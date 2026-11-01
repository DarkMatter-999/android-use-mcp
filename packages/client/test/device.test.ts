import { describe, expect, it } from 'vitest';
import { AndroidDevice } from '../src/index.js';

type RequestOptions = { timeoutMs?: number; signal?: AbortSignal };
type Call = { method: string; params: Record<string, unknown>; options?: RequestOptions };

const element = {
  ref: 'ref-continue',
  role: 'button',
  text: 'Continue',
  bounds: { left: 2, top: 3, right: 42, bottom: 27 },
  clickable: true,
  scrollable: false,
  editable: false,
  enabled: true,
  focused: false,
  visible: true,
  supportedActions: ['click'],
};

function createDevice(responses: unknown[] = []) {
  const calls: Call[] = [];
  const connection = {
    request: async <T>(method: string, params: Record<string, unknown>, options?: RequestOptions): Promise<T> => {
      calls.push({ method, params, ...(options ? { options } : {}) });
      const response = responses.shift();
      if (response instanceof Error) throw response;
      if (response !== undefined) return response as T;
      if (method === 'element.query') return { count: 1, revision: 12, elements: [element] } as T;
      if (method === 'element.action') return {
        actionId: 'action-1', action: params.action, accepted: true, verified: null,
        revisionBefore: 12, revisionAfter: 13,
      } as T;
      if (method === 'device.status') return {
        deviceFingerprintSha256: 'sha256-pin', activeHost: 'test-host', sessionActive: true, listenerPort: 47831,
      } as T;
      return {} as T;
    },
  };
  return { device: new AndroidDevice(connection as never), calls };
}

describe('AndroidDevice lazy locators', () => {
  it('creates locators without RPC and queries only when evaluated', async () => {
    const { device, calls } = createDevice();
    const locator = device.getByText('Continue', { exact: true });

    expect(calls).toEqual([]);
    await expect(locator.count()).resolves.toBe(1);
    expect(calls).toEqual([{
      method: 'element.query',
      params: { selector: { type: 'text', value: 'Continue', exact: true } },
    }]);
  });

  it('returns fresh visible state and bounds for a unique element', async () => {
    const { device, calls } = createDevice();
    const locator = device.getByText('Continue');

    await expect(locator.isVisible()).resolves.toBe(true);
    await expect(locator.bounds()).resolves.toEqual(element.bounds);
    expect(calls.map(({ method }) => method)).toEqual(['element.query', 'element.query']);
  });

  it('resolves a unique live element and pins its action to the observed revision', async () => {
    const { device, calls } = createDevice();

    await expect(device.getByText('Continue').click()).resolves.toMatchObject({ accepted: true, verified: null });
    expect(calls).toEqual([
      { method: 'element.query', params: { selector: { type: 'text', value: 'Continue' } } },
      {
        method: 'element.action',
        params: {
          action: 'click',
          selector: { type: 'ref', value: 'ref-continue', revision: 12 },
          expectedRevision: 12,
        },
      },
    ]);
  });

  it('preserves filters and explicit index while resolving a locator', async () => {
    const { device, calls } = createDevice([{ count: 2, revision: 8, elements: [element, { ...element, ref: 'ref-second' }] }]);

    await device.getByRole('button', { name: 'Continue', index: 1, enabled: true }).state();
    expect(calls[0]).toEqual({
      method: 'element.query',
      params: {
        selector: { type: 'role', value: 'button', name: 'Continue' },
        index: 1,
        enabled: true,
      },
    });
  });

  it('uses an explicit first selection without treating it as implicit ambiguity', async () => {
    const { device, calls } = createDevice([{ count: 2, revision: 8, elements: [element, { ...element, ref: 'ref-second' }] }]);

    await expect(device.getByText('Continue', { first: true }).state()).resolves.toMatchObject({ ref: 'ref-continue' });
    expect(calls[0]?.params).toEqual({ selector: { type: 'text', value: 'Continue' }, first: true });
  });

  it('rejects ambiguous matches instead of choosing the first element', async () => {
    const { device, calls } = createDevice([{ count: 2, revision: 4, elements: [element, { ...element, ref: 'ref-second' }] }]);

    await expect(device.getByText('Continue').click()).rejects.toMatchObject({ code: 'AMBIGUOUS_TARGET' });
    expect(calls).toHaveLength(1);
  });

  it('propagates stale-reference errors from the current query', async () => {
    const stale = Object.assign(new Error('reference is stale'), { code: 'STALE_REF' });
    const { device, calls } = createDevice([stale]);

    await expect(device.getByRef('ref-continue', 3).state()).rejects.toMatchObject({ code: 'STALE_REF' });
    expect(calls[0]?.params).toEqual({ selector: { type: 'ref', value: 'ref-continue', revision: 3 } });
  });

  it('rejects conflicting selection options before any RPC', async () => {
    const { device, calls } = createDevice();

    expect(() => device.getByText('Continue', { first: true, index: 0 })).toThrow(/first.*index|index.*first/i);
    expect(calls).toEqual([]);
  });

  it('forwards timeout and cancellation to locator queries and actions', async () => {
    const { device, calls } = createDevice();
    const controller = new AbortController();
    const locator = device.getByResourceId('app:id/continue', { timeoutMs: 800, signal: controller.signal });

    await locator.fill('value');
    expect(calls[0]?.options).toEqual({ timeoutMs: 800, signal: controller.signal });
    expect(calls[1]?.options).toEqual({ timeoutMs: 800, signal: controller.signal });
  });

  it('returns false for a missing locator visibility check but keeps count at zero', async () => {
    const { device } = createDevice([{ count: 0, revision: 7, elements: [] }, { count: 0, revision: 7, elements: [] }]);
    const locator = device.getByDescription('missing');

    await expect(locator.count()).resolves.toBe(0);
    await expect(locator.isVisible()).resolves.toBe(false);
  });

  it('forwards caller timeouts and abort signals through status and snapshots', async () => {
    const { device, calls } = createDevice();
    const controller = new AbortController();

    const status = await device.status({ timeoutMs: 700, signal: controller.signal });
    expect(status.deviceFingerprintSha256).toBe('sha256-pin');
    await device.snapshot({ mode: 'compact', timeoutMs: 900 });
    expect(calls).toEqual([
      { method: 'device.status', params: {}, options: { timeoutMs: 700, signal: controller.signal } },
      { method: 'snapshot.get', params: { mode: 'compact' }, options: { timeoutMs: 900 } },
    ]);
  });
});
