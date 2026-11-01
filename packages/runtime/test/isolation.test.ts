import { describe, expect, it } from 'vitest';

import { DEFAULT_ISOLATE_TIMEOUT_MS, evaluateInIsolate } from '../src/index.js';

describe('isolated-vm feasibility gate', () => {
  it('evaluates a basic expression inside the isolate', async () => {
    const result = await evaluateInIsolate('1 + 1');

    expect(result.value).toBe(2);
  });

  it('does not expose ambient Node capabilities to the isolate', async () => {
    await expect(evaluateInIsolate('typeof process')).resolves.toMatchObject({
      value: 'undefined',
    });
    await expect(evaluateInIsolate('typeof require')).resolves.toMatchObject({
      value: 'undefined',
    });
    await expect(evaluateInIsolate('process.version')).rejects.toThrow(
      /process is not defined/,
    );
    await expect(evaluateInIsolate("require('node:fs')")).rejects.toThrow(
      /require is not defined/,
    );
  });

  it('blocks constructor-based escapes to the host realm', async () => {
    const escape = "globalThis.constructor.constructor('return process')()";

    await expect(evaluateInIsolate(escape)).rejects.toThrow(
      /process is not defined/,
    );
  });

  it('terminates an infinite loop within the configured timeout', async () => {
    const timeoutMs = 250;
    const startedAt = performance.now();

    await expect(
      evaluateInIsolate('while (true) {}', { timeoutMs }),
    ).rejects.toThrow(/timed out/i);

    const elapsedMs = performance.now() - startedAt;

    expect(elapsedMs).toBeLessThan(5_000);
    expect(DEFAULT_ISOLATE_TIMEOUT_MS).toBeGreaterThan(0);
    expect(DEFAULT_ISOLATE_TIMEOUT_MS).toBeLessThanOrEqual(5_000);
  });
});
