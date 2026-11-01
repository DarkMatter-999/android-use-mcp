# RPC Protocol (version 1)

This document freezes the wire contract shared by the Android companion
(`apps/companion`, Kotlin) and the TypeScript host (`packages/protocol`). It is
the source of truth for Tasks 3–9. Both implementations parse the shared JSON
fixtures in `packages/protocol/fixtures/` and apply the same acceptance rules.

The contract covers the envelope, typed errors, method names, and snapshot/action
DTOs. Transport framing, authentication, pairing and operation dispatch are
implemented by the companion and host packages.

## Frame limits

- A frame is one UTF-8 encoded JSON text containing a single JSON object.
- The maximum frame size is **2 MiB (2,097,152 bytes)**, measured in UTF-8 bytes
  **before** JSON parsing. A frame of exactly 2 MiB is accepted; 2 MiB + 1 byte
  is rejected with `MESSAGE_TOO_LARGE`. Because the limit is byte-based, a frame
  whose character count is below the limit can still be rejected when it
  contains multi-byte characters.
- Size is checked before JSON syntax, so an oversized malformed frame is
  reported as `MESSAGE_TOO_LARGE`, not `INVALID_MESSAGE`.
- A transport that receives bytes must cap buffering before materializing a
  string; the parser cap alone is not a network-level defense.

## Request envelope

```json
{
  "protocolVersion": 1,
  "requestId": "3f8c8b1e-2e4a-4f6b-9c7d-5a1b2c3d4e5f",
  "method": "device.status",
  "params": {}
}
```

| Field | Type | Rules |
|---|---|---|
| `protocolVersion` | number | Required; must be exactly `1`. Missing → `INVALID_MESSAGE`; any other value (including strings/null) → `UNSUPPORTED_VERSION`. |
| `requestId` | string | Required; canonical RFC 4122 text UUID (`8-4-4-4-12` hex, case-insensitive). Client-generated per request. |
| `method` | string | Required; 1–128 characters. Unknown but well-formed names are accepted by the envelope parser; dispatch answers `UNSUPPORTED`. |
| `params` | object | Required; any JSON object. Arrays, scalars, and `null` are rejected. |

Unknown top-level fields are rejected. Params contents are method-specific and
validated by the method handler.

## Response envelope

```json
{
  "protocolVersion": 1,
  "requestId": "c2f0a7d4-6b1e-4a83-9f52-7d8e1c0b3a69",
  "result": {}
}
```

```json
{
  "protocolVersion": 1,
  "requestId": "8e7d6c5b-4a39-4281-9e0f-1a2b3c4d5e6f",
  "error": {
    "code": "STALE_REF",
    "message": "Snapshot revision 7 is no longer current",
    "details": { "ref": "w42r7n0", "revision": 7, "currentRevision": 9 }
  }
}
```

| Field | Type | Rules |
|---|---|---|
| `protocolVersion` | number | Same rules as requests. |
| `requestId` | string | Required; echoes the request UUID. |
| `result` | any JSON value | Optional. Shape is method-specific (for example `UiSnapshot`). |
| `error` | object | Optional; required fields `code` (frozen code) and `message` (string); optional `details` (JSON object). |

Unknown top-level fields and unknown fields nested inside `error` are rejected.
A response is expected to carry exactly one of `result` or `error`; v1 parsers
validate each present field but do not reject both-present or both-absent
responses. Dispatch owns that semantic choice. An explicitly `null` optional
`error` or `error.details` is treated as absent (JSON producers commonly emit
nulls). `result: null` is a present JSON null: it is accepted as a
method-specific result value, and both implementations preserve it as a present
key when re-serializing (Kotlin uses a custom response serializer so an absent
`result` and an explicit `result: null` stay distinguishable).

### `rpc.cancel`

`rpc.cancel` is a normal v1 method request: it has a **new** `requestId` (the
cancel request itself must be correlatable) and names the operation it cancels:

```json
{
  "protocolVersion": 1,
  "requestId": "5ad5f4f0-8b60-4c6e-9d42-4f6c1a7e2b30",
  "method": "rpc.cancel",
  "params": { "cancelRequestId": "3f8c8b1e-2e4a-4f6b-9c7d-5a1b2c3d4e5f" }
}
```

`params.cancelRequestId` is required and must be a canonical UUID. A missing,
non-string, or malformed value is `INVALID_MESSAGE`; placing
`cancelRequestId` outside `params` is also rejected. The cancelled operation's
response uses `CANCELLED`. Cancellation of an already-finished operation is
idempotent and must not replay the operation.

### Frozen method names

`device.status`, `pairing.begin`, `pairing.approve`, `pairing.revoke`,
`session.start`, `session.stop`, `snapshot.get`, `element.query`,
`element.action`, `gesture.perform`, `screenshot.capture`, `rpc.cancel`.

A new method must ship with a schema, a permission rule, a timeout, and typed
errors. Unknown methods receive `UNSUPPORTED`; unsupported capabilities do not
fall back to a weaker behavior.

## Typed error codes (closed set for v1)

