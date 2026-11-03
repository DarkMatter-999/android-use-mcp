import { isIP } from 'node:net';
import Bonjour from 'bonjour-service';

export interface DiscoveredService {
  type: string;
  host: string;
  addresses: string[];
  port: number;
  txt: Record<string, string | undefined>;
}

export interface DiscoveryAdapter {
  start(
    onService: (service: DiscoveredService) => void,
    onError: (error: Error) => void,
    onServiceDown?: (service: DiscoveredService) => void,
  ): { stop(): void };
  destroy(): void;
}

const SERVICE_TYPE = '_androiduse._tcp';
const SERVICE_PATH = '/rpc';
const DEFAULT_DISCOVERY_TIMEOUT_MS = 5_000;

/** DNS-SD adapter for the MCP host; owns exactly one Bonjour client/browser pair. */
export function createBonjourDiscoveryAdapter(): DiscoveryAdapter {
  let client: Bonjour | undefined;
  let browser: ReturnType<Bonjour['find']> | undefined;
  return {
    start(onService, onError, onServiceDown) {
      client = new Bonjour({}, (error: unknown) => {
        onError(error instanceof Error ? error : new Error('DNS-SD browser failed'));
      });
      browser = client.find({ type: 'androiduse', protocol: 'tcp' });
      const toDiscoveredService = (service: import('bonjour-service').Service): DiscoveredService => {
        const txt: Record<string, string | undefined> = {};
        if (service.txt && typeof service.txt === 'object') {
          for (const [key, value] of Object.entries(service.txt as Record<string, unknown>)) {
            if (typeof value === 'string') txt[key] = value;
          }
        }
        return {
          type: `_${service.type}._${service.protocol}`,
          host: service.host,
          addresses: Array.isArray(service.addresses)
            ? service.addresses.filter((address): address is string => typeof address === 'string')
            : [],
          port: service.port,
          txt,
        };
      };
      browser.on('up', (service) => onService(toDiscoveredService(service)));
      browser.on('down', (service) => onServiceDown?.(toDiscoveredService(service)));
      browser.start();
      return { stop: () => browser?.stop() };
    },
    destroy() {
      browser?.stop();
      browser = undefined;
      client?.destroy();
      client = undefined;
    },
  };
}

/** Resolve exactly one DNS-SD endpoint, and always release the browse resources. */
export function discoverCompanionUrl(
  adapter: DiscoveryAdapter,
  timeoutMs = DEFAULT_DISCOVERY_TIMEOUT_MS,
): Promise<string> {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    return Promise.reject(new RangeError('discovery timeout must be a positive safe integer'));
  }

  return new Promise((resolve, reject) => {
    const found = new Map<string, string>();
    let invalidRecord = false;
    let browser: { stop(): void } | undefined;
    let timer: NodeJS.Timeout | undefined;
    let settled = false;
    let starting = true;
    let deferredResult: { error: Error } | { url: string } | undefined;

    const cleanup = () => {
      if (timer) clearTimeout(timer);
      try { browser?.stop(); } catch { /* Cleanup must not mask the discovery result. */ }
      try { adapter.destroy(); } catch { /* Cleanup must not mask the discovery result. */ }
    };
    const finish = (error?: Error, url?: string) => {
      if (settled) return;
      if (starting) {
        deferredResult = error ? { error } : { url: url! };
        return;
      }
      settled = true;
      cleanup();
      if (error) reject(error);
      else resolve(url!);
    };

    try {
      browser = adapter.start((service) => {
        const validated = validateService(service);
        if (!validated) {
          invalidRecord = true;
          return;
        }
        found.set(validated.key, validated.url);
      }, (error) => finish(error), (service) => {
        const validated = validateService(service);
        if (validated) found.delete(validated.key);
      });
      starting = false;
      if (deferredResult) {
        if ('error' in deferredResult) finish(deferredResult.error);
        else finish(undefined, deferredResult.url);
        return;
      }
      timer = setTimeout(() => {
        if (invalidRecord) return finish(new Error('invalid Android Use DNS-SD service record'));
        if (found.size === 0) return finish(new Error('no Android Use companion found on the local network'));
        if (found.size > 1) return finish(new Error('multiple Android Use companions found; discovery requires exactly one'));
        finish(undefined, found.values().next().value);
      }, timeoutMs);
      timer.unref?.();
    } catch (error) {
      starting = false;
      finish(error instanceof Error ? error : new Error('DNS-SD discovery failed'));
    }
  });
}

function validateService(service: DiscoveredService): { key: string; url: string } | undefined {
  if (normalizeServiceType(service.type) !== SERVICE_TYPE || service.txt['path'] !== SERVICE_PATH) return undefined;
  if (!Number.isSafeInteger(service.port) || service.port < 1 || service.port > 65_535) return undefined;
  const host = service.host.trim().replace(/\.$/, '');
  if (!host || /[\s/@?#]/.test(host)) return undefined;
  const address = service.addresses.find((candidate) => isIP(candidate) === 4);
  if (!address) return undefined;
  try {
    const url = new URL(`wss://${address}:${service.port}${SERVICE_PATH}`);
    if (url.protocol !== 'wss:' || url.username || url.password || url.pathname !== SERVICE_PATH) return undefined;
    return { key: `${url.hostname.toLowerCase()}:${service.port}${SERVICE_PATH}`, url: url.toString() };
  } catch {
    return undefined;
  }
}

function normalizeServiceType(type: string): string {
  return type.trim().replace(/\.$/, '').toLowerCase();
}
