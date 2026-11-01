import ivm from 'isolated-vm';
import { selectorSchema, type ActionResult, type Selector } from '@android-use/protocol';
import type { AndroidDevice, Locator, LocatorOptions, ScrollDirection, GestureKind, GlobalAction } from '@android-use/client';

/** Default wall-clock limit for one isolate evaluation. */
export const DEFAULT_ISOLATE_TIMEOUT_MS = 1_000;

/** Default heap ceiling for one isolate, in MiB. */
export const DEFAULT_ISOLATE_MEMORY_MB = 32;

const MAX_ISOLATE_TIMEOUT_MS = 60_000;
const MIN_ISOLATE_MEMORY_MB = 8;
const MAX_ISOLATE_MEMORY_MB = 512;

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
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > MAX_ISOLATE_TIMEOUT_MS) {
    throw new RangeError(`timeoutMs must be a positive finite number no greater than ${MAX_ISOLATE_TIMEOUT_MS}`);
  }
  if (!Number.isSafeInteger(memoryLimitMb) || memoryLimitMb < MIN_ISOLATE_MEMORY_MB || memoryLimitMb > MAX_ISOLATE_MEMORY_MB) {
    throw new RangeError(`memoryLimitMb must be an integer between ${MIN_ISOLATE_MEMORY_MB} and ${MAX_ISOLATE_MEMORY_MB}`);
  }
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

export type ExecuteCodeErrorCode = 'INVALID_CONFIG' | 'TIMEOUT' | 'CANCELLED' | 'EXECUTION_FAILED' | 'RESULT_TOO_LARGE';

export class ExecuteCodeError extends Error {
  override readonly name = 'ExecuteCodeError';
  constructor(readonly code: ExecuteCodeErrorCode, message: string) {
    super(message);
  }
}

export interface ExecuteCodeOptions {
  device: AndroidDevice;
  timeoutMs?: number;
  memoryLimitMb?: number;
  maxResultBytes?: number;
  maxResultDepth?: number;
  signal?: AbortSignal;
}

export interface ExecuteCodeResult {
  result: unknown;
  logs: string[];
  actions: ActionResult[];
  durationMs: number;
}

const MAX_TIMEOUT_MS = 60_000;
const MAX_MEMORY_MB = 512;
const MIN_MEMORY_MB = 8;
const DEFAULT_RESULT_BYTES = 256 * 1024;
const DEFAULT_RESULT_DEPTH = 32;
const MAX_LOG_LINES = 100;
const MAX_ACTIONS = 100;
const MAX_SOURCE_BYTES = 1024 * 1024;

