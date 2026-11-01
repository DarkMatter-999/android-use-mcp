import { describe, expect, it } from 'vitest';
import { AndroidDevice } from '../src/index.js';

function createDevice(responses: unknown[] = []) {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  const connection = {
    request: async <T>(method: string, params: Record<string, unknown>): Promise<T> => {
      calls.push({ method, params });
      const value = responses.shift();
      if (value instanceof Error) throw value;
      return (value ?? { actionId: 'action-1', action: params['action'], accepted: true, verified: null, revisionBefore: 0, revisionAfter: 0 }) as T;
    },
  };
  return { device: new AndroidDevice(connection as never), calls };
}

describe('AndroidDevice bounded action helpers', () => {
  it('validates and dispatches a coordinate swipe through the shared action RPC', async () => {
    const { device, calls } = createDevice();
    await device.performGesture('swipe', { x: 10, y: 20, endX: 90, endY: 180, durationMillis: 400 });
    expect(calls[0]?.method).toBe('gesture.perform');
    expect(calls[0]?.params).toMatchObject({ action: 'swipe', x: 10, y: 20, endX: 90, endY: 180, durationMillis: 400 });
    await expect(device.performGesture('swipe', { x: -1, y: 1, endX: 2, endY: 2 })).rejects.toThrow(/coordinate/i);
  });

  it('validates global actions and rejects malformed or non-allowlisted app packages before RPC', async () => {
    const { device, calls } = createDevice();
    await expect(device.performGlobalAction('back')).resolves.toMatchObject({ accepted: true });
    await expect(Promise.resolve().then(() => device.launchApp('not a package'))).rejects.toThrow(/package/i);
    await expect(Promise.resolve().then(() => device.launchApp('com.example.unknown'))).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(calls.map((call) => call.method)).toEqual(['element.action']);
  });

  it('launches only a phone-allowlisted package through the app.launch method', async () => {
    const { device, calls } = createDevice();
    await device.launchApp('com.android.settings');
    expect(calls[0]).toEqual({ method: 'app.launch', params: { packageName: 'com.android.settings' } });
  });

  it('re-resolves a selector between each bounded scroll and stops when visible', async () => {
    const hidden = { count: 1, revision: 3, elements: [{ visible: false }] };
    const shown = { count: 1, revision: 4, elements: [{ visible: true }] };
    const scrollable = { count: 1, revision: 3, elements: [{ ref: 'list', visible: true }] };
    const action = { actionId: 'a', action: 'scroll', accepted: true, verified: null, revisionBefore: 3, revisionAfter: 4 };
    const { device, calls } = createDevice([hidden, scrollable, action, shown]);
    const result = await device.getByResourceId('list').scrollUntilVisible(
      device.getByText('item'), { direction: 'down', maxSteps: 2 },
    );
    expect(result).toMatchObject({ visible: true, steps: 1, exhausted: false });
    expect(calls.map(({ method }) => method)).toEqual(['element.query', 'element.query', 'element.action', 'element.query']);
  });

  it('returns immediately for a visible target and validates the step bound before RPC', async () => {
    const visible = { count: 1, revision: 3, elements: [{ visible: true }] };
    const { device, calls } = createDevice([visible]);
    const target = device.getByText('item');
    await expect(device.getByResourceId('list').scrollUntilVisible(target, { direction: 'down', maxSteps: 2 }))
      .resolves.toMatchObject({ visible: true, steps: 0, actions: [] });
    const invalid = createDevice();
    await expect(invalid.device.getByResourceId('list').scrollUntilVisible(
      invalid.device.getByText('item'), { direction: 'down', maxSteps: 21 },
    )).rejects.toThrow(/maxSteps/i);
    expect(invalid.calls).toEqual([]);
    expect(calls).toHaveLength(1);
  });

  it('reports max-step exhaustion and rejects an ambiguous target before scrolling', async () => {
    const hidden = { count: 1, revision: 3, elements: [{ visible: false }] };
    const scrollable = { count: 1, revision: 3, elements: [{ ref: 'list', visible: true }] };
    const action = { actionId: 'a', action: 'scroll', accepted: true, verified: null, revisionBefore: 3, revisionAfter: 4 };
    const exhaustedDevice = createDevice([hidden, scrollable, action, hidden]);
    const exhausted = await exhaustedDevice.device.getByResourceId('list').scrollUntilVisible(
      exhaustedDevice.device.getByText('item'), { direction: 'down', maxSteps: 1 },
    );
    expect(exhausted).toMatchObject({ visible: false, steps: 1, exhausted: true });

    const ambiguousDevice = createDevice([{ count: 2, revision: 3, elements: [{ visible: false }, { visible: false }] }]);
    await expect(ambiguousDevice.device.getByResourceId('list').scrollUntilVisible(
      ambiguousDevice.device.getByText('item'), { direction: 'down', maxSteps: 2 },
    )).rejects.toMatchObject({ code: 'AMBIGUOUS_TARGET' });
    expect(ambiguousDevice.calls).toHaveLength(1);
  });
});
