import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { connect as connectTcp } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { WebSocket } from 'ws';
import { RpcConnection, loadOrCreateHostIdentity } from '../src/index.js';
import { DROP_CONNECTION, NO_RESPONSE, startTestTlsServer } from './helpers/tls-server.js';

const directories: string[] = [];
const servers: Array<{ close(): Promise<void> }> = [];

afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => server.close()));
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

async function identity() {
  const directory = await mkdtemp(join(tmpdir(), 'android-use-connection-'));
  directories.push(directory);
  return loadOrCreateHostIdentity(join(directory, 'host'));
}

async function server(options?: Parameters<typeof startTestTlsServer>[0]) {
  const instance = await startTestTlsServer(options);
  servers.push(instance);
  return instance;
}

describe('RpcConnection', () => {
  it('connects with host mTLS identity and exact pinned device certificate', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    try {
      const result = await connection.request<{ method: string; params: object }>('device.status', { check: true });
      expect(result).toEqual({ method: 'device.status', params: { check: true } });
      expect(device.received).toHaveLength(1);
    } finally {
      connection.close();
    }
  });

  it('rejects a wrong pin and untrusted device certificate before RPC data is sent', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem });
    await expect(RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: '0'.repeat(64),
    })).rejects.toThrow();
    expect(device.received).toHaveLength(0);
  });

  it('requires secure URL and valid pin configuration before connecting', async () => {
    const host = await identity();
    await expect(RpcConnection.connect({
      url: 'ws://127.0.0.1:1/rpc',
      hostIdentity: host,
      deviceCertificatePem: 'not a certificate',
      deviceFingerprintSha256: 'bad',
    })).rejects.toThrow();
  });

  it('correlates concurrent requests and preserves typed remote RPC errors', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    try {
      const [one, two] = await Promise.all([
        connection.request<{ params: { n: number } }>('test.one', { n: 1 }),
        connection.request<{ params: { n: number } }>('test.two', { n: 2 }),
      ]);
      expect(one.params.n).toBe(1);
      expect(two.params.n).toBe(2);
      await expect(connection.request('test.error', {})).rejects.toMatchObject({ code: 'UNSUPPORTED' });
    } finally {
      connection.close();
    }
  });

  it('rejects missing client certificate and rejects new requests after close', async () => {
    const host = await identity();
    const device = await server({ requireClientCertificate: true, clientCertificatePem: host.certificatePem });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    connection.close();
    await expect(connection.request('device.status', {})).rejects.toThrow();
  });

  it('times out, sends rpc.cancel, and marks dispatched actions outcome-unknown', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, respond: () => NO_RESPONSE });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    try {
      await expect(connection.request('element.action', {}, {
        requestId: '00000000-0000-4000-8000-000000000123',
        timeoutMs: 30,
      })).rejects.toMatchObject({ name: 'RequestTimeoutError', outcomeUnknown: true });
      await waitFor(() => device.received.some((request) => request.method === 'rpc.cancel'));
      expect(device.received.find((request) => request.method === 'rpc.cancel')?.params)
        .toEqual({ cancelRequestId: '00000000-0000-4000-8000-000000000123' });
    } finally {
      connection.close();
    }
  });

  it('aborts an in-flight request and sends its cancellation request id', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, respond: () => NO_RESPONSE });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    const controller = new AbortController();
    try {
      const request = connection.request('snapshot.get', {}, {
        requestId: '00000000-0000-4000-8000-000000000124',
        signal: controller.signal,
      });
      await waitFor(() => device.received.some((message) => message.method === 'snapshot.get'));
      controller.abort();
      await expect(request).rejects.toMatchObject({ name: 'AbortError' });
      await waitFor(() => device.received.some((message) => message.method === 'rpc.cancel'));
      expect(device.received.find((message) => message.method === 'rpc.cancel')?.params)
        .toEqual({ cancelRequestId: '00000000-0000-4000-8000-000000000124' });
    } finally {
      connection.close();
    }
  });

  it('cancel(requestId) rejects the matching local request and targets only that request', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, respond: () => NO_RESPONSE });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    try {
      const request = connection.request('snapshot.get', {}, {
        requestId: '00000000-0000-4000-8000-000000000125',
      });
      await waitFor(() => device.received.some((message) => message.method === 'snapshot.get'));
      connection.cancel('00000000-0000-4000-8000-000000000125');
      await expect(request).rejects.toMatchObject({ outcomeUnknown: false });
      await waitFor(() => device.received.some((message) => message.method === 'rpc.cancel'));
      expect(device.received.find((message) => message.method === 'rpc.cancel')?.params)
        .toEqual({ cancelRequestId: '00000000-0000-4000-8000-000000000125' });
    } finally {
      connection.close();
    }
  });

  it('reports connected and closed lifecycle events and rejects duplicate in-flight ids', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, respond: () => NO_RESPONSE });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    const events: string[] = [];
    connection.onEvent((event) => events.push(event.type));
    const first = connection.request('device.status', {}, {
      requestId: '00000000-0000-4000-8000-000000000126',
    });
    await waitFor(() => device.received.some((message) => message.method === 'device.status'));
    await expect(connection.request('device.status', {}, {
      requestId: '00000000-0000-4000-8000-000000000126',
    })).rejects.toThrow(/already in flight/);
    connection.close();
    await expect(first).rejects.toThrow(/closed/);
    expect(events).toEqual(['connected', 'closed']);
  });

  it('rejects an outstanding request when explicitly closed', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, respond: () => NO_RESPONSE });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    const request = connection.request('device.status', {});
    await waitFor(() => device.received.some((message) => message.method === 'device.status'));
    connection.close();
    await expect(request).rejects.toThrow(/closed/);
  });

  it('rejects pending requests on close and does not replay an action after loss', async () => {
    const host = await identity();
    const device = await server({
      clientCertificatePem: host.certificatePem,
      respond: (request) => request.method === 'element.action' ? DROP_CONNECTION : NO_RESPONSE,
    });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
    });
    const action = connection.request('element.action', {});
    await expect(action).rejects.toMatchObject({ outcomeUnknown: true });
    expect(device.received.filter((request) => request.method === 'element.action')).toHaveLength(1);
    connection.close();
  });

  it('detects missing heartbeat pong and closes the connection', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem, autoPong: false });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
      heartbeatIntervalMs: 15,
      idleTimeoutMs: 100,
    });
    const disconnected = new Promise<void>((resolve) => {
      connection.onEvent((event) => { if (event.type === 'disconnected') resolve(); });
    });
    await expect(disconnected).resolves.toBeUndefined();
    await expect(connection.request('device.status', {})).rejects.toThrow();
  });

  it('closes an otherwise healthy but idle connection', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem });
    const connection = await RpcConnection.connect({
      url: device.url,
      hostIdentity: host,
      deviceCertificatePem: device.certificatePem,
      deviceFingerprintSha256: device.fingerprintSha256,
      heartbeatIntervalMs: 10,
      idleTimeoutMs: 60,
    });
    const disconnected = new Promise<void>((resolve) => {
      connection.onEvent((event) => { if (event.type === 'disconnected') resolve(); });
    });
    await expect(disconnected).resolves.toBeUndefined();
    await expect(connection.request('device.status', {})).rejects.toThrow();
  });

  it('rejects an untrusted phone certificate even when another valid pin is configured', async () => {
    const host = await identity();
    const trusted = await server({ clientCertificatePem: host.certificatePem });
    const untrusted = await server({ clientCertificatePem: host.certificatePem });
    await expect(RpcConnection.connect({
      url: untrusted.url,
      hostIdentity: host,
      deviceCertificatePem: trusted.certificatePem,
      deviceFingerprintSha256: trusted.fingerprintSha256,
    })).rejects.toThrow();
    expect(untrusted.received).toHaveLength(0);
  });

  it('rejects missing host certificate, TLS downgrade, and plaintext before RPC exchange', async () => {
    const host = await identity();
    const device = await server({ clientCertificatePem: host.certificatePem });
    const missingClientCertificate = new WebSocket(device.url, {
      ca: device.certificatePem,
      rejectUnauthorized: true,
      minVersion: 'TLSv1.3',
      maxVersion: 'TLSv1.3',
    });
    await expect(new Promise<void>((resolve, reject) => {
      missingClientCertificate.once('open', () => resolve());
      missingClientCertificate.once('error', reject);
    })).rejects.toThrow();
    missingClientCertificate.terminate();

    const oldTls = await server({
      clientCertificatePem: host.certificatePem,
      tlsVersion: 'TLSv1.2',
    });
    await expect(RpcConnection.connect({
      url: oldTls.url,
      hostIdentity: host,
      deviceCertificatePem: oldTls.certificatePem,
      deviceFingerprintSha256: oldTls.fingerprintSha256,
    })).rejects.toThrow();

    const plainResponse = await new Promise<Buffer>((resolve, reject) => {
      const socket = connectTcp(Number(new URL(device.url).port), '127.0.0.1', () => {
        socket.write('GET /rpc HTTP/1.1\r\nHost: localhost\r\n\r\n');
      });
      const chunks: Buffer[] = [];
      socket.on('data', (chunk: Buffer) => chunks.push(chunk));
      socket.on('end', () => resolve(Buffer.concat(chunks)));
      socket.on('error', reject);
      socket.setTimeout(1000, () => { socket.destroy(); resolve(Buffer.concat(chunks)); });
    });
    expect(plainResponse.subarray(0, 15).toString()).not.toBe('HTTP/1.1 101 Swi');
    expect(device.received).toHaveLength(0);
  });
});

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 1000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error('condition was not met before timeout');
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}
