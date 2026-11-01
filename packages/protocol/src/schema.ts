import { z } from 'zod';

import { MAX_FRAME_BYTES, MAX_METHOD_LENGTH, RpcProtocolError, RPC_ERROR_CODES } from './errors.js';
import {
  PROTOCOL_VERSION,
  RPC_CANCEL_METHOD,
  type RpcErrorBody,
  type RpcRequest,
  type RpcResponse,
  type Selector,
} from './types.js';

/**
 * Canonical RFC 4122 textual UUID: 8-4-4-4-12 hexadecimal groups. The same
 * pattern is used by the Kotlin implementation so both peers agree on IDs.
 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const requestIdSchema = z.string().regex(UUID_PATTERN, 'must be a canonical UUID');
const paramsSchema = z.record(z.string(), z.unknown());

export const rpcErrorSchema: z.ZodType<RpcErrorBody> = z
  .object({
    code: z.enum(RPC_ERROR_CODES),
    message: z.string(),
    details: paramsSchema.nullish(),
  })
  .strict()
  .transform((value) => ({
    code: value.code,
    message: value.message,
    // JSON producers commonly emit explicit nulls for absent optional fields.
    details: value.details ?? undefined,
  }));

export const rpcRequestSchema: z.ZodType<RpcRequest> = z
  .object({
    protocolVersion: z.literal(PROTOCOL_VERSION),
    requestId: requestIdSchema,
    method: z.string().min(1).max(MAX_METHOD_LENGTH),
    params: paramsSchema,
  })
  .strict()
  .superRefine((value, ctx) => {
    if (value.method !== RPC_CANCEL_METHOD) {
      return;
    }
    const cancelRequestId: unknown = value.params['cancelRequestId'];
    if (typeof cancelRequestId !== 'string' || !UUID_PATTERN.test(cancelRequestId)) {
      ctx.addIssue({
        code: 'custom',
        path: ['params', 'cancelRequestId'],
        message: `${RPC_CANCEL_METHOD} requires a canonical UUID params.cancelRequestId`,
      });
    }
  });

export const rpcResponseSchema: z.ZodType<RpcResponse> = z
  .object({
    protocolVersion: z.literal(PROTOCOL_VERSION),
    requestId: requestIdSchema,
    result: z.unknown().optional(),
    error: rpcErrorSchema.nullish(),
  })
  .strict()
  .transform((value) => ({
    protocolVersion: value.protocolVersion,
    requestId: value.requestId,
    result: value.result,
    // An explicit null optional error means "no error", matching Kotlin.
    error: value.error ?? undefined,
  }));

const selectorValueSchema = z.string().min(1);

/**
 * Strict selector union. Cross-variant fields are rejected (for example `name`
 * on a text selector); method handlers must not silently ignore them.
 */
export const selectorSchema: z.ZodType<Selector> = z.discriminatedUnion('type', [
  z
    .object({
      type: z.literal('text'),
      value: selectorValueSchema,
      exact: z.boolean().optional(),
    })
    .strict(),
  z
    .object({
      type: z.literal('resourceId'),
      value: selectorValueSchema,
    })
    .strict(),
  z
    .object({
      type: z.literal('description'),
      value: selectorValueSchema,
    })
    .strict(),
  z
    .object({
      type: z.literal('role'),
      value: selectorValueSchema,
      name: selectorValueSchema.optional(),
    })
    .strict(),
  z
    .object({
      type: z.literal('ref'),
      value: selectorValueSchema,
      revision: z.number().int().min(0),
    })
    .strict(),
]);

const utf8Encoder = new TextEncoder();

export function measureFrameBytes(text: string): number {
  return utf8Encoder.encode(text).byteLength;
}

function invalidMessage(message: string, details?: Record<string, unknown>): RpcProtocolError {
  return new RpcProtocolError('INVALID_MESSAGE', message, details);
}

