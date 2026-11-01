import { readFileSync, readdirSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import {
  MAX_FRAME_BYTES,
  parseRpcRequest,
  parseRpcResponse,
  parseSelector,
  RpcProtocolError,
  serializeRpcMessage,
  type RpcErrorCode,
  type RpcRequest,
  type RpcResponse,
} from '../src/index.js';

type FixtureKind = 'request' | 'response';

const fixturesUrl = new URL('../fixtures/', import.meta.url);
const FIXTURE_NAMES = [
  'action-error.json',
  'action-unverified.json',
  'element-action-role.json',
  'snapshot-response.json',
  'status-request.json',
];

type JsonObject = Record<string, unknown>;

function load(name: string): string {
  return readFileSync(new URL(name, fixturesUrl), 'utf8');
}

function parseJsonObject(text: string): JsonObject {
  const value: unknown = JSON.parse(text);
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('fixture root must be a JSON object');
  }
  return value as JsonObject;
}

function captureCode(run: () => unknown): RpcErrorCode {
  try {
    run();
  } catch (error) {
    if (error instanceof RpcProtocolError) {
      return error.code;
    }
    throw error;
  }
  throw new Error('expected a typed RpcProtocolError');
}

function fixtureKind(value: JsonObject): FixtureKind {
  return typeof value.method === 'string' ? 'request' : 'response';
}

function accept(kind: FixtureKind, text: string): RpcRequest | RpcResponse {
  return kind === 'request' ? parseRpcRequest(text) : parseRpcResponse(text);
}

/**
 * The same mutation names and expected codes are mirrored by the Kotlin
 * `RpcProtocolTest` so both implementations accept or reject the shared
 * fixtures identically.
 */
const MUTATIONS: ReadonlyArray<{ name: string; code: RpcErrorCode; apply: (value: JsonObject) => void }> = [
  {
    name: 'unknown-version',
    code: 'UNSUPPORTED_VERSION',
    apply: (value) => {
      value['protocolVersion'] = 2;
    },
  },
  {
    name: 'missing-request-id',
    code: 'INVALID_MESSAGE',
    apply: (value) => {
      delete value['requestId'];
    },
  },
  {
    name: 'malformed-request-id',
    code: 'INVALID_MESSAGE',
    apply: (value) => {
      value['requestId'] = 'not-a-uuid';
    },
  },
  {
    name: 'extra-envelope-field',
    code: 'INVALID_MESSAGE',
    apply: (value) => {
      value['extra'] = true;
    },
  },
  {
    name: 'unknown-error-code',
    code: 'INVALID_MESSAGE',
    apply: (value) => {
      value['error'] = { code: 'NEW_CODE', message: 'from a future version' };
    },
  },
];

function oversizedMutation(value: JsonObject, kind: FixtureKind): number {
  const pad = 'a'.repeat(MAX_FRAME_BYTES);
  if (kind === 'request') {
    (value['params'] as JsonObject)['pad'] = pad;
  } else {
    value['result'] = { pad };
  }
  return Buffer.byteLength(JSON.stringify(value), 'utf8');
}

