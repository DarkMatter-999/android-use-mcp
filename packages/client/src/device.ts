import type { Selector, UiSnapshot } from '@android-use/protocol';
import { RemoteRpcError, type RpcConnection } from '@android-use/transport';
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

export interface DeviceScreenshot {
  bytes: Uint8Array;
  mimeType: 'image/png' | 'image/jpeg';
  width: number;
  height: number;
  timestampMillis: number;
  deviceId: string;
  windowId: string;
}

export type GlobalAction = 'back' | 'home' | 'recents' | 'notifications';
export type GestureKind = 'tap' | 'longPress' | 'swipe';
export interface GestureOptions {
  x: number;
  y: number;
  endX?: number;
  endY?: number;
  durationMillis?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
}

/** Phone-side launch policy is authoritative; this client mirror fails closed early. */
export const ANDROID_APP_LAUNCH_ALLOWLIST = ['com.android.settings', 'darkmatter.androiduse.app'] as const;

export class AndroidDevice {
  constructor(private readonly connection: RpcConnection) {}

  status(options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'> = {}): Promise<DeviceStatus> {
    return this.connection.request<DeviceStatus>('device.status', {}, requestOptions(options));
  }

  snapshot(options: SnapshotOptions = {}): Promise<UiSnapshot> {
    return this.connection.request<UiSnapshot>('snapshot.get', { mode: options.mode ?? 'full' }, requestOptions(options));
  }

  async screenshot(options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'> = {}): Promise<DeviceScreenshot> {
    const response = await this.connection.request<unknown>('screenshot.capture', {}, requestOptions(options));
    return decodeScreenshot(response);
  }

  async performGesture(kind: GestureKind, gesture: GestureOptions): Promise<import('@android-use/protocol').ActionResult> {
    validateGesture(kind, gesture);
    const params = {
      actionId: actionId(), action: kind, x: gesture.x, y: gesture.y,
      ...(kind === 'swipe' ? { endX: gesture.endX, endY: gesture.endY } : {}),
      durationMillis: gesture.durationMillis ?? (kind === 'tap' ? 80 : 350),
      timeoutMillis: gesture.timeoutMs ?? 5_000,
    };
    return this.connection.request('gesture.perform', params, requestOptions(gesture));
  }

  performGlobalAction(action: GlobalAction, options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'> = {}): Promise<import('@android-use/protocol').ActionResult> {
    if (!['back', 'home', 'recents', 'notifications'].includes(action)) throw new TypeError('global action is invalid');
    return this.connection.request('element.action', { actionId: actionId(), action }, requestOptions(options));
  }

  launchApp(packageName: string, options: Pick<SnapshotOptions, 'timeoutMs' | 'signal'> = {}): Promise<{ launched: true; packageName: string }> {
    if (typeof packageName !== 'string' || !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(packageName)) {
      throw new TypeError('package name is invalid');
    }
    if (!(ANDROID_APP_LAUNCH_ALLOWLIST as readonly string[]).includes(packageName)) {
      throw new RemoteRpcError('UNAUTHORIZED', 'App package is not allowlisted on the phone', { reason: 'NOT_ALLOWLISTED' });
    }
    return this.connection.request('app.launch', { packageName }, requestOptions(options));
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

function validateGesture(kind: GestureKind, gesture: GestureOptions): void {
  if (!['tap', 'longPress', 'swipe'].includes(kind)) throw new TypeError('gesture kind is invalid');
  const coordinate = (value: number) => Number.isInteger(value) && value >= 0 && value <= 100_000;
  if (!coordinate(gesture.x) || !coordinate(gesture.y) ||
      (kind === 'swipe' && (!coordinate(gesture.endX ?? -1) || !coordinate(gesture.endY ?? -1)))) {
    throw new RangeError('gesture coordinate is outside the supported range');
  }
  if (kind !== 'swipe' && (gesture.endX !== undefined || gesture.endY !== undefined)) throw new TypeError('end coordinates are only valid for swipe');
  const duration = gesture.durationMillis ?? (kind === 'tap' ? 80 : 350);
  if (!Number.isInteger(duration) || duration < 1 || duration > 10_000) throw new RangeError('gesture duration is outside the supported range');
  if (gesture.timeoutMs !== undefined && (!Number.isFinite(gesture.timeoutMs) || gesture.timeoutMs <= 0 || gesture.timeoutMs > 30_000)) {
    throw new RangeError('timeoutMs must be a positive finite value no greater than 30000');
  }
}

function actionId(): string {
  if (!globalThis.crypto?.randomUUID) throw new Error('secure UUID generation is unavailable');
  return globalThis.crypto.randomUUID();
}

const MAX_SCREENSHOT_BYTES = 1_400_000;

function decodeScreenshot(value: unknown): DeviceScreenshot {
  const fail = (): never => { throw new TypeError('Invalid screenshot response'); };
  if (typeof value !== 'object' || value === null) return fail();
  const record = value as Record<string, unknown>;
  if (typeof record['imageBase64'] !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(record['imageBase64'])) return fail();
  if (record['mimeType'] !== 'image/png' && record['mimeType'] !== 'image/jpeg') return fail();
  if (!positiveInteger(record['width']) || !positiveInteger(record['height']) || !positiveInteger(record['timestampMillis'])) return fail();
  if (typeof record['deviceId'] !== 'string' || !record['deviceId'] || typeof record['windowId'] !== 'string' || !record['windowId']) return fail();
  let decoded: string;
  try { decoded = atob(record['imageBase64']); } catch { return fail(); }
  if (decoded.length === 0 || decoded.length > MAX_SCREENSHOT_BYTES) return fail();
  const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  const validPng = record['mimeType'] === 'image/png' && bytes.length >= 8 &&
    bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 &&
    bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a;
  const validJpeg = record['mimeType'] === 'image/jpeg' && bytes.length >= 3 &&
    bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (!validPng && !validJpeg) return fail();
  return {
    bytes,
    mimeType: record['mimeType'],
    width: record['width'],
    height: record['height'],
    timestampMillis: record['timestampMillis'],
    deviceId: record['deviceId'],
    windowId: record['windowId'],
  };
}

function positiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
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