| Code | Meaning |
|---|---|
| `INVALID_MESSAGE` | Malformed JSON object, missing/typed-wrong field, unknown field, bad UUID, bad method name, bad cancel params, unknown error code. |
| `UNSUPPORTED_VERSION` | Envelope `protocolVersion` is not the supported version. |
| `MESSAGE_TOO_LARGE` | Frame exceeds 2 MiB UTF-8 bytes (checked before parsing). |
| `UNAUTHORIZED` | Peer identity or credential is not accepted. |
| `PAIRING_REQUIRED` | No approved pairing exists for this host. |
| `STALE_REF` | Snapshot ref belongs to an older revision/window; no action was taken. |
| `AMBIGUOUS_TARGET` | Selector matches more than one element; caller must narrow it. |
| `NOT_FOUND` | Selector matched nothing. |
| `NOT_ACTIONABLE` | Target exists but does not support the requested action. |
| `UNSUPPORTED` | Method/action/capability is not supported on this device or build. |
| `TIMEOUT` | Operation exceeded its deadline. |
| `CANCELLED` | Operation was cancelled through `rpc.cancel` or local cancellation. |
| `INTERNAL` | Unexpected companion failure; details must stay non-sensitive. |

The set is closed for protocol version 1. The TypeScript parser rejects an
unknown `error.code` as `INVALID_MESSAGE`, and the Kotlin enum decoder does the
same. Adding a code is a protocol change and must update both peers, the
fixtures, and this document.

## Snapshot DTOs

`UiSnapshot` is the `result` of `snapshot.get`:

| Field | Type | Notes |
|---|---|---|
| `deviceId` | string | Companion/device identity. |
| `windowId` | string | Window the snapshot describes. |
| `revision` | number | Monotonic snapshot revision; refs are scoped to it. |
| `packageName` | string? | Optional foreground package. |
| `windowTitle` | string? | Optional window title. |
| `width`, `height` | number | Window size in pixels. |
| `elements` | `UiElement[]` | Bounded, redacted accessibility tree. |
| `compactText` | string? | Optional compact actionable text. |
| `truncated` | boolean | True when depth/node caps were hit. |

`UiElement`:

| Field | Type | Notes |
|---|---|---|
| `ref` | string | Revision-scoped reference, never a durable node handle. |
| `className`, `role`, `text`, `contentDescription`, `resourceId` | string? | Optional node metadata. Password/secret text must be redacted before serialization. |
| `bounds` | `{ left, top, right, bottom }` | Integers in screen pixels. |
| `clickable`, `scrollable`, `editable`, `enabled`, `focused`, `visible` | boolean | Accessibility flags. |
| `checked` | boolean? | Optional tri-state. |
| `supportedActions` | string[] | Actions the companion may attempt on this node. |
| `children` | `UiElement[]?` | Optional nested nodes. |

## Action result DTO

`ActionResult` is the `result` of `element.action` and related methods:

| Field | Type | Notes |
|---|---|---|
| `actionId` | string | Companion-generated action identifier. |
| `action` | string | Requested action name. |
| `accepted` | boolean | Whether dispatch accepted the action. Not proof of success. |
| `verified` | boolean \| null | Required key on the wire; `null` unless a separate postcondition was evaluated. Kotlin must emit an explicit `"verified": null` when unverified rather than omitting the key. |
| `verification` | `{ passed, message? }`? | Postcondition outcome when supplied. |
| `target` | `{ ref?, text?, bounds?: [l, t, r, b] }`? | Safe target description; never secret input text. |
| `revisionBefore`, `revisionAfter` | number | Snapshot revisions around the attempt. |
| `error` | `{ code, message }`? | Typed failure when the action was not accepted or failed. |

Rejected-but-valid actions return `accepted: false` with a typed error; an
accepted action is not reported as verified task success.

## Selector union

Selectors are resolved against a live snapshot at action time; they are never
durable `AccessibilityNodeInfo` handles. Method params such as
`element.action.params.selector` use this strict, closed union:

| `type` | Required fields | Optional fields | Meaning |
|---|---|---|---|
| `text` | `value: string` | `exact?: boolean` | Match node text; `exact: false` permits partial matching. |
| `resourceId` | `value: string` | — | Match the full resource ID. |
| `description` | `value: string` | — | Match content description. |
| `role` | `value: string` | `name?: string` | Match role/class, optionally narrowed by accessible name. |
| `ref` | `value: string`, `revision: integer` | — | Revision-scoped snapshot reference. |

All variants reject unknown or cross-variant fields (for example `name` on a
`text` selector or `exact` on a `ref` selector). `value` and `role.name` must be
non-empty strings, `exact` must be a boolean, and `revision` must be a
non-negative JSON number whose mathematical value is integral and exactly
representable as an integer (`7`, `7.0`, and `7e0` are accepted; `7.5`, `-1`,
and the string `"7"` are rejected). Malformed selectors produce
`INVALID_MESSAGE`. The TypeScript `selectorSchema` and Kotlin `Selector` sealed
interface share these exact variants.

```json
{ "type": "role", "value": "button", "name": "Save" }
```

