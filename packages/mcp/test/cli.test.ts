import { describe, expect, it, vi } from 'vitest';

import { runCli, type CliServices } from '../src/cli.js';
import type { McpToolDependencies } from '../src/tools.js';

function createServices(overrides: Partial<CliServices> = {}) {
  const stderr: string[] = [];
  const close = vi.fn(async () => {});
  const disconnect = vi.fn(async () => ({ disconnected: true }));
  const dependencies = {
    status: vi.fn(async () => ({})),
    pair: vi.fn(async () => ({})),
    snapshot: vi.fn(async () => ({})),
    execute: vi.fn(async () => ({})),
    disconnect,
    screenshot: vi.fn(async () => ({
      bytes: new Uint8Array([0xff, 0xd8, 0xff]), mimeType: 'image/jpeg' as const,
      width: 1, height: 1, timestampMillis: 1, deviceId: 'device', windowId: 'window',
    })),
    openApp: vi.fn(async (packageName: string) => ({ launched: true, packageName })),
    listApps: vi.fn(async () => ({ apps: [] })),
    gesture: vi.fn(async () => ({ accepted: true })),
  } satisfies McpToolDependencies;
  const services: CliServices = {
    readCertificate: vi.fn(async () => 'device-certificate-pem'),
    loadIdentity: vi.fn(async () => ({
      hostId: 'f38b4f4e-63a0-4126-97ca-2c5fc57df39e',
      certificatePem: 'host-certificate-pem',
      privateKeyPem: 'host-private-key',
      fingerprintSha256: 'AB'.repeat(32),
    })),
    createDependencies: vi.fn(() => dependencies),
    runServer: vi.fn(async () => ({ close })),
    writeStderr: vi.fn((message: string) => { stderr.push(message); }),
    ...overrides,
  };
  return { services, stderr, close, disconnect, dependencies };
}

const args = ['--url', 'wss://192.0.2.10:47831/rpc', '--device-certificate', '/tmp/device.pem', '--device-certificate-pin', 'CD'.repeat(32)];

describe('MCP CLI lifecycle', () => {
  it('writes invalid startup configuration only to stderr and does not load identities', async () => {
    const { services, stderr } = createServices();
    const signals: string[] = [];
    const stdoutWrite = vi.spyOn(process.stdout, 'write');

    await expect(runCli([], {}, services, { once: (signal) => { signals.push(signal); } })).resolves.toBe(1);
    expect(stderr.join('')).toMatch(/missing required settings/i);
    expect(stdoutWrite).not.toHaveBeenCalled();
    expect(services.loadIdentity).not.toHaveBeenCalled();
    expect(services.runServer).not.toHaveBeenCalled();
    expect(signals).toEqual([]);
    stdoutWrite.mockRestore();
  });

  it('loads configured credentials, starts lazily, and cleans up once on termination', async () => {
    const { services, close, disconnect } = createServices();
    const signals = new Map<string, () => void>();

    await expect(runCli(args, {}, services, { once: (signal, handler) => { signals.set(signal, handler); } })).resolves.toBe(0);
    expect(services.readCertificate).toHaveBeenCalledWith('/tmp/device.pem');
    expect(services.loadIdentity).toHaveBeenCalledWith(expect.stringMatching(/android-use-mcp$/));
    expect(services.runServer).toHaveBeenCalledTimes(1);
    expect(signals.has('SIGINT')).toBe(true);
    expect(signals.has('SIGTERM')).toBe(true);

    await signals.get('SIGTERM')?.();
    await signals.get('SIGINT')?.();
    expect(close).toHaveBeenCalledTimes(1);
    expect(disconnect).toHaveBeenCalledTimes(1);
  });
});
