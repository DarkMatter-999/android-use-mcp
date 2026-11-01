import { createServer } from 'node:net';
import { runAdb, type RunAdbOptions } from './adb.js';

export interface ForwardConnection {
  close(): void | Promise<void>;
}

export interface UsbForwarderOptions {
  serial: string;
  hostPort?: number;
  executable?: string;
  runAdb?: typeof runAdb;
  connection?: ForwardConnection;
}

export interface EnsureForwardedOptions {
  hostPort?: number;
  connection?: ForwardConnection;
}

export interface ForwardHandle {
  readonly serial: string;
  readonly hostPort: number;
  readonly devicePort: number;
  close(): Promise<void>;
}

export class UsbForwarder {
  private active: ForwardHandle | undefined;
  private pending: {
    devicePort: number;
    requestedHostPort: number | undefined;
    promise: Promise<ForwardHandle>;
  } | undefined;

  constructor(private readonly options: UsbForwarderOptions) {
    if (!options.serial || options.serial.includes('\0')) throw new TypeError('an explicit NUL-free ADB serial is required');
    if (options.hostPort !== undefined) validatePort(options.hostPort, 'host port');
  }

  async ensureForwarded(devicePort: number, options: EnsureForwardedOptions = {}): Promise<ForwardHandle> {
    validatePort(devicePort, 'device port');
    const requestedHostPort = options.hostPort ?? this.options.hostPort;
    if (requestedHostPort !== undefined) validatePort(requestedHostPort, 'host port');
    if (this.active) {
      if (this.active.devicePort !== devicePort ||
        (requestedHostPort !== undefined && this.active.hostPort !== requestedHostPort)) {
        throw new Error('this forwarder already owns a different forwarding rule');
      }
      return this.active;
    }

    if (this.pending) {
      if (this.pending.devicePort !== devicePort || this.pending.requestedHostPort !== requestedHostPort) {
        throw new Error('this forwarder is already creating a different forwarding rule');
      }
      return this.pending.promise;
    }

    const promise = this.createForward(devicePort, requestedHostPort, options.connection);
    this.pending = { devicePort, requestedHostPort, promise };
    try {
      return await promise;
    } finally {
      if (this.pending?.promise === promise) this.pending = undefined;
    }
  }

  private async createForward(
    devicePort: number,
    requestedHostPort: number | undefined,
    connection: ForwardConnection | undefined,
  ): Promise<ForwardHandle> {
    const hostPort = requestedHostPort ?? await findAvailableLoopbackPort();
    const run = this.options.runAdb ?? runAdb;
    const runOptions: RunAdbOptions = this.options.executable ? { executable: this.options.executable } : {};
    await run([
      '-s', this.options.serial,
      'forward',
      '--no-rebind',
      `tcp:${hostPort}`,
      `tcp:${devicePort}`,
    ], runOptions);

    const handle: ForwardHandle = {
      serial: this.options.serial,
      hostPort,
      devicePort,
      close: async () => this.closeHandle(handle, connection ?? this.options.connection),
    };
    this.active = handle;
    return handle;
  }

  async close(): Promise<void> {
    if (this.pending) {
      try { await this.pending.promise; } catch { return; }
    }
    if (this.active) await this.active.close();
  }

  private async closeHandle(handle: ForwardHandle, connection?: ForwardConnection): Promise<void> {
    if (this.active !== handle) return;
    this.active = undefined;
    let failure: unknown;
    try {
      const run = this.options.runAdb ?? runAdb;
      const runOptions: RunAdbOptions = this.options.executable ? { executable: this.options.executable } : {};
      await run([
        '-s', handle.serial,
        'forward', '--remove',
        `tcp:${handle.hostPort}`,
      ], runOptions);
    } catch (error) {
      failure = error;
    }
    try {
      await connection?.close();
    } catch (error) {
      failure ??= error;
    }
    if (failure) throw failure;
  }
}

function validatePort(port: number, label: string): void {
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new RangeError(`${label} must be an integer in 1..65535`);
  }
}

async function findAvailableLoopbackPort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
  const address = server.address();
  if (!address || typeof address === 'string') {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    throw new Error('failed to allocate a loopback port');
  }
  const port = address.port;
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}