/** Evaluate one async expression in a fresh isolate with only a narrow Android bridge. */
export async function executeCode(source: string, options: ExecuteCodeOptions): Promise<ExecuteCodeResult> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_ISOLATE_TIMEOUT_MS;
  const memoryLimitMb = options.memoryLimitMb ?? DEFAULT_ISOLATE_MEMORY_MB;
  const maxResultBytes = options.maxResultBytes ?? DEFAULT_RESULT_BYTES;
  const maxResultDepth = options.maxResultDepth ?? DEFAULT_RESULT_DEPTH;
  if (typeof source !== 'string' || Buffer.byteLength(source, 'utf8') > MAX_SOURCE_BYTES ||
      !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS ||
      !Number.isSafeInteger(memoryLimitMb) || memoryLimitMb < MIN_MEMORY_MB || memoryLimitMb > MAX_MEMORY_MB ||
      !Number.isSafeInteger(maxResultBytes) || maxResultBytes < 1 || maxResultBytes > 4 * 1024 * 1024 ||
      !Number.isSafeInteger(maxResultDepth) || maxResultDepth < 1 || maxResultDepth > 128) {
    throw new ExecuteCodeError('INVALID_CONFIG', 'Execution limits and source must be within supported bounds');
  }
  if (options.signal?.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');

  const startedAt = performance.now();
  const cancellation = new AbortController();
  const logs: string[] = [];
  const actions: ActionResult[] = [];
  let outputBytes = 0;
  let timedOut = false;
  let cancelled = false;
  let cancelPromiseReject: ((error: ExecuteCodeError) => void) | undefined;
  const abortFromCaller = () => {
    cancelled = true;
    cancellation.abort();
    cancelPromiseReject?.(new ExecuteCodeError('CANCELLED', 'Execution was cancelled'));
  };
  options.signal?.addEventListener('abort', abortFromCaller, { once: true });

  const bridge = async (input: string): Promise<string> => {
    try {
      const command = parseBridgeCommand(input);
      if (cancellation.signal.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');
      if (command.op === 'log') {
        const line = command.args.map(stringifyLogValue).join(' ');
        const size = Buffer.byteLength(line, 'utf8');
        if (logs.length >= MAX_LOG_LINES || outputBytes + size > maxResultBytes) {
          throw new ExecuteCodeError('RESULT_TOO_LARGE', 'Execution log output exceeds its configured limit');
        }
        logs.push(line);
        outputBytes += size;
        return JSON.stringify({ ok: true, value: null });
      }
      if (isAction(command.op) && actions.length >= MAX_ACTIONS) {
        throw new ExecuteCodeError('RESULT_TOO_LARGE', 'Execution action trace exceeds its limit');
      }
      const value = await dispatchBridge(options.device, command, cancellation.signal);
      if (isAction(command.op)) {
        actions.push(value as ActionResult);
      }
      return JSON.stringify({ ok: true, value });
    } catch (error) {
      return JSON.stringify({
        ok: false,
        error: {
          code: error instanceof ExecuteCodeError ? error.code : getErrorCode(error) ?? 'EXECUTION_FAILED',
          message: safeMessage(error),
        },
      });
    }
  };

  const isolate = new ivm.Isolate({ memoryLimit: memoryLimitMb });
  const bridgeReference = new ivm.Reference(bridge);
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const context = await isolate.createContext();
    if (cancelled || options.signal?.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');
    context.global.setSync('__androidHostBridge', bridgeReference);
    const prelude = `
      (async () => {
        const __bridge = __androidHostBridge;
        delete globalThis.__androidHostBridge;
        const __call = async (op, args) => {
          const response = JSON.parse(await __bridge.apply(undefined, [JSON.stringify({op, args})], {
            arguments: {copy: true}, result: {promise: true, copy: true}
          }));
          if (!response.ok) { const error = new Error(response.error.message); error.code = response.error.code; throw error; }
          return response.value;
        };
        const __locator = (selector, options) => Object.freeze({
          __selector: selector,
          __options: options,
          count: () => __call('count', [selector, options]),
          isVisible: () => __call('isVisible', [selector, options]),
          bounds: () => __call('bounds', [selector, options]),
          state: () => __call('state', [selector, options]),
          click: () => __call('click', [selector, options]),
          longClick: () => __call('longClick', [selector, options]),
          clear: () => __call('clear', [selector, options]),
          fill: value => __call('fill', [selector, options, value]),
          scroll: direction => __call('scroll', [selector, options, direction]),
          scrollUntilVisible: (target, scrollOptions) => __call('scrollUntilVisible', [selector, options, target?.__selector, target?.__options, scrollOptions]),
        });
        globalThis.android = Object.freeze({
          snapshot: (options = {}) => __call('snapshot', [options]),
          getByText: (value, options = {}) => { const {exact, ...filters} = options; return __locator({type:'text', value, ...(exact === undefined ? {} : {exact})}, filters); },
          getByResourceId: (value, options = {}) => __locator({type:'resourceId', value}, options),
          getByDescription: (value, options = {}) => __locator({type:'description', value}, options),
          getByRole: (value, options = {}) => { const {name, ...filters} = options; return __locator({type:'role', value, ...(name === undefined ? {} : {name})}, filters); },
          getByRef: (value, revision) => __locator({type:'ref', value, revision}, {}),
          performGesture: (kind, options) => __call('performGesture', [kind, options]),
          performGlobalAction: (action, options = {}) => __call('performGlobalAction', [action, options]),
          launchApp: (packageName, options = {}) => __call('launchApp', [packageName, options]),
        });
        globalThis.console = Object.freeze({log: (...args) => __call('log', args)});
        try {
          const value = await (0, eval)(${JSON.stringify(source)});
          return { __androidRuntimeEnvelope: 'success', value };
        } catch (error) {
          return {
            __androidRuntimeEnvelope: 'error',
            code: typeof error?.code === 'string' ? error.code : 'EXECUTION_FAILED',
            message: typeof error?.message === 'string' ? error.message : String(error),
          };
        }
      })()
    `;
    const script = await isolate.compileScript(prelude, { filename: 'android-use-isolate.js' });
    if (cancelled || options.signal?.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');
    const running = script.run(context, { timeout: timeoutMs, promise: true, copy: true });
    const deadline = new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => {
        timedOut = true;
        cancellation.abort();
        reject(new ExecuteCodeError('TIMEOUT', `Execution exceeded ${timeoutMs}ms`));
      }, timeoutMs);
      timer.unref?.();
    });
    const aborted = new Promise<never>((_resolve, reject) => { cancelPromiseReject = reject; });
    let result: unknown;
    try {
      const envelope = await Promise.race([running, deadline, aborted]);
      if (typeof envelope !== 'object' || envelope === null || !('__androidRuntimeEnvelope' in envelope)) {
        throw new ExecuteCodeError('EXECUTION_FAILED', 'Isolate returned an invalid runtime envelope');
      }
      const runtimeEnvelope = envelope as { __androidRuntimeEnvelope: unknown; value?: unknown; code?: unknown; message?: unknown };
      if (runtimeEnvelope.__androidRuntimeEnvelope !== 'success') {
        const code = runtimeEnvelope.code === 'RESULT_TOO_LARGE' ? 'RESULT_TOO_LARGE' :
          runtimeEnvelope.code === 'CANCELLED' ? 'CANCELLED' : 'EXECUTION_FAILED';
        throw new ExecuteCodeError(code, typeof runtimeEnvelope.message === 'string' ? runtimeEnvelope.message : 'Execution failed');
      }
      result = runtimeEnvelope.value;
    } catch (error) {
      if (timedOut || /timed out|execution.*timeout/i.test(safeMessage(error))) {
        throw new ExecuteCodeError('TIMEOUT', `Execution exceeded ${timeoutMs}ms`);
      }
      if (cancelled || options.signal?.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');
      if (error instanceof ExecuteCodeError) throw error;
      const code = getErrorCode(error);
      if (code === 'RESULT_TOO_LARGE') throw new ExecuteCodeError(code, safeMessage(error));
      throw new ExecuteCodeError('EXECUTION_FAILED', safeMessage(error));
    }

    validateJsonValue(result, maxResultDepth);
    const resultJson = JSON.stringify(result);
    if (resultJson === undefined) throw new ExecuteCodeError('EXECUTION_FAILED', 'Result is not JSON serializable');
    const totalBytes = Buffer.byteLength(resultJson, 'utf8') +
      Buffer.byteLength(JSON.stringify(logs), 'utf8') + Buffer.byteLength(JSON.stringify(actions), 'utf8');
    if (totalBytes > maxResultBytes) {
      throw new ExecuteCodeError('RESULT_TOO_LARGE', 'Execution result, logs, and actions exceed the configured byte limit');
    }
    return { result: JSON.parse(resultJson) as unknown, logs, actions, durationMs: performance.now() - startedAt };
  } catch (error) {
    if (error instanceof ExecuteCodeError) throw error;
    if (timedOut) throw new ExecuteCodeError('TIMEOUT', `Execution exceeded ${timeoutMs}ms`);
    if (cancelled || options.signal?.aborted) throw new ExecuteCodeError('CANCELLED', 'Execution was cancelled');
    throw new ExecuteCodeError('EXECUTION_FAILED', safeMessage(error));
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    options.signal?.removeEventListener('abort', abortFromCaller);
    cancellation.abort();
    bridgeReference.release();
    isolate.dispose();
  }
}

