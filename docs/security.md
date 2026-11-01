# AndroidUse security model

## Scope and trust boundaries

AndroidUse is a user-authorized Android accessibility companion and a host-side
RPC client. Android's enabled-accessibility-services setting is the authority
for accessibility consent; the application can read that state and link to
Settings, but does not write secure settings or enable the service. Accessibility
tree data and actions are sensitive. Password values are redacted and snapshots
are on demand; this build does not capture screenshots.

The network protocol uses TLS 1.3 with mandatory mutual TLS. The phone trusts a
self-signed host certificate only after the user approves the host name and
certificate fingerprint on the phone. Host software pins the phone certificate
fingerprint. TLS encryption alone is not the pairing decision: an unapproved
host is restricted to pairing/status operations. Approval and revocation are
phone-local; approval RPC methods are rejected.

## Identity and stored data

The phone's TLS private key is generated and used through Android Keystore and
is not exported. The public certificate and SHA-256 fingerprint are displayable
metadata. The host creates a separate client identity and stores its private
key in a user-private directory with restrictive permissions; symlinked,
partial, or mismatched identity files are rejected. Pairing storage contains
approved host identifiers, names, certificate fingerprints, and approval
timestamps only. It is separate from service preferences. Pairing challenges
are short-lived, one-use, rate-limited, and never persisted as credentials.

## Listener and forwarding exposure

The server supports loopback binding for local USB/ADB forwarding and explicit
binding to one concrete local Wi-Fi IPv4 address. It rejects wildcard and
multicast Wi-Fi addresses. ADB is invoked with an argument array and
`shell:false`; device serials and ports are never interpolated into a shell
command. ADB forwarding uses `--no-rebind`; a colliding rule is not taken over.
A forwarder removes only the forwarding rule it created. ADB does not
replace TLS, client identity, certificate pinning, or RPC authorization.

Wi-Fi is a separate, explicit exposure choice and should be disabled when no
longer needed. A local network peer may still reach the TLS listener, but must
present a valid client certificate and pass pairing/authorization checks.
Firewalls, compromised endpoints, malicious accessibility content, and OS
vulnerabilities remain outside the app's protection boundary.

The companion starts loopback only after the explicit in-app toggle succeeds
and the persistent notification is visible. Wi-Fi switching is a separate UI
choice, selects one IPv4 address from an active `wlan*`/`wifi*` interface, and
falls back to stopped/disabled state if rebinding fails. Service restart does
not restore a listener or Wi-Fi exposure. UI pairing codes are time-limited;
the exact pending host name and fingerprint require local approval or rejection.
These code paths have unit/build verification but have not been operationally
validated on a device as part of this task.

## RPC, revocation, and outcomes

Protocol v1 has a fixed method set. Screenshot capture is unsupported. Pairing
approval/revocation are not remotely callable. Pairing status is queried using
`device.status` with a challenge identifier. The companion currently dispatches
snapshots and element/gesture actions; `element.query` is not implemented and
returns `UNSUPPORTED`. `session.stop` commits its response, then disables the
remote-control toggle and notification, stops the listener, and closes the
controller socket, releasing its one-controller lease. The server bounds connections,
request workers, frame size, in-flight requests, and TLS/WebSocket timeouts.
Revocation invalidates the authorization and closes the affected live session;
authorization is held under the pairing lock through response commitment, so a
revocation cannot overtake an already-authorized response write.

Requests are not automatically replayed after timeout, cancellation, or
disconnect. If a connection is lost after an action may have been dispatched,
the outcome can be unknown; the user or host must inspect the device before
retrying. A timeout is not proof that an action did not happen.

## Logging and limitations

Logs must not contain private keys, pairing codes, credentials, raw snapshots,
input text, or other secret material. Errors should identify an actionable
category without exposing TLS key material or pairing secrets. Action history
is bounded and records metadata rather than entered values.

TLS/Keystore feasibility instrumentation is not proof of hardware-backed key
storage on every device. Unit tests and local test certificates are not
production credential tests. Do not infer successful phone UI lifecycle,
Wi-Fi connectivity, ADB forwarding, or real-device pairing from host tests.
Those paths require separately authorized device validation. No operational
host credentials or production private keys belong in this repository.
