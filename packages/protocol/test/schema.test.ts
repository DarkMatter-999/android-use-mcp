import { describe, expect, it } from 'vitest';

import {
  MAX_FRAME_BYTES,
  MAX_METHOD_LENGTH,
  parseRpcRequest,
  parseRpcResponse,
  parseSelector,
  RpcProtocolError,
  selectorSchema,
  serializeRpcMessage,
  type RpcErrorCode,
  type RpcRequest,
  type RpcResponse,
  type Selector,
} from '../src/index.js';

const REQUEST_ID = '3f8c8b1e-2e4a-4f6b-9c7d-5a1b2c3d4e5f';
const CANCEL_ID = 'c2f0a7d4-6b1e-4a83-9f52-7d8e1c0b3a69';

function captureError(run: () => unknown): RpcProtocolError {
  try {
    run();
  } catch (error) {
    if (error instanceof RpcProtocolError) {
      return error;
    }
    throw error;
  }
  throw new Error('expected a typed RpcProtocolError, but the call succeeded');
}

function expectCode(run: () => unknown, code: RpcErrorCode): RpcProtocolError {
  const error = captureError(run);
  expect(error).toBeInstanceOf(RpcProtocolError);
  expect(error.code).toBe(code);
  expect(error.message.length).toBeGreaterThan(0);
  return error;
}

function request(overrides: Partial<Record<string, unknown>> = {}): unknown {
  return {
    protocolVersion: 1,
    requestId: REQUEST_ID,
    method: 'device.status',
    params: {},
    ...overrides,
  };
}

function response(overrides: Partial<Record<string, unknown>> = {}): unknown {
  return {
    protocolVersion: 1,
    requestId: REQUEST_ID,
    result: { ok: true },
    ...overrides,
  };
}

function serializedRequest(overrides: Partial<Record<string, unknown>> = {}): string {
  return JSON.stringify(request(overrides));
}

describe('parseRpcRequest', () => {
  it('accepts a valid v1 request and returns the typed value', () => {
    const parsed: RpcRequest = parseRpcRequest(serializedRequest());

    expect(parsed.protocolVersion).toBe(1);
    expect(parsed.requestId).toBe(REQUEST_ID);
    expect(parsed.method).toBe('device.status');
    expect(parsed.params).toEqual({});
  });

  it('accepts a bounded unknown method so dispatch can answer UNSUPPORTED', () => {
    const parsed = parseRpcRequest(serializedRequest({ method: 'future.method' }));

    expect(parsed.method).toBe('future.method');
  });

  it('rejects an unknown protocol version with UNSUPPORTED_VERSION', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ protocolVersion: 2 })), 'UNSUPPORTED_VERSION');
    expectCode(() => parseRpcRequest(serializedRequest({ protocolVersion: 0 })), 'UNSUPPORTED_VERSION');
    expectCode(() => parseRpcRequest(serializedRequest({ protocolVersion: '1' })), 'UNSUPPORTED_VERSION');
  });

  it('treats a missing protocol version as a malformed message', () => {
    const { protocolVersion: _omitted, ...withoutVersion } = request() as Record<string, unknown>;

    expectCode(() => parseRpcRequest(JSON.stringify(withoutVersion)), 'INVALID_MESSAGE');
  });

  it('rejects a missing request ID with INVALID_MESSAGE', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ requestId: undefined })), 'INVALID_MESSAGE');
  });

  it('rejects a malformed request ID with INVALID_MESSAGE', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ requestId: 'not-a-uuid' })), 'INVALID_MESSAGE');
    expectCode(
      () => parseRpcRequest(serializedRequest({ requestId: '3f8c8b1e-2e4a-4f6b-9c7d' })),
      'INVALID_MESSAGE',
    );
    expectCode(() => parseRpcRequest(serializedRequest({ requestId: 42 })), 'INVALID_MESSAGE');
  });

  it('rejects method names outside the 1-128 character bounds', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ method: '' })), 'INVALID_MESSAGE');
    expectCode(
      () => parseRpcRequest(serializedRequest({ method: 'a'.repeat(MAX_METHOD_LENGTH + 1) })),
      'INVALID_MESSAGE',
    );
    expectCode(() => parseRpcRequest(serializedRequest({ method: 12 })), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest(serializedRequest({ method: ['device.status'] })), 'INVALID_MESSAGE');

    expect(parseRpcRequest(serializedRequest({ method: 'a'.repeat(MAX_METHOD_LENGTH) })).method).toHaveLength(
      MAX_METHOD_LENGTH,
    );
  });

  it('requires params to be a JSON object', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ params: [] })), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest(serializedRequest({ params: 'x' })), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest(serializedRequest({ params: null })), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest(serializedRequest({ params: undefined })), 'INVALID_MESSAGE');
  });

  it('rejects unknown envelope fields', () => {
    expectCode(() => parseRpcRequest(serializedRequest({ extra: true })), 'INVALID_MESSAGE');
  });

  it('rejects malformed JSON with INVALID_MESSAGE', () => {
    expectCode(() => parseRpcRequest('{'), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest(''), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest('"request"'), 'INVALID_MESSAGE');
    expectCode(() => parseRpcRequest('[]'), 'INVALID_MESSAGE');
  });

  it('requires rpc.cancel to name the active operation', () => {
    const cancel = { protocolVersion: 1, requestId: REQUEST_ID, method: 'rpc.cancel', params: { cancelRequestId: CANCEL_ID } };

    expect(parseRpcRequest(JSON.stringify(cancel)).params).toEqual({ cancelRequestId: CANCEL_ID });

    expectCode(
      () => parseRpcRequest(JSON.stringify({ ...cancel, params: {} })),
      'INVALID_MESSAGE',
    );
    expectCode(
      () => parseRpcRequest(JSON.stringify({ ...cancel, params: { cancelRequestId: 'active-op' } })),
      'INVALID_MESSAGE',
    );
    expectCode(
      () => parseRpcRequest(JSON.stringify({ ...cancel, params: { cancelRequestId: 7 } })),
      'INVALID_MESSAGE',
    );
    expectCode(
      () => parseRpcRequest(JSON.stringify({ ...cancel, params: {}, cancelRequestId: CANCEL_ID })),
      'INVALID_MESSAGE',
    );
  });
});

