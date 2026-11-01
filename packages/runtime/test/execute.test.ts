import { describe, expect, it } from 'vitest';
import { AndroidDevice } from '@android-use/client';
import { ExecuteCodeError, executeCode } from '../src/index.js';

type Call = { method: string; params: Record<string, unknown>; signal?: AbortSignal };
const uiElement = {
  ref: 'ref-next', role: 'button', text: 'Next',
  bounds: { left: 1, top: 2, right: 20, bottom: 30 },
  clickable: true, scrollable: false, editable: false,
  enabled: true, focused: false, visible: true, supportedActions: ['click'],
};

function makeDevice(queryResponse: unknown = { count: 1, revision: 9, elements: [uiElement] }) {
  const calls: Call[] = [];
  const connection = {
    request: async <T>(method: string, params: Record<string, unknown>, options?: { signal?: AbortSignal }): Promise<T> => {
      calls.push({ method, params, ...(options?.signal ? { signal: options.signal } : {}) });
      if (method === 'snapshot.get') return {
        deviceId: 'device-1', windowId: 'window-2', revision: 9,
        width: 100, height: 200, elements: [uiElement], truncated: false,
      } as T;
      if (method === 'element.query') return queryResponse as T;
      if (method === 'element.action') return {
        actionId: 'action-1', action: params.action, accepted: true, verified: null,
        revisionBefore: 9, revisionAfter: 10,
      } as T;
      throw new Error(`unexpected RPC ${method}`);
    },
  };
  return { device: new AndroidDevice(connection as never), calls };
}

