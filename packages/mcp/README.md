# Android Use MCP

Local stdio MCP server for the Android Use companion app. It adapts the authenticated RPC transport and TypeScript SDK/runtime into nine tools:

- `android_status` — read current device/session status.
- `android_pair` — submit a pairing code and wait for approval on the phone.
- `android_snapshot` — read a bounded accessibility snapshot (`full`, `compact`, or `actionable`).
- `android_screenshot` — capture a bounded PNG/JPEG image via Android's accessibility screenshot API; returns image content and separate metadata.
- `android_open_app` — request launch of an app selected in the phone-side allowlist.
- `android_list_apps` — list launcher-visible apps already selected for remote launch, with display names and package IDs.
- `android_gesture` — perform a bounded `tap`, `longPress`, or `swipe`, or one supported global action (`back`, `home`, `recents`, `notifications`).
- `android_execute` — execute bounded JavaScript in an isolated V8 runtime; multiple SDK operations can be batched into one call.
- `android_disconnect` — close the current RPC connection.

## Requirements

- Node.js `>=22`.
- pnpm from the repository root.
- A running Android Use companion with its TLS listener enabled.
- Matching protocol v2 host and companion builds.
- The phone's device certificate PEM and its SHA-256 fingerprint.

## Build

From the repository root:

```sh
pnpm install
pnpm --filter @android-use/mcp... build
```

The build emits Node-loadable ESM under `packages/*/dist`. The CLI entry point is `packages/mcp/dist/src/cli.js`.

## Connection settings

The CLI requires exactly one endpoint mode (`--url` or `--discover`), plus the phone certificate path and pin. Values can be supplied as flags or environment variables; flags take precedence.

| Flag | Environment variable | Meaning |
| --- | --- | --- |
| `--url` | `ANDROID_USE_URL` | `wss://host:port/rpc` endpoint |
| `--discover` | `ANDROID_USE_DISCOVERY=true` | Discover exactly one Android Use companion via DNS-SD on the local Wi-Fi |
| `--device-certificate` | `ANDROID_USE_DEVICE_CERTIFICATE` | PEM certificate used to authenticate/pin the phone |
| `--device-certificate-pin` | `ANDROID_USE_DEVICE_CERTIFICATE_PIN` | SHA-256 certificate fingerprint (64 hex characters; colons are accepted) |
| `--identity-dir` | `ANDROID_USE_IDENTITY_DIR` | Optional host identity directory; defaults to `~/.config/android-use-mcp` |

The companion listener defaults to stable port `43419`; its phone-side **Connection and session** settings allow a custom port. Enable **Expose on current Wi-Fi** on the phone for discovery. The MCP host and phone must share a network that permits multicast DNS/DNS-SD; guest networks, VPNs, and some access points block it. Discovery reports an error if no companion or multiple companions are found. For manually configured URL mode, use the endpoint shown in Android Use; a router DHCP reservation can keep its IP stable.

For discovery mode, omit `--url` and pass `--discover` together with the same certificate, pin, and identity options:

```sh
node packages/mcp/dist/src/cli.js \
  --discover \
  --device-certificate /absolute/path/to/device-certificate.pem \
  --device-certificate-pin <64-character-sha256-pin>
```

The host identity helper creates a client certificate and private key in a restricted local directory. Keep this directory private. To revoke a host, use the phone-side pairing controls; deleting the local identity does not revoke a certificate already approved by the phone.

## Claude Code and OpenCode

Copy and adjust one of the examples:

- [`examples/mcp-config/claude-code.json`](../../examples/mcp-config/claude-code.json)
- [`examples/mcp-config/opencode.json`](../../examples/mcp-config/opencode.json)

Choose either `--discover` (no phone IP in configuration) or `--url` with the current `wss://<phone-ip>:<port>/rpc`; do not configure both. Replace the certificate path and pin with values for your device. Do not put the host private key in MCP configuration. The first pairing request must be approved on the Android device.

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

The isolated SDK also supports `longClick()`, `clear()`, bounded `scrollUntilVisible(target, {direction, maxSteps})` (maximum 20 steps), `android.performGesture(...)`, `android.performGlobalAction(...)`, `android.listApps()`, and `android.launchApp(...)`. Each operation is independently bounded and re-resolves selectors before acting. Actions are dispatch outcomes, not proof of task completion.

## Security and limitations

- The CLI uses TLS and an exact device-certificate SHA-256 pin; pairing approval is performed on the phone.
- The local host identity directory contains a private key and is created with restrictive permissions by the transport helper. Do not share or sync it publicly.
- Tool errors are intentionally concise and omit raw transport exceptions and credentials.
- JavaScript runs on the host, not on Android; Android operations go through the narrow SDK bridge and authenticated RPC.
- The server connects lazily when a device tool is called. MCP initialization and tool listing do not contact the phone.
- Screenshot capture requires Android API 30+, the phone accessibility screenshot capability, an approved authenticated host, and an unprotected screen. Secure/protected content is rejected rather than replaced by a blank image; encoded image bytes are capped so the RPC frame remains below 2 MiB.
- Wi-Fi listener exposure is a separate, explicit phone-side choice. The listener defaults to loopback/USB forwarding; pairing, TLS, certificate pinning, and phone approval remain required.
- App listing includes only launcher-visible packages selected in the phone's user-managed launch allowlist. App launch authorization is decided by that same phone-side allowlist; the host SDK only validates package syntax. Package availability and OS launch rejection are surfaced as safe typed errors. The host cannot change the phone's selection.
- Secure/protected screens, OEM accessibility behavior, and the availability of an active phone-side session may limit snapshots or actions.
