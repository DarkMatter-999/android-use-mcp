import type { RpcErrorCode } from '@android-use/protocol';

export class TransportError extends Error {
  readonly outcomeUnknown: boolean;

  constructor(message: string, options: { cause?: unknown; outcomeUnknown?: boolean } = {}) {
    super(message, { cause: options.cause });
    this.name = 'TransportError';
    this.outcomeUnknown = options.outcomeUnknown ?? false;
  }
}

export class RemoteRpcError extends Error {
  readonly code: RpcErrorCode;
  readonly details: Record<string, unknown> | undefined;

  constructor(code: RpcErrorCode, message: string, details?: Record<string, unknown>) {
    super(message);
    this.name = 'RemoteRpcError';
    this.code = code;
    this.details = details;
  }
}

export class RequestTimeoutError extends TransportError {
  readonly requestId: string;

  constructor(requestId: string, outcomeUnknown: boolean) {
    super(`RPC request ${requestId} timed out`, { outcomeUnknown });
    this.name = 'RequestTimeoutError';
    this.requestId = requestId;
  }
}
