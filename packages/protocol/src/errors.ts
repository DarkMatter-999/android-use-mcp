/**
 * Typed protocol error codes shared by the Android companion and the TypeScript
 * host. The set is closed for protocol version 1: adding a code requires a
 * protocol-version change so both peers stay in lockstep.
 */
export const RPC_ERROR_CODES = [
  'INVALID_MESSAGE',
  'UNSUPPORTED_VERSION',
  'MESSAGE_TOO_LARGE',
  'UNAUTHORIZED',
  'PAIRING_REQUIRED',
  'STALE_REF',
  'AMBIGUOUS_TARGET',
  'NOT_FOUND',
  'NOT_ACTIONABLE',
  'UNSUPPORTED',
  'TIMEOUT',
  'CANCELLED',
  'INTERNAL',
] as const;

export type RpcErrorCode = (typeof RPC_ERROR_CODES)[number];

/** Maximum UTF-8 encoded size of a single RPC frame, measured before JSON parsing. */
export const MAX_FRAME_BYTES = 2 * 1024 * 1024;

/** Maximum length of an RPC method name. */
export const MAX_METHOD_LENGTH = 128;

/**
 * Stable typed failure for protocol-level problems. Parsers never leak raw
 * validator exceptions; callers can branch on `code`.
 */
export class RpcProtocolError extends Error {
  readonly code: RpcErrorCode;
  readonly details: Record<string, unknown> | undefined;

  constructor(code: RpcErrorCode, message: string, details?: Record<string, unknown>) {
    super(message);
    this.name = 'RpcProtocolError';
    this.code = code;
    this.details = details;
  }
}

export function isRpcProtocolError(value: unknown): value is RpcProtocolError {
  return value instanceof RpcProtocolError;
}
