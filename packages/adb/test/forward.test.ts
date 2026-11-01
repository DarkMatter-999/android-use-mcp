import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { describe, expect, it, vi } from 'vitest';
import {
  AdbCommandError,
  AdbNotFoundError,
  UsbForwarder,
  runAdb,
} from '../src/index.js';

describe('runAdb', () => {
  it('passes argument arrays with shell disabled and captures command output', async () => {
    let observed: { executable: string; args: readonly string[]; options: { shell?: boolean } } | undefined;
    const spawnImpl = ((executable: string, args: readonly string[], options: { shell?: boolean }) => {
      observed = { executable, args, options };
      const child = new EventEmitter() as EventEmitter & {
        stdout: PassThrough;
        stderr: PassThrough;
        stdin: PassThrough;
        kill(): void;
      };
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = new PassThrough();
      child.kill = () => undefined;
      queueMicrotask(() => {
        child.stdout.end('devices-output');
        child.stderr.end('');
        child.emit('close', 0);
      });
      return child;
    }) as never;

    await expect(runAdb(['-s', 'serial;$(no-shell)', 'devices'], { executable: 'adb-fixture', spawnImpl }))
      .resolves.toEqual({ stdout: 'devices-output', stderr: '', exitCode: 0 });
    expect(observed).toEqual({
      executable: 'adb-fixture',
      args: ['-s', 'serial;$(no-shell)', 'devices'],
      options: expect.objectContaining({ shell: false }),
    });
  });

  it('distinguishes a missing adb executable from a nonzero command result', async () => {
    const missing = ((() => {
      const child = new EventEmitter() as EventEmitter & {
        stdout: PassThrough; stderr: PassThrough; stdin: PassThrough; kill(): void;
      };
      child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.stdin = new PassThrough(); child.kill = () => undefined;
      queueMicrotask(() => child.emit('error', Object.assign(new Error('missing'), { code: 'ENOENT' })));
      return child;
    }) as never);
    await expect(runAdb(['devices'], { spawnImpl: missing })).rejects.toBeInstanceOf(AdbNotFoundError);

    const failed = ((() => {
      const child = new EventEmitter() as EventEmitter & {
        stdout: PassThrough; stderr: PassThrough; stdin: PassThrough; kill(): void;
      };
      child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.stdin = new PassThrough(); child.kill = () => undefined;
      queueMicrotask(() => { child.stderr.end('device unauthorized'); child.emit('close', 1); });
      return child;
    }) as never);
    await expect(runAdb(['devices'], { spawnImpl: failed })).rejects.toBeInstanceOf(AdbCommandError);
  });
});

describe('UsbForwarder', () => {
  it('forwards host loopback to device loopback and removes only its own rule', async () => {
    const run = vi.fn(async (_args: readonly string[], _options?: { executable?: string; timeoutMs?: number }) => ({ stdout: '', stderr: '', exitCode: 0 }));
    const closeConnection = vi.fn();
    const forwarder = new UsbForwarder({
      serial: 'device;$(not-shell)',
      hostPort: 43127,
      runAdb: run,
      connection: { close: closeConnection },
    });

    const handle = await forwarder.ensureForwarded(7831);
    expect(run).toHaveBeenNthCalledWith(1, [
      '-s', 'device;$(not-shell)', 'forward', '--no-rebind', 'tcp:43127', 'tcp:7831',
    ], expect.any(Object));
    expect(handle).toMatchObject({ hostPort: 43127, devicePort: 7831, serial: 'device;$(not-shell)' });
    await handle.close();

    expect(run).toHaveBeenNthCalledWith(2, [
      '-s', 'device;$(not-shell)', 'forward', '--remove', 'tcp:43127',
    ], expect.any(Object));
    expect(closeConnection).toHaveBeenCalledTimes(1);
  });

  it('does not remove a forwarding rule if creation failed and validates ports before adb', async () => {
    const run = vi.fn(async (_args: readonly string[], _options?: { executable?: string; timeoutMs?: number }) => {
      throw new Error('adb forward failed');
    });
    const forwarder = new UsbForwarder({ serial: 'device-1', hostPort: 43128, runAdb: run });
    await expect(forwarder.ensureForwarded(7831)).rejects.toThrow('adb forward failed');
    expect(run).toHaveBeenCalledTimes(1);
    await forwarder.close();
    expect(run).toHaveBeenCalledTimes(1);
    await expect(forwarder.ensureForwarded(0)).rejects.toThrow(/port/);
    await expect(forwarder.ensureForwarded(65536)).rejects.toThrow(/port/);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('chooses an available loopback host port when none is specified', async () => {
    const run = vi.fn(async (_args: readonly string[], _options?: { executable?: string; timeoutMs?: number }) => ({ stdout: '', stderr: '', exitCode: 0 }));
    const forwarder = new UsbForwarder({ serial: 'device-1', runAdb: run });
    const handle = await forwarder.ensureForwarded(7831);
    expect(handle.hostPort).toBeGreaterThan(0);
    expect(run.mock.calls[0]?.[0]).toContain(`tcp:${handle.hostPort}`);
    await forwarder.close();
  });

  it('deduplicates concurrent ensure calls so this instance owns exactly one rule', async () => {
    let finishForward!: () => void;
    let invocations = 0;
    const run = vi.fn(async (_args: readonly string[], _options?: { executable?: string; timeoutMs?: number }) => {
      invocations++;
      if (invocations === 1) await new Promise<void>((resolve) => { finishForward = resolve; });
      return { stdout: '', stderr: '', exitCode: 0 };
    });
    const forwarder = new UsbForwarder({ serial: 'device-1', hostPort: 43129, runAdb: run });

    const first = forwarder.ensureForwarded(7831);
    const second = forwarder.ensureForwarded(7831);
    await Promise.resolve();

    expect(run).toHaveBeenCalledTimes(1);
    finishForward();
    const [firstHandle, secondHandle] = await Promise.all([first, second]);
    expect(firstHandle).toBe(secondHandle);
    await firstHandle.close();
  });
});
