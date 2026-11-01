import type { Selector, UiSnapshot } from '@android-use/protocol';
import type { RpcConnection } from '@android-use/transport';
import { Locator } from './locator.js';
import {
  descriptionSelector,
  refSelector,
  resourceIdSelector,
  roleSelector,
  textSelector,
  validateLocatorOptions,
  type LocatorOptions,
  type RoleLocatorOptions,
  type TextLocatorOptions,
} from './selectors.js';

export interface DeviceStatus {
  deviceFingerprintSha256: string;
  activeHost: string;
  sessionActive: boolean;
  listenerPort: number;
}

export interface SnapshotOptions {
  mode?: 'full' | 'compact' | 'actionable';
  timeoutMs?: number;
  signal?: AbortSignal;
}

export class AndroidDevice {
  constructor(private readonly connection: RpcConnection) {}

  status(options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'> = {}): Promise<DeviceStatus> {
    return this.connection.request<DeviceStatus>('device.status', {}, requestOptions(options));
  }

  snapshot(options: SnapshotOptions = {}): Promise<UiSnapshot> {
    return this.connection.request<UiSnapshot>('snapshot.get', { mode: options.mode ?? 'full' }, requestOptions(options));
  }

  getByText(value: string, options: TextLocatorOptions = {}): Locator {
    const selector = textSelector(value, options.exact);
    return this.locator(selector, options);
  }

  getByResourceId(value: string, options: LocatorOptions = {}): Locator {
    return this.locator(resourceIdSelector(value), options);
  }

  getByDescription(value: string, options: LocatorOptions = {}): Locator {
    return this.locator(descriptionSelector(value), options);
  }

  getByRole(role: string, options: RoleLocatorOptions = {}): Locator {
    return this.locator(roleSelector(role, options.name), options);
  }

  getByRef(ref: string, revision: number): Locator {
    return this.locator(refSelector(ref, revision), {});
  }

  private locator(selector: Selector, options: LocatorOptions): Locator {
    validateLocatorOptions(options);
    return new Locator(this.connection, selector, options);
  }
}

function requestOptions(options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'>): { timeoutMs?: number; signal?: AbortSignal } | undefined {
  if (options.timeoutMs !== undefined && (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0)) {
    throw new RangeError('timeoutMs must be a positive finite number');
  }
  if (options.timeoutMs === undefined && options.signal === undefined) return undefined;
  return {
    ...(options.timeoutMs === undefined ? {} : { timeoutMs: options.timeoutMs }),
    ...(options.signal === undefined ? {} : { signal: options.signal }),
  };
}
