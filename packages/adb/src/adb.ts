import { spawn, type ChildProcess } from 'node:child_process';

export interface AdbDevice {
  serial: string;
  state: string;
  attributes: Record<string, string>;
}

export class AdbError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}
export class AdbNoDevicesError extends AdbError {}
export class AdbMultipleDevicesError extends AdbError {}
export class AdbUnknownDeviceError extends AdbError {}
export class AdbUnauthorizedDeviceError extends AdbError {}
export class AdbOfflineDeviceError extends AdbError {}
export class AdbUnavailableDeviceError extends AdbError {}
export class AdbNotFoundError extends AdbError {}
export class AdbCommandError extends AdbError {
  constructor(message: string, readonly exitCode: number | null, readonly stderr: string) {
    super(message);
  }
}

export function parseAdbDevices(output: string): AdbDevice[] {
  const devices: AdbDevice[] = [];
  for (const rawLine of output.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('List of devices attached') || line.startsWith('*') || !rawLine.includes('\t')) continue;
    const columns = line.split(/\s+/);
    if (columns.length < 2 || !columns[0] || !columns[1]) continue;
    const [serial, state, ...metadata] = columns;
    const attributes: Record<string, string> = {};
    for (const entry of metadata) {
      const separator = entry.indexOf(':');
      if (separator <= 0 || separator === entry.length - 1) continue;
      attributes[entry.slice(0, separator)] = entry.slice(separator + 1);
    }
    devices.push({ serial, state, attributes });
  }
  return devices;
}

export function selectDevice(devices: readonly AdbDevice[], serial?: string): AdbDevice {
  if (devices.length === 0) throw new AdbNoDevicesError('no ADB devices are connected');
  if (serial !== undefined) {
    const selected = devices.find((device) => device.serial === serial);
    if (!selected) throw new AdbUnknownDeviceError(`ADB device ${serial} was not found`);
    assertUsable(selected);
    return selected;
  }
  const online = devices.filter((device) => device.state === 'device');
  if (online.length > 1) throw new AdbMultipleDevicesError('multiple ADB devices are available; select a serial explicitly');
  if (online.length === 1) return online[0]!;
  const unauthorized = devices.find((device) => device.state === 'unauthorized');
  if (unauthorized) throw new AdbUnauthorizedDeviceError(`ADB device ${unauthorized.serial} is unauthorized`);
  const offline = devices.find((device) => device.state === 'offline');
  if (offline) throw new AdbOfflineDeviceError(`ADB device ${offline.serial} is offline`);
  throw new AdbUnavailableDeviceError('no usable ADB device is available');
}

function assertUsable(device: AdbDevice): void {
  if (device.state === 'unauthorized') throw new AdbUnauthorizedDeviceError(`ADB device ${device.serial} is unauthorized`);
  if (device.state === 'offline') throw new AdbOfflineDeviceError(`ADB device ${device.serial} is offline`);
  if (device.state !== 'device') throw new AdbUnavailableDeviceError(`ADB device ${device.serial} is ${device.state}`);
}

export interface AdbCommandResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

export interface RunAdbOptions {
  executable?: string;
  timeoutMs?: number;
  spawnImpl?: typeof spawn;
}

export async function runAdb(args: readonly string[], options: RunAdbOptions = {}): Promise<AdbCommandResult> {
  if (!Array.isArray(args) || args.some((argument) => typeof argument !== 'string' || argument.includes('\0'))) {
    throw new TypeError('ADB arguments must be NUL-free strings');
  }
  const executable = options.executable ?? 'adb';
  const timeoutMs = options.timeoutMs ?? 30_000;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new RangeError('ADB timeout must be positive');
  const spawnImpl = options.spawnImpl ?? spawn;
  return new Promise((resolve, reject) => {
    let child: ChildProcess;
    try {
      child = spawnImpl(executable, [...args], {
        shell: false,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      }) as ChildProcess;
    } catch (error) {
      reject(mapSpawnError(error, executable));
      return;
    }
    if (!child.stdout || !child.stderr) {
      child.kill();
      reject(new AdbError('ADB process did not provide stdout/stderr pipes'));
      return;
    }
    let stdout = '';
    let stderr = '';
    let settled = false;
    const timer = setTimeout(() => {
      child.kill();
      finish(new AdbError(`ADB command timed out after ${timeoutMs} ms`));
    }, timeoutMs);
    timer.unref?.();
    child.stdout.setEncoding('utf8').on('data', (chunk: string) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk: string) => { stderr += chunk; });
    child.once('error', (error) => finish(mapSpawnError(error, executable)));
    child.once('close', (code) => {
      if (code !== 0) {
        finish(new AdbCommandError(`ADB exited with status ${code ?? 'unknown'}`, code, stderr));
      } else {
        finish(undefined, { stdout, stderr, exitCode: 0 });
      }
    });

    function finish(error?: Error, result?: AdbCommandResult): void {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(result!);
    }
  });
}

export interface ListDevicesOptions extends RunAdbOptions {}

export async function listDevices(options: ListDevicesOptions = {}): Promise<AdbDevice[]> {
  const result = await runAdb(['devices', '-l'], options);
  return parseAdbDevices(result.stdout);
}

function mapSpawnError(error: unknown, executable: string): Error {
  if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
    return new AdbNotFoundError(`ADB executable was not found: ${executable}`, { cause: error });
  }
  return error instanceof Error ? new AdbError(`failed to execute ADB: ${error.message}`, { cause: error }) :
    new AdbError('failed to execute ADB');
}
