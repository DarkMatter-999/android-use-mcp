# Android Use

Android Use is an Android companion app and a local Model Context Protocol (MCP) server for authenticated, user-approved device control. The MCP server runs on your computer and communicates with the companion over a TLS-protected WebSocket connection.

The phone owner enables Android's Accessibility service and Remote control, chooses whether to expose the listener on Wi-Fi, and approves each host on the phone. The MCP server cannot approve itself.

## Requirements

- Android device with the Android Use companion app installed.
- Node.js 22 or newer and pnpm 12 (the repository pins its package manager version).
- OpenCode V2 for the global configuration example below.
- A network path between the computer and phone. Wi-Fi is supported; USB/ADB forwarding can be used when the listener is on loopback.

## Build the MCP server

From the repository root:

```sh
pnpm install
pnpm --filter @android-use/mcp... build
```

The local MCP command is `packages/mcp/dist/src/cli.js`. Keep the build output in place: OpenCode will launch this file directly.

## Get the phone connection details

1. Open Android Use and enable its Accessibility service through Android Settings. This is a user-controlled Android setting; the app cannot enable it for you.
2. Enable **Remote control** in the app. Keep the app's visible notification active.
3. In **Connection and session**, copy the **Phone certificate fingerprint** and **Public certificate**. Save the PEM certificate to a persistent file on the computer, for example `~/.config/android-use/device-certificate.pem`. Do not use a temporary directory.
4. The listener uses stable port `43419` by default; you can set a custom port in **Connection and session** while remote control is off. For Wi-Fi, enable **Expose on current Wi-Fi**. To avoid entering the phone IP, use the host's `--discover` option; the phone advertises its endpoint over DNS-SD while Wi-Fi exposure is active. The computer and phone must be on the same LAN and multicast DNS/DNS-SD must be allowed. Alternatively, use the displayed IP and port in `wss://<phone-ip>:<port>/rpc`; a router DHCP reservation can keep that manual URL stable.

The fingerprint is the SHA-256 pin for the phone certificate (64 hexadecimal characters). The PEM file and fingerprint must come from the same phone identity. The public phone certificate is not a private key, but avoid placing it in a temporary location that may disappear.

### USB / ADB forwarding

The app's default listener is loopback-only. For a USB connection, forward a local computer port to the phone listener port using ADB, then use the local forwarded port in the URL:

```sh
adb forward tcp:<computer-port> tcp:<phone-listener-port>
```

For example, if you choose local port `33931` and the app reports phone listener port `33931`, use `wss://127.0.0.1:33931/rpc`. Keep the forward active while using the MCP server. ADB forwarding does not replace TLS, certificate pinning, host pairing, or phone-side approval.

## Add Android Use to OpenCode globally

OpenCode V2's user configuration on Linux is normally `~/.config/opencode/opencode.json` (or `opencode.jsonc`). If the file already exists, preserve its other settings and merge the `mcp.servers.android-use` entry into it. OpenCode's `mcp.servers` structure uses a server name beneath `servers`.

Replace every placeholder below with your actual values. This example uses discovery, so no phone IP or `--url` is required. Use absolute paths; JSON does not expand `~`. The `--identity-dir` option is a separate, persistent directory for this computer's host certificate and private key. It is created by the MCP server and must be kept private. It is not the phone certificate file.

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "android-use": {
        "type": "local",
        "command": [
          "node",
          "/absolute/path/to/android-use/packages/mcp/dist/src/cli.js",
          "--discover",
          "--device-certificate",
          "/absolute/path/to/device-certificate.pem",
          "--device-certificate-pin",
          "<64-character-phone-certificate-sha256-fingerprint>",
          "--identity-dir",
          "/absolute/path/to/private/android-use-host-identity"
        ],
        "timeout": 10000
      }
    }
  }
}
```

The identity directory should be dedicated to this host identity. Do not manually put certificate files in it, copy it to another computer, or share its private key. The MCP CLI's supported option is `--identity-dir` (not `--host-identity-dir`). If you omit it, the CLI uses `~/.config/android-use-mcp`.

After saving the config, run:

```sh
opencode mcp list
```

The MCP process starts locally. It connects to the phone lazily when a device tool is first used, so listing or initializing tools alone does not verify phone connectivity.

## Pair and approve the host

1. In OpenCode, call `android_pair` and provide the pairing code shown by Android Use. If needed, first use `android_status` to inspect status.
2. Check the pending host name and certificate fingerprint shown in the phone app.
3. Approve the host on the phone only if you recognize it and the details match the computer you intend to authorize.
4. After approval, use `android_status` to confirm the authenticated connection.

Pairing codes are short-lived. Host approval and revocation are phone-side actions; deleting the computer's identity files does **not** revoke an already approved host. Revoke it in the app.

## Discover and launch allowed apps

Use `android_list_apps` to see launcher-visible apps the phone owner has already selected for remote launch. Each entry includes its display name and package ID. `android_open_app` requests launch through the same phone-side selection; the host cannot modify that allowlist. The host MCP server and phone companion must both use protocol v2 builds.

## Troubleshooting

- **MCP server fails to start:** confirm Node.js 22+, run the build command again, and check that the CLI and phone PEM paths are absolute and readable.
- **Invalid fingerprint:** copy the complete SHA-256 fingerprint from the phone and ensure it matches the PEM certificate you saved.
- **Discovery finds no phone or multiple phones:** confirm Wi-Fi exposure is enabled, host and phone share the same LAN, multicast DNS/DNS-SD is not filtered, and only one Android Use companion is advertising. For manual URL mode, confirm the current phone IP/port and `wss://.../rpc` endpoint.
- **TLS or connection error:** confirm the listener is enabled, the phone certificate PEM and SHA-256 pin match, both devices can reach each other, and any manual URL ends in `/rpc` and uses `wss://`.
- **USB connection fails:** check `adb devices` and that the `adb forward` rule is active for the listener's current port.
- **Pairing required:** call `android_pair`, then approve the matching pending host on the phone before attempting other device operations.
- **Host identity error:** use a dedicated `--identity-dir` directory. Do not add the phone certificate or unrelated files to the host identity directory.

## Security notes

- Accessibility access is sensitive. Enable it only if you understand and want the phone-side capabilities.
- Pairing is explicit and user-mediated. Wi-Fi exposure is a separate opt-in choice; disable it when it is no longer needed.
- Keep the host identity directory private. It contains the host private key that identifies this computer.
- Do not put private keys, pairing codes, or operational credentials in this repository or in shared configuration.
- The MCP runtime is bounded, but device actions can have real effects. Review the target and verify outcomes on the phone.

## Further reading

- [MCP server tools, configuration, and runtime details](packages/mcp/README.md)
- [Security model](docs/security.md)
- [Protocol overview](docs/protocol.md)
- [OpenCode MCP server documentation](https://opencode.ai/v2/docs/mcp-servers)