## Parser and serializer APIs

TypeScript (`packages/protocol`):

```ts
parseRpcRequest(text: string): RpcRequest      // throws RpcProtocolError
parseRpcResponse(text: string): RpcResponse    // throws RpcProtocolError
parseSelector(value: unknown): Selector       // throws RpcProtocolError
selectorSchema: z.ZodType<Selector>
serializeRpcMessage(message: RpcRequest | RpcResponse): string
measureFrameBytes(text: string): number
```

`RpcProtocolError` exposes `code`, `message`, and optional `details`; raw Zod
errors are never surfaced. `serializeRpcMessage` validates the envelope with the
same schema, refuses to emit a frame over the cap, and throws the same typed
errors.

Kotlin (`lakshyajeet.androiduse.app.network.RpcProtocol`):

```kotlin
RpcProtocol.parseRequest(text: String): RpcParseResult<RpcRequest>
RpcProtocol.parseResponse(text: String): RpcParseResult<RpcResponse>
RpcProtocol.serialize(request: RpcRequest): String       // throws RpcProtocolException
RpcProtocol.serialize(response: RpcResponse): String     // throws RpcProtocolException
```

`RpcParseResult.Success` carries the typed value; `RpcParseResult.Failure`
carries `RpcProtocolError(code, message, details)`. `RpcProtocolException`
wraps the same error for invalid locally constructed messages. The Kotlin and
TypeScript rules are intentionally identical: strict unknown-field rejection,
canonical UUIDs, 1–128 method characters, object-only params, version check
before shape validation, and UTF-8 byte size check before parsing.

Selectors use `lakshyajeet.androiduse.app.selectors.SelectorCodec`:

```kotlin
SelectorCodec.parse(text: String): RpcParseResult<Selector>
SelectorCodec.parse(element: JsonElement): RpcParseResult<Selector>
SelectorCodec.serialize(selector: Selector): String   // throws RpcProtocolException
```

Kotlin pre-validates the raw JSON shape because `kotlinx.serialization` would
otherwise coerce a quoted `"revision": "7"` into an `Int`; the TypeScript and
Kotlin validators therefore accept and reject the same selector values.

## Shared fixtures and cross-language parity

| Fixture | Kind | Purpose |
|---|---|---|
| `packages/protocol/fixtures/status-request.json` | request | `device.status` with an empty params object. |
| `packages/protocol/fixtures/snapshot-response.json` | response | `snapshot.get` result carrying a `UiSnapshot`. |
| `packages/protocol/fixtures/action-error.json` | response | `STALE_REF` error with details. |
| `packages/protocol/fixtures/action-unverified.json` | response | `element.action` result with the required `verified: null` key present. |
| `packages/protocol/fixtures/element-action-role.json` | request | `element.action` using the `role` selector variant. |

Both test suites must accept every fixture, round-trip it to an equivalent JSON
shape, and reject these mutations with the listed codes:

| Mutation | Expected code |
|---|---|
| `unknown-version` | `UNSUPPORTED_VERSION` |
| `missing-request-id` | `INVALID_MESSAGE` |
| `malformed-request-id` | `INVALID_MESSAGE` |
| `extra-envelope-field` | `INVALID_MESSAGE` |
| `unknown-error-code` | `INVALID_MESSAGE` |
| unknown field nested inside `error` | `INVALID_MESSAGE` |
| oversized frame (2 MiB + 1 UTF-8 byte) | `MESSAGE_TOO_LARGE` |
| malformed JSON | `INVALID_MESSAGE` |

Request-only mutations (`empty-method`, `129-character-method`,
`array-params`, `missing-params`, `cancel-without-cancel-request-id`) and
response-only mutations (missing error code/message, non-object error details)
also return `INVALID_MESSAGE`. Selector accept/reject cases are exercised over
the five variants in both suites, including empty `value`/`name`, cross-variant
fields, non-boolean `exact`, and quoted, negative, or fractional `revision`.

The Kotlin test locates the fixtures by walking up from its working directory to
the repository root, so the fixtures stay single-sourced rather than copied into
Android test resources.

## Versioning rules

- `protocolVersion` is `1` for this contract.
- A breaking change (field rename/removal, changed meaning, new required field,
  or a new error code) requires a version bump and coordinated companion/host
  updates.
- Additive, optional response data may be introduced only if old peers can
  ignore it; because envelopes are strict, optional envelope fields must still
  be agreed before use.
- A version the peer does not implement is rejected with `UNSUPPORTED_VERSION`
  before any method dispatch, so a mismatched host cannot trigger device work.

## Security boundary

- Every request is authenticated and paired by the transport before dispatch
  (Task 5); this parser does not authenticate anything by itself.
- Unauthorized or unpaired peers receive `UNAUTHORIZED` or `PAIRING_REQUIRED`
  and no action is dispatched.
- Snapshot and action DTOs must never contain password values, credentials, or
  unredacted secure-window content; companions must return `UNSUPPORTED` for
  protected captures rather than degrade the security boundary.
- Details in errors are diagnostic only and must not echo user input text,
  credentials, or file contents.
