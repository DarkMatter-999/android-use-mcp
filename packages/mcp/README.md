# Android Use MCP

Local stdio MCP server for the Android Use companion app. It adapts the authenticated Task 5 RPC transport and Task 6 TypeScript SDK/runtime into five tools:

- `android_status` — read current device/session status.
- `android_pair` — submit a pairing code and wait for approval on the phone.
- `android_snapshot` — read a bounded accessibility snapshot (`full`, `compact`, or `actionable`).
- `android_execute` — execute bounded JavaScript in an isolated V8 runtime; multiple SDK operations can be batched into one call.
- `android_disconnect` — close the current RPC connection.

Screenshot capture, app launch, and other later POC tools are intentionally not included in this Task 7 package surface.

## Requirements

- Node.js `>=22`.
- pnpm from the repository root.
- A running Android Use companion with its TLS listener enabled.
- The phone's device certificate PEM and its SHA-256 fingerprint.

## Build

From the repository root:

```sh
pnpm install
pnpm --filter @android-use/mcp... build
```

The build emits Node-loadable ESM under `packages/*/dist`. The CLI entry point is `packages/mcp/dist/src/cli.js`.

## Connection settings

The CLI requires a secure WebSocket URL, device certificate path, and certificate pin. Values can be supplied as flags or environment variables; flags take precedence.

| Flag | Environment variable | Meaning |
| --- | --- | --- |
| `--url` | `ANDROID_USE_URL` | `wss://host:port/rpc` endpoint |
| `--device-certificate` | `ANDROID_USE_DEVICE_CERTIFICATE` | PEM certificate used to authenticate/pin the phone |
| `--device-certificate-pin` | `ANDROID_USE_DEVICE_CERTIFICATE_PIN` | SHA-256 certificate fingerprint (64 hex characters; colons are accepted) |
| `--identity-dir` | `ANDROID_USE_IDENTITY_DIR` | Optional host identity directory; defaults to `~/.config/android-use-mcp` |

The host identity helper creates a client certificate and private key in a restricted local directory. Keep this directory private. To revoke a host, use the phone-side pairing controls; deleting the local identity does not revoke a certificate already approved by the phone.

## Claude Code and OpenCode

Copy and adjust one of the examples:

- [`examples/mcp-config/claude-code.json`](../../examples/mcp-config/claude-code.json)
- [`examples/mcp-config/opencode.json`](../../examples/mcp-config/opencode.json)

Replace the phone address, certificate path, and certificate pin with the values for your device. Do not put the host private key in MCP configuration. The first pairing request must be approved on the Android device.

## Batched JavaScript

`android_execute` accepts JavaScript source up to 100,000 characters and a timeout from 50 to 60,000 ms (default 1,000 ms). For example, a single execution can read a snapshot, lazily locate a button, and click it:

```ts
const code = `(async () => {
  const snapshot = await android.snapshot();
  const action = await android.getByText('Continue', { exact: true }).click();
  return { revision: snapshot.revision, action };
})()`;

await mcpClient.callTool({
  name: 'android_execute',
  arguments: { code, timeoutMs: 1_000 },
});
```

See [`examples/basic-automation/index.ts`](../../examples/basic-automation/index.ts) for a reusable helper.

Each execution uses a fresh isolate with bounded time, heap, result bytes/depth, logs, and action trace. Node globals, filesystem, child processes, and unrestricted network are not exposed. A successful action dispatch is not the same as verifying the requested task postcondition; inspect the returned action result and take a fresh snapshot when needed.

## Security and limitations

- The CLI uses TLS and an exact device-certificate SHA-256 pin; pairing approval is performed on the phone.
- The local host identity directory contains a private key and is created with restrictive permissions by the transport helper. Do not share or sync it publicly.
- Tool errors are intentionally concise and omit raw transport exceptions and credentials.
- JavaScript runs on the host, not on Android; Android operations go through the narrow SDK bridge and authenticated RPC.
- The server connects lazily when a device tool is called. MCP initialization and tool listing do not contact the phone.
- Screenshot/image support is deferred to Task 8 and is not exposed by this build.
- Secure/protected screens, OEM accessibility behavior, and the availability of an active phone-side session may limit snapshots or actions.