describe('frame size limits', () => {
  const prefix = `{"protocolVersion":1,"requestId":"${REQUEST_ID}","method":"device.status","params":{"pad":"`;
  const suffix = '"}}';

  function frameOfBytes(bytes: number): string {
    return `${prefix}${'a'.repeat(bytes - prefix.length - suffix.length)}${suffix}`;
  }

  it('accepts a frame of exactly 2 MiB', () => {
    const text = frameOfBytes(MAX_FRAME_BYTES);

    expect(Buffer.byteLength(text, 'utf8')).toBe(MAX_FRAME_BYTES);
    expect(parseRpcRequest(text).method).toBe('device.status');
  });

  it('rejects a valid frame of 2 MiB + 1 byte with MESSAGE_TOO_LARGE', () => {
    const text = frameOfBytes(MAX_FRAME_BYTES + 1);

    expect(Buffer.byteLength(text, 'utf8')).toBe(MAX_FRAME_BYTES + 1);
    expectCode(() => parseRpcRequest(text), 'MESSAGE_TOO_LARGE');
  });

  it('measures the cap in UTF-8 bytes before JSON parsing', () => {
    const pad = '€'.repeat(Math.ceil((MAX_FRAME_BYTES + 1 - prefix.length - suffix.length) / 3));
    const text = `${prefix}${pad}${suffix}`;

    expect(text.length).toBeLessThan(MAX_FRAME_BYTES);
    expect(Buffer.byteLength(text, 'utf8')).toBeGreaterThan(MAX_FRAME_BYTES);
    expectCode(() => parseRpcRequest(text), 'MESSAGE_TOO_LARGE');
  });

  it('checks the size before parsing malformed JSON', () => {
    expectCode(() => parseRpcRequest(`${frameOfBytes(MAX_FRAME_BYTES + 1)}x`), 'MESSAGE_TOO_LARGE');
  });
});