interface BridgeCommand { op: string; args: unknown[] }

function parseBridgeCommand(input: string): BridgeCommand {
  let value: unknown;
  try { value = JSON.parse(input); } catch { throw new Error('Invalid bridge request'); }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid bridge request');
  const command = value as Record<string, unknown>;
  if (typeof command.op !== 'string' || !Array.isArray(command.args) || Object.keys(command).some((key) => key !== 'op' && key !== 'args')) {
    throw new Error('Invalid bridge request');
  }
  return { op: command.op, args: command.args };
}

async function dispatchBridge(device: AndroidDevice, command: BridgeCommand, signal: AbortSignal): Promise<unknown> {
  const [first, second, third, fourth, fifth] = command.args;
  if (command.op === 'performGesture') {
    if (first !== 'tap' && first !== 'longPress' && first !== 'swipe') throw new Error('gesture kind is invalid');
    return device.performGesture(first as GestureKind, { ...asRecord(second), signal } as never);
  }
  if (command.op === 'performGlobalAction') {
    if (!['back', 'home', 'recents', 'notifications'].includes(String(first))) throw new Error('global action is invalid');
    return device.performGlobalAction(first as GlobalAction, { ...asRecord(second), signal });
  }
  if (command.op === 'launchApp') {
    if (typeof first !== 'string') throw new Error('package name is invalid');
    return device.launchApp(first, { ...asRecord(second), signal });
  }
  if (command.op === 'snapshot') {
    const options = asRecord(first);
    const mode = options['mode'];
    if (mode !== undefined && mode !== 'full' && mode !== 'compact' && mode !== 'actionable') {
      throw new Error('Invalid snapshot mode');
    }
    return device.snapshot({ ...(mode === undefined ? {} : { mode }), signal });
  }
  if (command.op === 'log') return null;
  if (command.op === 'scrollUntilVisible') {
    const containerOptions = locatorOptions(second);
    const targetOptions = locatorOptions(fourth);
    const scrollOptions = asRecord(fifth);
    if (typeof third !== 'object' || third === null || Array.isArray(third)) throw new Error('target selector is required');
    const container = createLocator(device, selectorSchema.parse(first) as Selector, containerOptions);
    const target = createLocator(device, selectorSchema.parse(third) as Selector, targetOptions);
    const direction = scrollOptions['direction'];
    const maxSteps = scrollOptions['maxSteps'];
    if (typeof direction !== 'string' || typeof maxSteps !== 'number') throw new Error('scroll options are invalid');
    return container.scrollUntilVisible(target, {
      direction: direction as ScrollDirection,
      maxSteps,
      signal,
      ...(typeof scrollOptions['timeoutMs'] === 'number' ? { timeoutMs: scrollOptions['timeoutMs'] } : {}),
    });
  }
  const selector = selectorSchema.parse(first) as Selector;
  const options = locatorOptions(second);
  const locator = createLocator(device, selector, options);
  if (command.op === 'count') return locator.count();
  if (command.op === 'isVisible') return locator.isVisible();
  if (command.op === 'bounds') return locator.bounds();
  if (command.op === 'state') return locator.state();
  if (command.op === 'click') return locator.click({ signal });
  if (command.op === 'longClick') return locator.longClick({ signal });
  if (command.op === 'clear') return locator.clear({ signal });
  if (command.op === 'fill') {
    if (typeof third !== 'string') throw new Error('fill requires a string value');
    return locator.fill(third, { signal });
  }
  if (command.op === 'scroll') {
    if (typeof third !== 'string' || !['up', 'down', 'left', 'right', 'forward', 'backward'].includes(third)) {
      throw new Error('scroll direction is invalid');
    }
    return locator.scroll(third as ScrollDirection, { signal });
  }
  throw new Error('Unsupported Android bridge operation');
}

