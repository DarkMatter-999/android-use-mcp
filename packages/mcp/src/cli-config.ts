import { homedir } from 'node:os';
import { join } from 'node:path';

export interface CliConfig {
  readonly url: string | undefined;
  readonly discovery: boolean;
  readonly certPath: string;
  readonly pin: string;
  readonly identityDir: string;
}

const SUPPORTED_OPTIONS = new Set([
  '--url',
  '--discover',
  '--device-certificate',
  '--device-certificate-pin',
  '--identity-dir',
]);

export function parseCliConfig(
  args: readonly string[],
  env: NodeJS.ProcessEnv = process.env,
  defaultIdentityDir = join(homedir(), '.config', 'android-use-mcp'),
): CliConfig {
  const values = new Map<string, string>();
  for (let index = 0; index < args.length; index += 1) {
    const option = args[index];
    if (option === undefined) break;
    if (option === '--discover') {
      values.set(option, 'true');
      continue;
    }
    if (!SUPPORTED_OPTIONS.has(option)) throw new Error(`Unknown option: ${option}`);
    const value = args[index + 1];
    if (value === undefined || value.length === 0 || value.startsWith('--')) {
      throw new Error(`Missing value for ${option}`);
    }
    values.set(option, value);
    index += 1;
  }

  const explicitUrl = values.get('--url');
  const explicitDiscovery = values.has('--discover');
  const environmentDiscovery = env['ANDROID_USE_DISCOVERY'];
  if (environmentDiscovery !== undefined && environmentDiscovery !== 'true' && environmentDiscovery !== 'false') {
    throw new Error('ANDROID_USE_DISCOVERY must be "true" or "false"');
  }
  const discovery = explicitDiscovery || (explicitUrl === undefined && environmentDiscovery === 'true');
  const urlValue = explicitUrl ?? (explicitDiscovery ? undefined : env['ANDROID_USE_URL']);
  const certPath = values.get('--device-certificate') ?? env['ANDROID_USE_DEVICE_CERTIFICATE'];
  const pinValue = values.get('--device-certificate-pin') ?? env['ANDROID_USE_DEVICE_CERTIFICATE_PIN'];
  const identityDir = values.get('--identity-dir') ?? env['ANDROID_USE_IDENTITY_DIR'] ?? defaultIdentityDir;
  const missing = [
    urlValue === undefined && !discovery && '--url',
    certPath === undefined && '--device-certificate',
    pinValue === undefined && '--device-certificate-pin',
  ].filter((entry): entry is string => Boolean(entry));
  if (missing.length > 0) throw new Error(`Missing required settings: ${missing.join(', ')}`);

  if (discovery && urlValue !== undefined) throw new Error('discovery mode cannot be used with --url');
  if (!discovery && urlValue === undefined) throw new Error('missing required settings: --url');

  let url: string | undefined;
  if (urlValue !== undefined) {
    let parsedUrl: URL;
    try {
      parsedUrl = new URL(urlValue);
    } catch {
      throw new Error('--url must be a valid wss:// endpoint');
    }
    if (parsedUrl.protocol !== 'wss:' || !parsedUrl.hostname || parsedUrl.username || parsedUrl.password) {
      throw new Error('--url must be a valid wss:// endpoint without embedded credentials');
    }
    url = parsedUrl.toString();
  }

  const pin = pinValue!.replaceAll(':', '').toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(pin)) throw new Error('--device-certificate-pin must be a 32-byte SHA-256 fingerprint');
  return { url, discovery, certPath: certPath!, pin, identityDir };
}
