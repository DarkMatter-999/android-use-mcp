import type { RpcErrorCode } from './errors.js';

/**
 * Wire protocol version shared by the Android companion and the TypeScript host.
 * Every RPC envelope carries this value; it is bumped only for breaking changes.
 */
export const PROTOCOL_VERSION = 1;

/** Method that cancels an in-flight operation; its params name the cancelled request. */
export const RPC_CANCEL_METHOD = 'rpc.cancel';

/** Method names frozen for protocol version 1. */
export const RPC_METHODS = [
  'device.status',
  'pairing.begin',
  'pairing.approve',
  'pairing.revoke',
  'session.start',
  'session.stop',
  'snapshot.get',
  'element.query',
  'element.action',
  'gesture.perform',
  'screenshot.capture',
  'app.launch',
  RPC_CANCEL_METHOD,
] as const;

export type RpcMethod = (typeof RPC_METHODS)[number];

/** Selector variants frozen for protocol version 1. */
export type SelectorType = 'text' | 'resourceId' | 'description' | 'role' | 'ref';

export interface TextSelector {
  type: 'text';
  value: string;
  exact?: boolean | undefined;
}

export interface ResourceIdSelector {
  type: 'resourceId';
  value: string;
}

export interface DescriptionSelector {
  type: 'description';
  value: string;
}

export interface RoleSelector {
  type: 'role';
  value: string;
  name?: string | undefined;
}

export interface RefSelector {
  type: 'ref';
  value: string;
  revision: number;
}

/**
 * Wire selector union shared by the TypeScript SDK and the Android resolver.
 * A selector is resolved against a live snapshot at action time; it is never a
 * durable node handle.
 */
export type Selector = TextSelector | ResourceIdSelector | DescriptionSelector | RoleSelector | RefSelector;


/**
 * A bounded method name is accepted at the envelope layer even when it is not a
 * known v1 method; dispatch answers with the `UNSUPPORTED` error code.
 */
export interface RpcRequest {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  method: string;
  params: Record<string, unknown>;
}

export interface RpcErrorBody {
  code: RpcErrorCode;
  message: string;
  details?: Record<string, unknown> | undefined;
}

export interface RpcResponse {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  result?: unknown;
  error?: RpcErrorBody | undefined;
}

export interface UiBounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** One accessibility node in a revision-scoped snapshot. */
export interface UiElement {
  ref: string;
  className?: string | undefined;
  role?: string | undefined;
  text?: string | undefined;
  contentDescription?: string | undefined;
  resourceId?: string | undefined;
  bounds: UiBounds;
  clickable: boolean;
  scrollable: boolean;
  editable: boolean;
  enabled: boolean;
  focused: boolean;
  visible: boolean;
  checked?: boolean | undefined;
  supportedActions: string[];
  children?: UiElement[] | undefined;
}

/** Snapshot of one window, scoped to `deviceId`/`windowId`/`revision`. */
export interface UiSnapshot {
  deviceId: string;
  windowId: string;
  revision: number;
  packageName?: string | undefined;
  windowTitle?: string | undefined;
  width: number;
  height: number;
  elements: UiElement[];
  compactText?: string | undefined;
  truncated: boolean;
}

export interface ActionResultVerification {
  passed: boolean;
  message?: string | undefined;
}

export interface ActionResultTarget {
  ref?: string | undefined;
  text?: string | undefined;
  bounds?: [number, number, number, number] | undefined;
}

export interface ActionResultError {
  code: RpcErrorCode;
  message: string;
}

/**
 * Outcome of one action. `verified` is `null` unless a separate postcondition
 * was evaluated; a dispatched action is not proof of task success.
 */
export interface ActionResult {
  actionId: string;
  action: string;
  accepted: boolean;
  verified: boolean | null;
  verification?: ActionResultVerification | undefined;
  target?: ActionResultTarget | undefined;
  revisionBefore: number;
  revisionAfter: number;
  error?: ActionResultError | undefined;
}
