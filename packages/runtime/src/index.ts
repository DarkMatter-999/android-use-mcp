import ivm from 'isolated-vm';

/** Default wall-clock limit for one isolate evaluation. */
export const DEFAULT_ISOLATE_TIMEOUT_MS = 1_000;

/** Default heap ceiling for one isolate, in MiB. */
export const DEFAULT_ISOLATE_MEMORY_MB = 32;

export interface IsolateEvaluationOptions {
  timeoutMs?: number;
  memoryLimitMb?: number;
}

export interface IsolateEvaluation {
  value: unknown;
  durationMs: number;
}

/** Evaluate source in a fresh V8 isolate without exposing host references. */
export async function evaluateInIsolate(
  source: string,
  options: IsolateEvaluationOptions = {},
): Promise<IsolateEvaluation> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_ISOLATE_TIMEOUT_MS;
  const memoryLimitMb = options.memoryLimitMb ?? DEFAULT_ISOLATE_MEMORY_MB;
  const startedAt = performance.now();
  const isolate = new ivm.Isolate({ memoryLimit: memoryLimitMb });

  try {
    const context = await isolate.createContext();
    const script = await isolate.compileScript(source, {
      filename: 'isolate-input.js',
    });
    const value: unknown = await script.run(context, {
      timeout: timeoutMs,
      copy: true,
    });

    return { value, durationMs: performance.now() - startedAt };
  } finally {
    isolate.dispose();
  }
}