describe('parseRpcResponse', () => {
  it('accepts a result response', () => {
    const parsed: RpcResponse = parseRpcResponse(JSON.stringify(response()));

    expect(parsed.protocolVersion).toBe(1);
    expect(parsed.requestId).toBe(REQUEST_ID);
    expect(parsed.result).toEqual({ ok: true });
    expect(parsed.error).toBeUndefined();
  });

  it('accepts a typed error response', () => {
    const parsed = parseRpcResponse(
      JSON.stringify(
        response({
          result: undefined,
          error: { code: 'STALE_REF', message: 'revision moved', details: { revision: 9 } },
        }),
      ),
    );

    expect(parsed.error).toEqual({ code: 'STALE_REF', message: 'revision moved', details: { revision: 9 } });
  });

  it('accepts explicit nulls for optional error fields', () => {
    const nullError = parseRpcResponse(JSON.stringify(response({ error: null })));
    expect(nullError.error).toBeUndefined();

    const nullDetails = parseRpcResponse(
      JSON.stringify(
        response({
          result: undefined,
          error: { code: 'INTERNAL', message: 'no details', details: null },
        }),
      ),
    );
    expect(nullDetails.error?.details).toBeUndefined();
  });

  it('rejects unknown protocol versions', () => {
    expectCode(() => parseRpcResponse(JSON.stringify(response({ protocolVersion: 3 }))), 'UNSUPPORTED_VERSION');
  });

  it('rejects a missing or malformed request ID', () => {
    expectCode(() => parseRpcResponse(JSON.stringify(response({ requestId: undefined }))), 'INVALID_MESSAGE');
    expectCode(() => parseRpcResponse(JSON.stringify(response({ requestId: 'nope' }))), 'INVALID_MESSAGE');
  });

  it('rejects malformed error envelopes', () => {
    expectCode(
      () => parseRpcResponse(JSON.stringify(response({ result: undefined, error: { message: 'missing code' } }))),
      'INVALID_MESSAGE',
    );
    expectCode(
      () => parseRpcResponse(JSON.stringify(response({ result: undefined, error: { code: 'STALE_REF' } }))),
      'INVALID_MESSAGE',
    );
    expectCode(
      () =>
        parseRpcResponse(
          JSON.stringify(response({ result: undefined, error: { code: 'NEW_CODE', message: 'future code' } })),
        ),
      'INVALID_MESSAGE',
    );
    expectCode(
      () =>
        parseRpcResponse(
          JSON.stringify(response({ result: undefined, error: { code: 'STALE_REF', message: 'x', details: [] } })),
        ),
      'INVALID_MESSAGE',
    );
  });

  it('rejects an unknown field nested inside an error object', () => {
    expectCode(
      () =>
        parseRpcResponse(
          JSON.stringify(response({ result: undefined, error: { code: 'INTERNAL', message: 'x', extra: true } })),
        ),
      'INVALID_MESSAGE',
    );
  });

  it('preserves an explicit null result as a present JSON null', () => {
    const parsed = parseRpcResponse(JSON.stringify(response({ result: null })));

    expect('result' in parsed).toBe(true);
    expect(parsed.result).toBeNull();

    const serialized = JSON.parse(serializeRpcMessage(parsed)) as Record<string, unknown>;
    expect('result' in serialized).toBe(true);
    expect(serialized['result']).toBeNull();
  });

  it('rejects unknown envelope fields', () => {
    expectCode(() => parseRpcResponse(JSON.stringify(response({ method: 'device.status' }))), 'INVALID_MESSAGE');
  });

  it('rejects malformed JSON and oversized frames', () => {
    expectCode(() => parseRpcResponse('null'), 'INVALID_MESSAGE');
    expectCode(() => parseRpcResponse('x'.repeat(MAX_FRAME_BYTES + 1)), 'MESSAGE_TOO_LARGE');
  });
});

describe('serializeRpcMessage', () => {
  it('serializes requests and responses that parse back to the same value', () => {
    const requestValue: RpcRequest = {
      protocolVersion: 1,
      requestId: REQUEST_ID,
      method: 'device.status',
      params: {},
    };
    const responseValue: RpcResponse = {
      protocolVersion: 1,
      requestId: REQUEST_ID,
      result: { status: 'idle' },
    };

    expect(parseRpcRequest(serializeRpcMessage(requestValue))).toEqual(requestValue);
    expect(parseRpcResponse(serializeRpcMessage(responseValue))).toEqual(responseValue);
  });

  it('refuses to serialize an invalid envelope and returns typed errors', () => {
    expectCode(
      () => serializeRpcMessage({ protocolVersion: 2, requestId: REQUEST_ID, method: 'x', params: {} } as never),
      'UNSUPPORTED_VERSION',
    );
    expectCode(
      () => serializeRpcMessage({ protocolVersion: 1, requestId: 'nope', method: 'x', params: {} } as never),
      'INVALID_MESSAGE',
    );
    expectCode(
      () => serializeRpcMessage({ protocolVersion: 1, requestId: REQUEST_ID, method: 'rpc.cancel', params: {} }),
      'INVALID_MESSAGE',
    );
    expectCode(() => serializeRpcMessage(null as never), 'INVALID_MESSAGE');
  });

  it('refuses to emit a frame larger than 2 MiB', () => {
    const oversized: RpcRequest = {
      protocolVersion: 1,
      requestId: REQUEST_ID,
      method: 'device.status',
      params: { pad: 'a'.repeat(MAX_FRAME_BYTES) },
    };

    expectCode(() => serializeRpcMessage(oversized), 'MESSAGE_TOO_LARGE');
  });
});