describe('executeCode isolated Android runtime', () => {
  it('batches snapshot and lazy locator action through the Android device bridge', async () => {
    const { device, calls } = makeDevice();

    const output = await executeCode(
      '(async () => { const snap = await android.snapshot(); const action = await android.getByText("Next", { exact: true }).click(); return { revision: snap.revision, accepted: action.accepted }; })()',
      { device },
    );

    expect(output.result).toEqual({ revision: 9, accepted: true });
    expect(output.actions).toHaveLength(1);
    expect(calls.map(({ method }) => method)).toEqual(['snapshot.get', 'element.query', 'element.action']);
    expect(calls[1]?.params).toEqual({ selector: { type: 'text', value: 'Next', exact: true } });
  });

  it('keeps logs within the output budget without double-counting their bytes', async () => {
    const { device } = makeDevice();

    await expect(executeCode('(async () => { console.log("hi"); return "ok"; })()', { device, maxResultBytes: 13 }))
      .resolves.toMatchObject({ result: 'ok', logs: ['hi'], actions: [] });
  });

  it('does not expose Node globals or constructor-based host escapes', async () => {
    const { device } = makeDevice();
    const result = await executeCode('({ process: typeof process, require: typeof require })', { device });

    expect(result.result).toEqual({ process: 'undefined', require: 'undefined' });
    await expect(executeCode("globalThis.constructor.constructor('return process')()", { device }))
      .rejects.toMatchObject({ code: 'EXECUTION_FAILED' });
    await expect(executeCode(`({}).constructor.constructor('return require("node:fs")')()`, { device }))
      .rejects.toMatchObject({ code: 'EXECUTION_FAILED' });
    await expect(executeCode('({ fetch: typeof fetch, process: typeof process, require: typeof require })', { device }))
      .resolves.toMatchObject({ result: { fetch: 'undefined', process: 'undefined', require: 'undefined' } });
    await expect(executeCode('({ bridge: typeof __bridge, call: typeof __call, hostReference: typeof __androidHostBridge })', { device }))
      .resolves.toMatchObject({ result: { bridge: 'undefined', call: 'undefined', hostReference: 'undefined' } });
  });

  it('terminates synchronous infinite JavaScript at the configured deadline', async () => {
    const { device } = makeDevice();

    await expect(executeCode('(function () { while (true) {} })()', { device, timeoutMs: 100 }))
      .rejects.toMatchObject({ code: 'TIMEOUT' });
  });

  it('rejects non-positive or non-finite execution and output limits', async () => {
    const { device } = makeDevice();

    for (const options of [{ timeoutMs: 0 }, { timeoutMs: Number.POSITIVE_INFINITY }, { memoryLimitMb: 0 }, { maxResultBytes: 0 }, { maxResultDepth: -1 }]) {
      await expect(executeCode('1', { device, ...options })).rejects.toBeInstanceOf(ExecuteCodeError);
      await expect(executeCode('1', { device, ...options })).rejects.toMatchObject({ code: 'INVALID_CONFIG' });
    }
  });

  it('bounds returned JSON by encoded bytes and nesting depth', async () => {
    const { device } = makeDevice();

    await expect(executeCode('"x".repeat(100)', { device, maxResultBytes: 20 }))
      .rejects.toMatchObject({ code: 'RESULT_TOO_LARGE' });
    await expect(executeCode('({ a: { b: { c: 1 } } })', { device, maxResultDepth: 2 }))
      .rejects.toMatchObject({ code: 'RESULT_TOO_LARGE' });
  });

  it('bounds user logs and rejects user-thrown and non-JSON results safely', async () => {
    const { device } = makeDevice();

    await expect(executeCode('console.log("x".repeat(30))', { device, maxResultBytes: 10 }))
      .rejects.toMatchObject({ code: 'RESULT_TOO_LARGE' });
    await expect(executeCode('(async () => { throw new Error("user failure"); })()', { device }))
      .rejects.toMatchObject({ code: 'EXECUTION_FAILED', message: expect.stringContaining('user failure') });
    await expect(executeCode('undefined', { device })).rejects.toMatchObject({ code: 'EXECUTION_FAILED' });
  });

  it('aborts a stalled RPC when the execution deadline expires', async () => {
    let capturedSignal: AbortSignal | undefined;
    const connection = {
      request: async <T>(_method: string, _params: Record<string, unknown>, options?: { signal?: AbortSignal }): Promise<T> => {
        capturedSignal = options?.signal;
        return new Promise<T>((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new Error('RPC aborted')), { once: true });
        });
      },
    };
    const device = new AndroidDevice(connection as never);

    await expect(executeCode('android.snapshot()', { device, timeoutMs: 60 }))
      .rejects.toMatchObject({ code: 'TIMEOUT' });
    expect(capturedSignal?.aborted).toBe(true);
  });

  it('does not dispatch more actions than the bounded action trace can report', async () => {
    let dispatchedActions = 0;
    const connection = {
      request: async <T>(method: string, params: Record<string, unknown>): Promise<T> => {
        if (method === 'element.query') return { count: 1, revision: 9, elements: [uiElement] } as T;
        if (method === 'element.action') {
          dispatchedActions += 1;
          return {
            actionId: `action-${dispatchedActions}`, action: params.action, accepted: true, verified: null,
            revisionBefore: 9, revisionAfter: 9,
          } as T;
        }
        throw new Error(`unexpected RPC ${method}`);
      },
    };

    await expect(executeCode('(async () => { for (let i = 0; i < 101; i += 1) await android.getByText("Next").click(); return true; })()', {
      device: new AndroidDevice(connection as never), timeoutMs: 10_000,
    })).rejects.toMatchObject({ code: 'RESULT_TOO_LARGE' });
    expect(dispatchedActions).toBe(100);
  });

  it('cancels pending device RPC when the caller aborts execution', async () => {
    let capturedSignal: AbortSignal | undefined;
    let markStarted!: () => void;
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    const connection = {
      request: async <T>(_method: string, _params: Record<string, unknown>, options?: { signal?: AbortSignal }): Promise<T> => {
        capturedSignal = options?.signal;
        markStarted();
        return new Promise<T>((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new Error('RPC aborted')), { once: true });
        });
      },
    };
    const device = new AndroidDevice(connection as never);
    const controller = new AbortController();
    const execution = executeCode('android.snapshot()', { device, signal: controller.signal });
    await started;
    controller.abort();

    await expect(execution).rejects.toMatchObject({ code: 'CANCELLED' });
    expect(capturedSignal?.aborted).toBe(true);
  });

  it('honors cancellation that arrives while the isolate is being prepared', async () => {
    const { device } = makeDevice();
    const controller = new AbortController();
    const execution = executeCode('1', { device, signal: controller.signal });
    controller.abort();

    await expect(execution).rejects.toMatchObject({ code: 'CANCELLED' });
  });

  it('rejects cyclic results rather than leaking isolate data structures', async () => {
    const { device } = makeDevice();

    await expect(executeCode('(() => { const x = {}; x.self = x; return x; })()', { device }))
      .rejects.toMatchObject({ code: 'EXECUTION_FAILED' });
  });
});
