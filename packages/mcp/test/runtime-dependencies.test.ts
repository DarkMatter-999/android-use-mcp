import { describe, expect, it, vi } from 'vitest';

import { createRuntimeDependencies } from '../src/runtime-dependencies.js';
import type { HostIdentity, RpcConnection, RpcConnectionConfig } from '@android-use/transport';

const hostIdentity: HostIdentity = {
  hostId: 'f38b4f4e-63a0-4126-97ca-2c5fc57df39e',
  certificatePem: 'unused-in-fake',
  privateKeyPem: 'unused-in-fake',
  fingerprintSha256: 'AB'.repeat(32),
};
const config: RpcConnectionConfig = {
  url: 'wss://192.0.2.10:47831/rpc',
  hostIdentity,
  deviceCertificatePem: 'unused-in-fake',
  deviceFingerprintSha256: 'CD'.repeat(32),
};

class FakeConnection {
  private listeners = new Set<(event: { type: 'connected' | 'disconnected' | 'closed'; reason?: string }) => void>();
  close = vi.fn(() => { this.emit({ type: 'closed' }); });
  onEvent = vi.fn((listener: (event: { type: 'connected' | 'disconnected' | 'closed'; reason?: string }) => void) => {
    this.listeners.add(listener);
    listener({ type: 'connected' });
    return () => this.listeners.delete(listener);
  });
  disconnectUnexpectedly() { this.emit({ type: 'disconnected', reason: 'test disconnect' }); }
  request = vi.fn(async (method: string, params: Record<string, unknown>) => {
    if (method === 'pairing.begin') return { challengeId: 'challenge-1', status: 'APPROVED' };
    if (method === 'device.status') return {
      deviceFingerprintSha256: 'CD'.repeat(32), activeHost: 'test-host', sessionActive: false, listenerPort: 47831,
    };
    if (method === 'snapshot.get') return {
      deviceId: 'device-1', windowId: 'window-1', revision: 7,
      width: 100, height: 200, elements: [], truncated: false,
    };
    if (method === 'screenshot.capture') return {
      imageBase64: '/9j/', mimeType: 'image/jpeg', width: 10, height: 20,
      timestampMillis: 123, deviceId: 'device-1', windowId: 'window-1',
    };
    if (method === 'app.launch') return { launched: true, packageName: params['packageName'] };
    if (method === 'element.action' || method === 'gesture.perform') return {
      actionId: 'action-1', action: params['action'], accepted: true, verified: null,
      revisionBefore: 1, revisionAfter: 1,
    };
    throw new Error(`unexpected RPC ${method}: ${JSON.stringify(params)}`);
  });

  private emit(event: { type: 'connected' | 'disconnected' | 'closed'; reason?: string }) {
    for (const listener of [...this.listeners]) listener(event);
  }
}

describe('lazy MCP runtime dependencies', () => {
  it('does not connect until a device tool is invoked and reuses the active device', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    expect(connect).not.toHaveBeenCalled();
    await dependencies.status();
    await dependencies.snapshot({ mode: 'compact' });
    expect(connect).toHaveBeenCalledTimes(1);
    expect(connection.request).toHaveBeenCalledTimes(2);
    connection.disconnectUnexpectedly();
    await dependencies.status();
    expect(connect).toHaveBeenCalledTimes(2);
  });

  it('shares one connection attempt across simultaneous first tool calls', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    await Promise.all([dependencies.status(), dependencies.snapshot({ mode: 'compact' })]);
    expect(connect).toHaveBeenCalledTimes(1);
  });

  it('pairs using the configured host identity and reuses then clears the connection', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    await expect(dependencies.pair({ code: 'ABCD2345', hostName: 'OpenCode' }))
      .resolves.toMatchObject({ hostId: hostIdentity.hostId, hostName: 'OpenCode' });
    expect(connection.request).toHaveBeenCalledWith('pairing.begin', {
      code: 'ABCD2345', hostId: hostIdentity.hostId, hostName: 'OpenCode',
    }, expect.any(Object));
    await dependencies.status();
    expect(connect).toHaveBeenCalledTimes(1);

    await expect(dependencies.disconnect()).resolves.toEqual({ disconnected: true });
    await dependencies.disconnect();
    await dependencies.status();
    expect(connection.close).toHaveBeenCalledTimes(1);
    expect(connect).toHaveBeenCalledTimes(2);
  });

  it('runs JavaScript against the same lazy Android device instance', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    await expect(dependencies.execute('android.snapshot()', 1_000)).resolves.toMatchObject({
      result: { deviceId: 'device-1', revision: 7 },
      actions: [],
      logs: [],
    });
    expect(connect).toHaveBeenCalledTimes(1);
    expect(connection.request).toHaveBeenCalledWith('snapshot.get', { mode: 'full' }, expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it('delegates screenshot capture to the same authenticated Android device', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    await expect(dependencies.screenshot()).resolves.toMatchObject({
      mimeType: 'image/jpeg', width: 10, height: 20, deviceId: 'device-1',
    });
    expect(connection.request).toHaveBeenCalledWith('screenshot.capture', {}, undefined);
  });

  it('delegates app launch and coordinate/global actions to the same device instance', async () => {
    const connection = new FakeConnection();
    const connect = vi.fn(async () => connection as unknown as RpcConnection);
    const dependencies = createRuntimeDependencies(config, { connect });

    await expect(dependencies.openApp('com.android.settings', 800)).resolves.toMatchObject({ launched: true });
    await dependencies.gesture({ action: 'swipe', x: 1, y: 2, endX: 5, endY: 6, durationMillis: 300, timeoutMs: 900 });
    await dependencies.gesture({ action: 'back', timeoutMs: 500 });

    expect(connection.request).toHaveBeenCalledWith('app.launch', { packageName: 'com.android.settings' }, expect.objectContaining({ timeoutMs: 800 }));
    expect(connection.request).toHaveBeenCalledWith('gesture.perform', expect.objectContaining({ action: 'swipe', x: 1, endX: 5, durationMillis: 300 }), expect.objectContaining({ timeoutMs: 900 }));
    expect(connection.request).toHaveBeenCalledWith('element.action', expect.objectContaining({ action: 'back' }), expect.objectContaining({ timeoutMs: 500 }));
    expect(connect).toHaveBeenCalledOnce();
  });
});