function createLocator(device: AndroidDevice, selector: Selector, options: LocatorOptions): Locator {
  switch (selector.type) {
    case 'text': return device.getByText(selector.value, { ...options, ...(selector.exact === undefined ? {} : { exact: selector.exact }) });
    case 'resourceId': return device.getByResourceId(selector.value, options);
    case 'description': return device.getByDescription(selector.value, options);
    case 'role': return device.getByRole(selector.value, { ...options, ...(selector.name === undefined ? {} : { name: selector.name }) });
    case 'ref': return device.getByRef(selector.value, selector.revision);
  }
}

function locatorOptions(value: unknown): LocatorOptions {
  const options = value === undefined ? {} : asRecord(value);
  const allowed = ['first', 'index', 'clickable', 'editable', 'enabled', 'visible'];
  if (Object.keys(options).some((key) => !allowed.includes(key))) throw new Error('Unsupported locator option');
  return options as LocatorOptions;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Expected an options object');
  return value as Record<string, unknown>;
}

function isAction(operation: string): boolean {
  return operation === 'click' || operation === 'longClick' || operation === 'clear' ||
    operation === 'fill' || operation === 'scroll' ||
    operation === 'performGesture' || operation === 'performGlobalAction';
}

function stringifyLogValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value) ?? String(value); } catch { return '[unserializable]'; }
}

function validateJsonValue(value: unknown, maxDepth: number): void {
  const active = new Set<object>();
  const visit = (node: unknown, depth: number): void => {
    if (depth > maxDepth) throw new ExecuteCodeError('RESULT_TOO_LARGE', 'Execution result exceeds its nesting limit');
    if (node === null || typeof node === 'string' || typeof node === 'boolean') return;
    if (typeof node === 'number') {
      if (!Number.isFinite(node)) throw new ExecuteCodeError('EXECUTION_FAILED', 'Result contains a non-finite number');
      return;
    }
    if (typeof node !== 'object') throw new ExecuteCodeError('EXECUTION_FAILED', 'Result is not JSON serializable');
    if (active.has(node)) throw new ExecuteCodeError('EXECUTION_FAILED', 'Result contains a cycle');
    active.add(node);
    for (const child of Array.isArray(node) ? node : Object.values(node)) visit(child, depth + 1);
    active.delete(node);
  };
  visit(value, 0);
}

function getErrorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' ? code : undefined;
}

function safeMessage(error: unknown): string {
  return error instanceof Error ? error.message.slice(0, 2_000) : 'Execution failed';
}