describe('selectorSchema', () => {
  it('accepts all five selector variants', () => {
    const accepted: Selector[] = [
      { type: 'text', value: 'Save' },
      { type: 'text', value: 'Save', exact: true },
      { type: 'resourceId', value: 'com.example:id/save' },
      { type: 'description', value: 'Save changes' },
      { type: 'role', value: 'button' },
      { type: 'role', value: 'button', name: 'Save' },
      { type: 'ref', value: 'w42r7n0', revision: 7 },
    ];

    for (const selector of accepted) {
      expect(parseSelector(selector)).toEqual(selector);
    }
  });

  it('accepts an integral JSON number lexical spelling for ref revision', () => {
    // JSON allows `7.0` and `7e0`; both denote the same integral mathematical
    // value as 7. Kotlin must accept these spellings to match this schema.
    const decimal = parseSelector(JSON.parse('{"type":"ref","value":"r7-e3","revision":7.0}'));
    const exponent = parseSelector(JSON.parse('{"type":"ref","value":"r7-e3","revision":7e0}'));
    const padded = parseSelector(JSON.parse('{"type":"ref","value":"r7-e3","revision":7.000}'));

    expect(decimal).toEqual({ type: 'ref', value: 'r7-e3', revision: 7 });
    expect(exponent).toEqual({ type: 'ref', value: 'r7-e3', revision: 7 });
    expect(padded).toEqual({ type: 'ref', value: 'r7-e3', revision: 7 });

    expect(selectorSchema.safeParse(JSON.parse('{"type":"ref","value":"x","revision":1.5}')).success).toBe(false);
    expect(selectorSchema.safeParse(JSON.parse('{"type":"ref","value":"x","revision":-1.0}')).success).toBe(false);
    expect(selectorSchema.safeParse(JSON.parse('{"type":"ref","value":"x","revision":1e999}')).success).toBe(false);
  });

  it('exposes a strict schema with no cross-variant fields', () => {
    expect(selectorSchema.safeParse({ type: 'role', value: 'button', name: 'Save' }).success).toBe(true);
    expect(selectorSchema.safeParse({ type: 'role', value: 'button', name: 'Save', extra: 1 }).success).toBe(false);
    expect(selectorSchema.safeParse({ type: 'text', value: 'Save', name: 'Save' }).success).toBe(false);
    expect(selectorSchema.safeParse({ type: 'ref', value: 'w42r7n0', revision: 7, exact: true }).success).toBe(false);
  });

  it('rejects malformed selector shapes with typed INVALID_MESSAGE errors', () => {
    const rejected: unknown[] = [
      undefined,
      null,
      'text',
      42,
      [],
      {},
      { type: 'unknown', value: 'x' },
      { type: 'text' },
      { type: 'text', value: '' },
      { type: 'text', value: 'x', exact: 'yes' },
      { type: 'text', value: 'x', name: 'extra' },
      { type: 'resourceId' },
      { type: 'resourceId', value: 'x', exact: true },
      { type: 'description', value: 7 },
      { type: 'role', value: 'button', name: '' },
      { type: 'role', value: 'button', extra: true },
      { type: 'ref', value: 'x' },
      { type: 'ref', value: 'x', revision: 1.5 },
      { type: 'ref', value: 'x', revision: -1 },
      { type: 'ref', value: 'x', revision: '7' },
    ];

    for (const value of rejected) {
      const error = expectCode(() => parseSelector(value), 'INVALID_MESSAGE');
      expect(error.message.length).toBeGreaterThan(0);
    }
  });
});

