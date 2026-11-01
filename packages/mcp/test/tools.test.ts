import { describe, expect, it, vi } from 'vitest';
import { PairingRejectedError, RequestTimeoutError, TransportError } from '@android-use/transport';

import { createToolHandlers, formatToolError } from '../src/tools.js';

function createDependencies() {
  return {
    status: vi.fn(async () => ({ deviceFingerprintSha256: 'fingerprint', activeHost: 'host-1', sessionActive: false, listenerPort: 47831 })),
    pair: vi.fn(async (_input: { code: string; hostName: string }) => ({ hostId: 'host-1', hostName: 'OpenCode', fingerprintSha256: 'host-pin' })),
    snapshot: vi.fn(async (_input: { mode: 'full' | 'compact' | 'actionable' }) => ({ deviceId: 'device-1', windowId: 'window-1', revision: 4, width: 100, height: 200, elements: [], truncated: false })),
    execute: vi.fn(async (_code: string, _timeoutMs: number) => ({ result: 4, logs: [], actions: [], durationMs: 2 })),
    disconnect: vi.fn(async () => ({ disconnected: true })),
    screenshot: vi.fn(async () => ({
      bytes: new Uint8Array([0xff, 0xd8, 0xff]), mimeType: 'image/jpeg' as const,
      width: 10, height: 20, timestampMillis: 123, deviceId: 'device-1', windowId: 'window-1',
    })),
    openApp: vi.fn(async (packageName: string, _timeoutMs: number) => ({ launched: true, packageName })),
    gesture: vi.fn(async (input: unknown) => ({ action: input, accepted: true })),
  };
}