function ensureSupportedVersion(value: Record<string, unknown>): void {
  if (!Object.hasOwn(value, 'protocolVersion')) {
    throw invalidMessage('protocolVersion is required');
  }
  if (value['protocolVersion'] !== PROTOCOL_VERSION) {
    throw new RpcProtocolError(
      'UNSUPPORTED_VERSION',
      `unsupported protocolVersion ${String(value['protocolVersion'])}; expected ${PROTOCOL_VERSION}`,
      { protocolVersion: value['protocolVersion'], expected: PROTOCOL_VERSION },
    );
  }
}

/**
 * Enforce the frame cap on UTF-8 bytes before any JSON parsing, then decode a
 * JSON object and reject unsupported protocol versions in a stable order.
 */
function decodeFrame(text: string): Record<string, unknown> {
  if (typeof text !== 'string') {
    throw invalidMessage('RPC frame must be a string');
  }

  const bytes = measureFrameBytes(text);
  if (bytes > MAX_FRAME_BYTES) {
    throw new RpcProtocolError(
      'MESSAGE_TOO_LARGE',
      `RPC frame is ${bytes} UTF-8 bytes; the maximum is ${MAX_FRAME_BYTES}`,
      { bytes, maxBytes: MAX_FRAME_BYTES },
    );
  }

  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw invalidMessage('RPC frame is not valid JSON');
  }

  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidMessage('RPC frame must be a JSON object');
  }

  const record = value as Record<string, unknown>;
  ensureSupportedVersion(record);
  return record;
}

function toProtocolError(error: z.ZodError): RpcProtocolError {
  const issues = error.issues.map((issue) => {
    const path = issue.path.length > 0 ? issue.path.join('.') : '<root>';
    return `${path}: ${issue.message}`;
  });
  return invalidMessage(`invalid RPC message: ${issues[0] ?? 'validation failed'}`, { issues });
}

export function parseRpcRequest(text: string): RpcRequest {
  const value = decodeFrame(text);
  const parsed = rpcRequestSchema.safeParse(value);
  if (!parsed.success) {
    throw toProtocolError(parsed.error);
  }
  return parsed.data;
}

export function parseRpcResponse(text: string): RpcResponse {
  const value = decodeFrame(text);
  const parsed = rpcResponseSchema.safeParse(value);
  if (!parsed.success) {
    throw toProtocolError(parsed.error);
  }
  return parsed.data;
}

/**
 * Validate a selector value (already-decoded JSON, not a frame) with the shared
 * union schema and surface the same typed error shape as envelope parsing.
 */
export function parseSelector(value: unknown): Selector {
  const parsed = selectorSchema.safeParse(value);
  if (!parsed.success) {
    throw toProtocolError(parsed.error);
  }
  return parsed.data;
}

/**
 * Serialize an already-typed envelope after validating it against the same
 * contract used for parsing, and refuse to emit a frame over the size cap.
 */
export function serializeRpcMessage(message: RpcRequest | RpcResponse): string {
  if (typeof message !== 'object' || message === null || Array.isArray(message)) {
    throw invalidMessage('RPC message must be an object');
  }

  const record = message as unknown as Record<string, unknown>;
  ensureSupportedVersion(record);

  const parsed = 'method' in record ? rpcRequestSchema.safeParse(record) : rpcResponseSchema.safeParse(record);
  if (!parsed.success) {
    throw toProtocolError(parsed.error);
  }

  let text: string;
  try {
    text = JSON.stringify(parsed.data);
  } catch {
    throw invalidMessage('RPC message is not JSON-serializable');
  }

  const bytes = measureFrameBytes(text);
  if (bytes > MAX_FRAME_BYTES) {
    throw new RpcProtocolError(
      'MESSAGE_TOO_LARGE',
      `RPC frame is ${bytes} UTF-8 bytes; the maximum is ${MAX_FRAME_BYTES}`,
      { bytes, maxBytes: MAX_FRAME_BYTES },
    );
  }

  return text;
}

/** Narrow an error body to the frozen v1 code set. */
export function isRpcErrorCode(value: unknown): value is RpcErrorBody['code'] {
  return typeof value === 'string' && (RPC_ERROR_CODES as readonly string[]).includes(value);
}
