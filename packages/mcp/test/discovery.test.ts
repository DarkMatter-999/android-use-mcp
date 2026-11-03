import { describe, expect, it, vi } from 'vitest';

import { discoverCompanionUrl, type DiscoveredService, type DiscoveryAdapter } from '../src/discovery.js';

function createAdapter(services: DiscoveredService[] = []): DiscoveryAdapter & {
  stop: ReturnType<typeof vi.fn>;
  destroy: ReturnType<typeof vi.fn>;
} {
  const stop = vi.fn();
  const destroy = vi.fn();
  return {
    start: (onService, _onError) => {
      for (const service of services) onService(service);
      return { stop };
    },
    destroy,
    stop,
  };
}

const service = (overrides: Partial<DiscoveredService> = {}): DiscoveredService => ({
  type: '_androiduse._tcp',
  host: 'android-use.local.',
  addresses: ['192.168.1.24'],
  port: 43419,
  txt: { path: '/rpc' },
  ...overrides,
});

describe('DNS-SD companion discovery', () => {
  it('constructs a WSS endpoint from its resolved IPv4 address and cleans up', async () => {
    const adapter = createAdapter([service()]);

    await expect(discoverCompanionUrl(adapter, 2)).resolves.toBe('wss://192.168.1.24:43419/rpc');
    expect(adapter.stop).toHaveBeenCalledOnce();
    expect(adapter.destroy).toHaveBeenCalledOnce();
  });

  it('rejects services that have no resolved IPv4 endpoint', async () => {
    const adapter = createAdapter([service({ addresses: ['2001:db8::4'] })]);

    await expect(discoverCompanionUrl(adapter, 2)).rejects.toThrow(/invalid/i);
  });

  it('rejects when no companion is discovered', async () => {
    const adapter = createAdapter();

    await expect(discoverCompanionUrl(adapter, 2)).rejects.toThrow(/no android use companion/i);
    expect(adapter.stop).toHaveBeenCalledOnce();
    expect(adapter.destroy).toHaveBeenCalledOnce();
  });

  it('rejects multiple unique companions instead of choosing one', async () => {
    const adapter = createAdapter([service(), service({ host: 'android-use-2.local.', addresses: ['192.168.1.25'] })]);

    await expect(discoverCompanionUrl(adapter, 2)).rejects.toThrow(/multiple android use companions/i);
    expect(adapter.stop).toHaveBeenCalledOnce();
    expect(adapter.destroy).toHaveBeenCalledOnce();
  });

  it('does not select a companion that disappears before the browse window ends', async () => {
    const stop = vi.fn();
    const destroy = vi.fn();
    const adapter: DiscoveryAdapter = {
      start: (onService, _onError, onServiceDown) => {
        onService(service());
        onServiceDown?.(service());
        return { stop };
      },
      destroy,
    };

    await expect(discoverCompanionUrl(adapter, 2)).rejects.toThrow(/no android use companion/i);
    expect(stop).toHaveBeenCalledOnce();
    expect(destroy).toHaveBeenCalledOnce();
  });

  it('rejects malformed service records and always cleans up', async () => {
    const malformed = [
      service({ type: '_other._tcp' }),
      service({ txt: { path: '/not-rpc' } }),
      service({ host: '' }),
      service({ host: 'bad host' }),
      service({ port: 0 }),
    ];
    for (const record of malformed) {
      const adapter = createAdapter([record]);
      await expect(discoverCompanionUrl(adapter, 1)).rejects.toThrow(/invalid/i);
      expect(adapter.stop).toHaveBeenCalledOnce();
      expect(adapter.destroy).toHaveBeenCalledOnce();
    }
  });

  it('rejects a browser error and stops browsing', async () => {
    const stop = vi.fn();
    const destroy = vi.fn();
    const adapter: DiscoveryAdapter = {
      start: (_onService, onError) => {
        queueMicrotask(() => onError(new Error('multicast unavailable')));
        return { stop };
      },
      destroy,
    };

    await expect(discoverCompanionUrl(adapter, 10)).rejects.toThrow(/multicast unavailable/i);
    expect(stop).toHaveBeenCalledOnce();
    expect(destroy).toHaveBeenCalledOnce();
  });

  it('cleans up even when the browser reports an error synchronously during startup', async () => {
    const stop = vi.fn();
    const destroy = vi.fn();
    const adapter: DiscoveryAdapter = {
      start: (_onService, onError) => {
        onError(new Error('startup failed'));
        return { stop };
      },
      destroy,
    };

    await expect(discoverCompanionUrl(adapter, 10)).rejects.toThrow(/startup failed/i);
    expect(stop).toHaveBeenCalledOnce();
    expect(destroy).toHaveBeenCalledOnce();
  });
});
