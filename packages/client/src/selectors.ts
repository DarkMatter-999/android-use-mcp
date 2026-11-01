import type { Selector } from '@android-use/protocol';

export interface ElementFilters {
  clickable?: boolean;
  editable?: boolean;
  enabled?: boolean;
  visible?: boolean;
}

export interface LocatorOptions extends ElementFilters {
  first?: boolean;
  index?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
}

export interface TextLocatorOptions extends LocatorOptions {
  exact?: boolean;
}

export interface RoleLocatorOptions extends LocatorOptions {
  name?: string;
}

export function validateLocatorOptions(options: LocatorOptions): void {
  if (options.first !== undefined && typeof options.first !== 'boolean') {
    throw new TypeError('first must be a boolean');
  }
  if (options.first === true && options.index !== undefined) {
    throw new TypeError('first and index are mutually exclusive');
  }
  if (options.index !== undefined && (!Number.isSafeInteger(options.index) || options.index < 0)) {
    throw new RangeError('index must be a non-negative safe integer');
  }
  if (options.timeoutMs !== undefined && (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0)) {
    throw new RangeError('timeoutMs must be a positive finite number');
  }
  for (const key of ['clickable', 'editable', 'enabled', 'visible'] as const) {
    if (options[key] !== undefined && typeof options[key] !== 'boolean') {
      throw new TypeError(`${key} must be a boolean`);
    }
  }
}

export function textSelector(value: string, exact?: boolean): Selector {
  nonEmpty(value, 'text');
  return { type: 'text', value, ...(exact === undefined ? {} : { exact }) };
}

export function resourceIdSelector(value: string): Selector {
  nonEmpty(value, 'resource ID');
  return { type: 'resourceId', value };
}

export function descriptionSelector(value: string): Selector {
  nonEmpty(value, 'description');
  return { type: 'description', value };
}

export function roleSelector(role: string, name?: string): Selector {
  nonEmpty(role, 'role');
  if (name !== undefined) nonEmpty(name, 'role name');
  return { type: 'role', value: role, ...(name === undefined ? {} : { name }) };
}

export function refSelector(ref: string, revision: number): Selector {
  nonEmpty(ref, 'ref');
  if (!Number.isSafeInteger(revision) || revision < 0) throw new RangeError('revision must be a non-negative safe integer');
  return { type: 'ref', value: ref, revision };
}

function nonEmpty(value: string, label: string): void {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${label} must be a non-empty string`);
}
