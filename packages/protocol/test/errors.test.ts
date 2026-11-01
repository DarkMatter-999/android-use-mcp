import { describe, expect, it } from 'vitest';

import {
  isRpcErrorCode,
  isRpcProtocolError,
  MAX_FRAME_BYTES,
  MAX_METHOD_LENGTH,
  measureFrameBytes,
  RPC_ERROR_CODES,
  RPC_METHODS,
  RpcProtocolError,
} from '../src/index.js';

describe('protocol constants and error codes', () => {
  it('freezes the v1 error codes in their documented order', () => {
    expect(RPC_ERROR_CODES).toEqual([
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
    ]);
    expect(new Set(RPC_ERROR_CODES).size).toBe(RPC_ERROR_CODES.length);
  });

  it('freezes the frame and method limits', () => {
    expect(MAX_FRAME_BYTES).toBe(2 * 1024 * 1024);
    expect(MAX_METHOD_LENGTH).toBe(128);
  });

  it('freezes the v1 method list and measures frames in UTF-8 bytes', () => {
    expect(RPC_METHODS).toEqual([
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
      'rpc.cancel',
    ]);
    expect(measureFrameBytes('€')).toBe(3);
    expect(measureFrameBytes('abc')).toBe(3);
  });

  it('narrows error codes to the frozen set', () => {
    expect(isRpcErrorCode('STALE_REF')).toBe(true);
    expect(isRpcErrorCode('NEW_CODE')).toBe(false);
    expect(isRpcErrorCode(7)).toBe(false);
  });

  it('exposes a typed protocol error instead of raw validator internals', () => {
    const error = new RpcProtocolError('STALE_REF', 'revision moved', { revision: 9 });

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('RpcProtocolError');
    expect(error.code).toBe('STALE_REF');
    expect(error.message).toBe('revision moved');
    expect(error.details).toEqual({ revision: 9 });
    expect(isRpcProtocolError(error)).toBe(true);
    expect(isRpcProtocolError(new Error('plain'))).toBe(false);
  });
});
