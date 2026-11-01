import type { ActionResult, Selector, UiBounds, UiElement } from '@android-use/protocol';
import { RemoteRpcError, type RpcConnection } from '@android-use/transport';
import { validateLocatorOptions, type LocatorOptions } from './selectors.js';

interface QueryResult {
  count: number;
  revision: number;
  elements?: UiElement[];
  element?: UiElement;
}

export interface LocatorActionOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
}

export type ScrollDirection = 'up' | 'down' | 'left' | 'right' | 'forward' | 'backward';

export class Locator {
  constructor(
    private readonly connection: RpcConnection,
    private readonly selector: Selector,
    private readonly options: LocatorOptions = {},
  ) {
    validateLocatorOptions(options);
  }

  async count(): Promise<number> {
    return (await this.query()).count;
  }

  async isVisible(): Promise<boolean> {
    const result = await this.query(this.selection());
    if (result.count === 0) return false;
    return this.selectElement(result).visible;
  }

  async bounds(): Promise<UiBounds> {
    return (await this.resolveElement()).element.bounds;
  }

  async state(): Promise<UiElement> {
    return (await this.resolveElement()).element;
  }

  click(options: LocatorActionOptions = {}): Promise<ActionResult> {
    return this.act('click', {}, options);
  }

  fill(value: string, options: LocatorActionOptions = {}): Promise<ActionResult> {
    if (typeof value !== 'string') throw new TypeError('fill value must be a string');
    return this.act('fill', { value }, options);
  }

  scroll(direction: ScrollDirection, options: LocatorActionOptions = {}): Promise<ActionResult> {
    if (!['up', 'down', 'left', 'right', 'forward', 'backward'].includes(direction)) {
      throw new TypeError('scroll direction is invalid');
    }
    return this.act('scroll', { direction }, options);
  }

  private async act(
    action: 'click' | 'fill' | 'scroll',
    payload: Record<string, unknown>,
    options: LocatorActionOptions,
  ): Promise<ActionResult> {
    const selected = await this.resolveElement();
    const params = {
      action,
      selector: { type: 'ref', value: selected.element.ref, revision: selected.revision },
      expectedRevision: selected.revision,
      ...payload,
    };
    const timeoutMs = options.timeoutMs ?? this.options.timeoutMs;
    const signal = options.signal ?? this.options.signal;
    return this.connection.request<ActionResult>('element.action', params, requestOptions({
      ...(timeoutMs === undefined ? {} : { timeoutMs }),
      ...(signal === undefined ? {} : { signal }),
    }));
  }

  private async resolveElement(): Promise<{ element: UiElement; revision: number }> {
    const result = await this.query(this.selection());
    if (result.count === 0) throw new RemoteRpcError('NOT_FOUND', 'No element matched the locator');
    if (this.options.first !== true && this.options.index === undefined && result.count > 1) {
      throw new RemoteRpcError('AMBIGUOUS_TARGET', 'More than one element matched the locator');
    }
    return { element: this.selectElement(result), revision: result.revision };
  }

  private selectElement(result: QueryResult): UiElement {
    const element = result.element ?? result.elements?.[this.options.index ?? 0];
    if (!element) throw new RemoteRpcError('NOT_FOUND', 'The selected element was not returned by the device');
    return element;
  }

  private selection(): Pick<LocatorOptions, 'first' | 'index'> {
    return {
      ...(this.options.first === undefined ? {} : { first: this.options.first }),
      ...(this.options.index === undefined ? {} : { index: this.options.index }),
    };
  }

  private async query(selection: Pick<LocatorOptions, 'first' | 'index'> = {}): Promise<QueryResult> {
    const params: Record<string, unknown> = { selector: this.selector };
    for (const key of ['clickable', 'editable', 'enabled', 'visible', 'first', 'index'] as const) {
      const value = key === 'first' || key === 'index'
        ? (Object.hasOwn(selection, key) ? selection[key] : this.options[key])
        : this.options[key];
      if (value !== undefined) params[key] = value;
    }
    const raw = await this.connection.request<unknown>('element.query', params, requestOptions(this.options));
    return validateQueryResult(raw);
  }
}

function requestOptions(options: LocatorOptions | LocatorActionOptions): { timeoutMs?: number; signal?: AbortSignal } | undefined {
  const timeoutMs = options.timeoutMs;
  if (timeoutMs !== undefined && (!Number.isFinite(timeoutMs) || timeoutMs <= 0)) {
    throw new RangeError('timeoutMs must be a positive finite number');
  }
  const signal = options.signal;
  if (timeoutMs === undefined && signal === undefined) return undefined;
  return { ...(timeoutMs === undefined ? {} : { timeoutMs }), ...(signal === undefined ? {} : { signal }) };
}

function validateQueryResult(value: unknown): QueryResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new RemoteRpcError('INVALID_MESSAGE', 'The device returned an invalid query result');
  }
  const result = value as Partial<QueryResult>;
  if (!Number.isSafeInteger(result.count) || (result.count ?? -1) < 0 ||
      !Number.isSafeInteger(result.revision) || (result.revision ?? -1) < 0 ||
      (result.elements !== undefined && !Array.isArray(result.elements))) {
    throw new RemoteRpcError('INVALID_MESSAGE', 'The device returned invalid query count, revision, or elements');
  }
  return result as QueryResult;
}