describe('MCP tool handlers', () => {
  it('delegates valid inputs and preserves bounded dependency results', async () => {
    const deps = createDependencies();
    const tools = createToolHandlers(deps);

    await expect(tools.android_status({})).resolves.toMatchObject({ sessionActive: false });
    await expect(tools.android_pair({ code: ' abcd2345 ', hostName: ' OpenCode ' }))
      .resolves.toMatchObject({ hostName: 'OpenCode' });
    await expect(tools.android_snapshot({})).resolves.toMatchObject({ revision: 4 });
    await expect(tools.android_snapshot({ mode: 'compact' })).resolves.toMatchObject({ revision: 4 });
    await expect(tools.android_execute({ code: '1 + 3' })).resolves.toMatchObject({ result: 4 });
    await expect(tools.android_disconnect({})).resolves.toEqual({ disconnected: true });

    expect(deps.pair).toHaveBeenCalledWith({ code: 'ABCD2345', hostName: 'OpenCode' });
    expect(deps.snapshot).toHaveBeenNthCalledWith(1, { mode: 'full' });
    expect(deps.snapshot).toHaveBeenNthCalledWith(2, { mode: 'compact' });
    expect(deps.execute).toHaveBeenCalledWith('1 + 3', 1_000);
  });

  it('rejects malformed and over-limit inputs before invoking dependencies', async () => {
    const deps = createDependencies();
    const tools = createToolHandlers(deps);
    const invalidCalls: Array<() => Promise<unknown>> = [
      () => tools.android_status({ unexpected: true }),
      () => tools.android_pair({ code: 'bad', hostName: 'OpenCode' }),
      () => tools.android_pair({ code: 'ABCDEFGH', hostName: '   ' }),
      () => tools.android_pair({ code: 'ABCDEFGH', hostName: 'x'.repeat(65) }),
      () => tools.android_pair({ code: 'ABCDEFGH', hostName: 'host\nname' }),
      () => tools.android_snapshot({ mode: 'unknown' }),
      () => tools.android_execute({ code: '' }),
      () => tools.android_execute({ code: 'x'.repeat(100_001) }),
      () => tools.android_execute({ code: '1', timeoutMs: 49 }),
      () => tools.android_execute({ code: '1', timeoutMs: 60_001 }),
      () => tools.android_execute({ code: '1', timeoutMs: 1.5 }),
      () => tools.android_disconnect({ extra: true }),
      () => tools.android_screenshot({ extra: true }),
      () => tools.android_open_app({ packageName: 'not a package' }),
      () => tools.android_gesture({ action: 'swipe', x: 1, y: 1 }),
      () => tools.android_gesture({ action: 'tap', x: -1, y: 1 }),
      () => tools.android_gesture({ action: 'back', x: 1, y: 1 }),
    ];

    for (const invoke of invalidCalls) {
      await expect(invoke()).rejects.toMatchObject({ name: 'ZodError' });
    }
    expect(deps.status).not.toHaveBeenCalled();
    expect(deps.pair).not.toHaveBeenCalled();
    expect(deps.snapshot).not.toHaveBeenCalled();
    expect(deps.execute).not.toHaveBeenCalled();
    expect(deps.disconnect).not.toHaveBeenCalled();
    expect(deps.screenshot).not.toHaveBeenCalled();
    expect(deps.openApp).not.toHaveBeenCalled();
    expect(deps.gesture).not.toHaveBeenCalled();
  });

  it('delegates no-input screenshot capture and preserves image metadata', async () => {
    const deps = createDependencies();
    const tools = createToolHandlers(deps);
    await expect(tools.android_screenshot({})).resolves.toMatchObject({ width: 10, height: 20, mimeType: 'image/jpeg' });
    expect(deps.screenshot).toHaveBeenCalledOnce();
  });

  it('maps Android launch rejection to concise guidance without leaking error details', () => {
    expect(formatToolError(Object.assign(new Error('app launch was rejected by Android: private detail'), {
      code: 'INTERNAL', details: { reason: 'LAUNCH_REJECTED' },
    }))).toBe('Android rejected the app launch. Check the phone state and try again if safe.');
  });

  it('delegates strict app launch and gesture actions with bounded options', async () => {
    const deps = createDependencies();
    const tools = createToolHandlers(deps);
    await expect(tools.android_open_app({ packageName: 'com.android.settings' })).resolves.toMatchObject({ launched: true });
    await tools.android_gesture({ action: 'swipe', x: 1, y: 2, endX: 10, endY: 20, durationMillis: 200, timeoutMs: 900 });
    await tools.android_gesture({ action: 'back' });
    expect(deps.openApp).toHaveBeenCalledWith('com.android.settings', 5_000);
    expect(deps.gesture).toHaveBeenNthCalledWith(1, {
      action: 'swipe', x: 1, y: 2, endX: 10, endY: 20, durationMillis: 200, timeoutMs: 900,
    });
    expect(deps.gesture).toHaveBeenNthCalledWith(2, { action: 'back', timeoutMs: 5_000 });
  });

  it('maps known transport and runtime errors to actionable safe guidance', () => {
    const cases = [
      ['PAIRING_REQUIRED', /pair|approve/i],
      ['UNAUTHORIZED', /pair|approve/i],
      ['DISCONNECTED', /connect|device/i],
      ['AMBIGUOUS_TARGET', /multiple|narrow/i],
      ['STALE_REF', /stale|snapshot/i],
      ['TIMEOUT', /timed out|retry/i],
      ['INVALID_MESSAGE', /invalid|input/i],
    ] as const;

    for (const [code, expected] of cases) {
      const error = Object.assign(new Error('secret raw transport detail'), { code });
      expect(formatToolError(error)).toMatch(expected);
      expect(formatToolError(error)).not.toContain('secret raw transport detail');
    }
    expect(formatToolError(new Error('private arbitrary exception'))).not.toContain('private arbitrary exception');
  });

  it('maps actual transport disconnect and timeout errors to specific safe guidance', () => {
    expect(formatToolError(new TransportError('private socket failure'))).toMatch(/disconnected|connection/i);
    expect(formatToolError(new RequestTimeoutError('request-1', true))).toMatch(/timed out|timeout/i);
    expect(formatToolError(new TransportError('private socket failure'))).not.toContain('private socket failure');
  });

  it('explains phone-rejected and expired pairing requests', () => {
    expect(formatToolError(new PairingRejectedError('REJECTED'))).toMatch(/rejected/i);
    expect(formatToolError(new PairingRejectedError('EXPIRED'))).toMatch(/expired/i);
  });

  it('explains pairing approval timeout without exposing the transport message', () => {
    const error = new Error('phone approval timed out; pairing code was not resubmitted');
    expect(formatToolError(error)).toMatch(/approval.*timed out/i);
    expect(formatToolError(error)).not.toContain('pairing code was not resubmitted');
  });
});