describe('shared protocol fixtures', () => {
  it('contains exactly the three documented v1 fixtures', () => {
    expect(readdirSync(fixturesUrl).sort()).toEqual(FIXTURE_NAMES);
  });

  for (const name of FIXTURE_NAMES) {
    describe(name, () => {
      const raw = load(name);
      const value = parseJsonObject(raw);
      const kind = fixtureKind(value);

      it('is a v1 frame below the 2 MiB UTF-8 cap', () => {
        expect(value['protocolVersion']).toBe(1);
        expect(Buffer.byteLength(raw, 'utf8')).toBeLessThan(MAX_FRAME_BYTES);
      });

      it('is accepted by the matching parser', () => {
        const parsed = accept(kind, raw);

        expect(parsed.protocolVersion).toBe(1);
        expect(parsed.requestId).toBe(value['requestId']);
      });

      it('round-trips through serializeRpcMessage without changing its shape', () => {
        const parsed = accept(kind, raw);

        expect(JSON.parse(serializeRpcMessage(parsed))).toEqual(value);
      });

      for (const mutation of MUTATIONS) {
        it(`rejects ${mutation.name} with ${mutation.code}`, () => {
          const mutated = parseJsonObject(raw);
          mutation.apply(mutated);

          expect(captureCode(() => accept(kind, JSON.stringify(mutated)))).toBe(mutation.code);
        });
      }

      it('rejects a 2 MiB + 1 UTF-8 byte mutation with MESSAGE_TOO_LARGE', () => {
        const mutated = parseJsonObject(raw);

        expect(oversizedMutation(mutated, kind)).toBeGreaterThan(MAX_FRAME_BYTES);
        expect(captureCode(() => accept(kind, JSON.stringify(mutated)))).toBe('MESSAGE_TOO_LARGE');
      });

      it('rejects malformed JSON with INVALID_MESSAGE', () => {
        expect(captureCode(() => accept(kind, `${raw.trimEnd()} trailing`))).toBe('INVALID_MESSAGE');
      });
    });
  }
});

describe('action-unverified.json', () => {
  it('keeps the required verified field as a present JSON null', () => {
    const raw = load('action-unverified.json');
    const result = parseJsonObject(raw)['result'] as JsonObject;

    expect('verified' in result).toBe(true);
    expect(result['verified']).toBeNull();

    const serialized = JSON.parse(serializeRpcMessage(parseRpcResponse(raw))) as JsonObject;
    expect(serialized['result']).toEqual(result);
    expect('verified' in (serialized['result'] as JsonObject)).toBe(true);
  });
});

describe('element-action-role.json', () => {
  it('carries a role selector accepted by the shared selector schema', () => {
    const value = parseJsonObject(load('element-action-role.json'));

    expect(value['method']).toBe('element.action');
    const params = value['params'] as JsonObject;
    expect(parseSelector(params['selector'])).toEqual({
      type: 'role',
      value: 'button',
      name: 'Save',
    });
  });
});

describe('request-only fixture mutations', () => {
  const raw = load('status-request.json');

  const cases: ReadonlyArray<{ name: string; apply: (value: JsonObject) => void }> = [
    {
      name: 'empty-method',
      apply: (value) => {
        value['method'] = '';
      },
    },
    {
      name: '129-character-method',
      apply: (value) => {
        value['method'] = 'a'.repeat(129);
      },
    },
    {
      name: 'array-params',
      apply: (value) => {
        value['params'] = [];
      },
    },
    {
      name: 'missing-params',
      apply: (value) => {
        delete value['params'];
      },
    },
    {
      name: 'cancel-without-cancel-request-id',
      apply: (value) => {
        value['method'] = 'rpc.cancel';
        value['params'] = {};
      },
    },
  ];

  for (const testCase of cases) {
    it(`rejects ${testCase.name} with INVALID_MESSAGE`, () => {
      const value = parseJsonObject(raw);
      testCase.apply(value);

      expect(captureCode(() => parseRpcRequest(JSON.stringify(value)))).toBe('INVALID_MESSAGE');
    });
  }
});

describe('response-only fixture mutations', () => {
  const raw = load('action-error.json');

  const cases: ReadonlyArray<{ name: string; apply: (value: JsonObject) => void }> = [
    {
      name: 'error-without-code',
      apply: (value) => {
        value['error'] = { message: 'missing code' };
      },
    },
    {
      name: 'error-without-message',
      apply: (value) => {
        value['error'] = { code: 'INTERNAL' };
      },
    },
    {
      name: 'array-error-details',
      apply: (value) => {
        value['error'] = { code: 'INTERNAL', message: 'x', details: [] };
      },
    },
  ];

  for (const testCase of cases) {
    it(`rejects ${testCase.name} with INVALID_MESSAGE`, () => {
      const value = parseJsonObject(raw);
      testCase.apply(value);

      expect(captureCode(() => parseRpcResponse(JSON.stringify(value)))).toBe('INVALID_MESSAGE');
    });
  }
});
