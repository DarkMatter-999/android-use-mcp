import { mkdtemp, chmod, readFile, readdir, rm, symlink, writeFile, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { PairingClient, loadOrCreateHostIdentity } from '../src/index.js';

const temporaryDirectories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'android-use-identity-'));
  temporaryDirectories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('loadOrCreateHostIdentity', () => {
  it('creates a private identity atomically and reuses the same key and host id', async () => {
    const directory = join(await temporaryDirectory(), 'identity');
    const first = await loadOrCreateHostIdentity(directory);
    const second = await loadOrCreateHostIdentity(directory);

    expect(first.hostId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(first.hostId).toBe(second.hostId);
    expect(first.certificatePem).toBe(second.certificatePem);
    expect(first.privateKeyPem).toBe(second.privateKeyPem);
    expect(first.fingerprintSha256).toMatch(/^[A-F0-9]{64}$/);
    expect(await stat(directory).then((value) => value.mode & 0o777)).toBe(0o700);
    expect(await stat(join(directory, 'host-key.pem')).then((value) => value.mode & 0o777)).toBe(0o600);
    expect(await stat(join(directory, 'host-id')).then((value) => value.mode & 0o777)).toBe(0o600);
    expect(await stat(join(directory, 'host-cert.pem')).then((value) => value.mode & 0o777)).toBe(0o644);
    expect((await readdir(directory)).sort()).toEqual(['host-cert.pem', 'host-id', 'host-key.pem']);
  });

  it('rejects mismatched or incomplete identity files without overwriting them', async () => {
    const directory = await temporaryDirectory();
    const identityDirectory = join(directory, 'identity');
    const identity = await loadOrCreateHostIdentity(identityDirectory);
    await writeFile(join(identityDirectory, 'host-key.pem'), identity.privateKeyPem.replace(/A/, 'B'));
    const before = await readFile(join(identityDirectory, 'host-key.pem'));
    await expect(loadOrCreateHostIdentity(identityDirectory)).rejects.toThrow();
    expect(await readFile(join(identityDirectory, 'host-key.pem'))).toEqual(before);

    const partial = join(directory, 'partial');
    await import('node:fs/promises').then(({ mkdir }) => mkdir(partial, { mode: 0o700 }));
    await writeFile(join(partial, 'host-id'), '11111111-1111-4111-8111-111111111111', { mode: 0o600 });
    await expect(loadOrCreateHostIdentity(partial)).rejects.toThrow();
    expect(await readdir(partial)).toEqual(['host-id']);
  });

  it('rejects symlinks and unsafe private file permissions', async () => {
    const directory = await temporaryDirectory();
    const source = await loadOrCreateHostIdentity(join(directory, 'source'));
    const linked = join(directory, 'linked');
    await import('node:fs/promises').then(({ mkdir }) => mkdir(linked, { mode: 0o700 }));
    await symlink(join(directory, 'source', 'host-key.pem'), join(linked, 'host-key.pem'));
    await writeFile(join(linked, 'host-cert.pem'), source.certificatePem, { mode: 0o644 });
    await writeFile(join(linked, 'host-id'), source.hostId, { mode: 0o600 });
    await expect(loadOrCreateHostIdentity(linked)).rejects.toThrow();

    const unsafe = join(directory, 'unsafe');
    await loadOrCreateHostIdentity(unsafe);
    await chmod(join(unsafe, 'host-key.pem'), 0o644);
    await expect(loadOrCreateHostIdentity(unsafe)).rejects.toThrow();
  });
});

describe('PairingClient', () => {
  it('rejects ambiguous pairing codes before using the connection', async () => {
    const calls: string[] = [];
    const client = new PairingClient({
      async request<T>(method: string): Promise<T> {
        calls.push(method);
        throw new Error('must not connect');
      },
    }, {
      hostId: '11111111-1111-4111-8111-111111111111',
      certificatePem: '',
      privateKeyPem: '',
      fingerprintSha256: 'A'.repeat(64),
    });

    await expect(client.pair('ABCD0FGH', 'Test Host')).rejects.toThrow(/unambiguous/);
    await expect(client.pair('abcdEFGH', 'Test Host')).rejects.toThrow(/unambiguous/);
    expect(calls).toEqual([]);
  });

  it('submits once and polls existing device.status until phone approval', async () => {
    const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
    let polls = 0;
    const client = new PairingClient({
      async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
        calls.push({ method, params });
        if (method === 'pairing.begin') return { challengeId: 'challenge-1', status: 'WAITING' } as T;
        polls++;
        return { pairing: { challengeId: 'challenge-1', status: polls === 1 ? 'WAITING' : 'APPROVED' } } as T;
      },
    }, {
      hostId: '11111111-1111-4111-8111-111111111111',
      certificatePem: 'public cert',
      privateKeyPem: 'private key',
      fingerprintSha256: 'A'.repeat(64),
    });

    const approved = await client.pair('ABCDEFGH', '  Test Host  ', { pollIntervalMs: 1, timeoutMs: 1000 });

    expect(approved).toEqual({
      hostId: '11111111-1111-4111-8111-111111111111',
      hostName: 'Test Host',
      fingerprintSha256: 'A'.repeat(64),
    });
    expect(calls.filter((call) => call.method === 'pairing.begin')).toHaveLength(1);
    expect(calls[0]?.params).toEqual({
      code: 'ABCDEFGH',
      hostId: '11111111-1111-4111-8111-111111111111',
      hostName: 'Test Host',
    });
    expect(calls.slice(1).every((call) => call.method === 'device.status')).toBe(true);
    expect(calls.some((call) => call.method === 'pairing.approve')).toBe(false);
  });

  it.each(['REJECTED', 'EXPIRED'] as const)('surfaces phone %s without approval RPC', async (status) => {
    const calls: string[] = [];
    const client = new PairingClient({
      async request<T>(method: string): Promise<T> {
        calls.push(method);
        if (method === 'pairing.begin') return { challengeId: 'challenge-1', status: 'WAITING' } as T;
        return { pairing: { challengeId: 'challenge-1', status } } as T;
      },
    }, {
      hostId: '11111111-1111-4111-8111-111111111111',
      certificatePem: '',
      privateKeyPem: '',
      fingerprintSha256: 'A'.repeat(64),
    });

    await expect(client.pair('ABCDEFGH', 'Test Host', { pollIntervalMs: 1, timeoutMs: 1000 }))
      .rejects.toMatchObject({ status });
    expect(calls).toEqual(['pairing.begin', 'device.status']);
  });

  it('cancels approval polling without resubmitting the pairing code', async () => {
    const calls: string[] = [];
    const controller = new AbortController();
    const client = new PairingClient({
      async request<T>(method: string): Promise<T> {
        calls.push(method);
        return method === 'pairing.begin'
          ? { challengeId: 'challenge-1', status: 'WAITING' } as T
          : { pairing: { challengeId: 'challenge-1', status: 'WAITING' } } as T;
      },
    }, {
      hostId: '11111111-1111-4111-8111-111111111111',
      certificatePem: '',
      privateKeyPem: '',
      fingerprintSha256: 'A'.repeat(64),
    });

    const pairing = client.pair('ABCDEFGH', 'Test Host', {
      pollIntervalMs: 100,
      timeoutMs: 1000,
      signal: controller.signal,
    });
    setTimeout(() => controller.abort(), 10);
    await expect(pairing).rejects.toMatchObject({ name: 'AbortError' });
    expect(calls.filter((method) => method === 'pairing.begin')).toHaveLength(1);
  });
});
