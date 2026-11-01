import { describe, expect, it } from 'vitest';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import {
  AdbMultipleDevicesError,
  AdbNoDevicesError,
  AdbOfflineDeviceError,
  AdbUnauthorizedDeviceError,
  AdbUnknownDeviceError,
  parseAdbDevices,
  listDevices,
  selectDevice,
} from '../src/index.js';

describe('parseAdbDevices and selectDevice', () => {
  it('parses one authorized device and its descriptive attributes', () => {
    const devices = parseAdbDevices(
      'List of devices attached\nZX1A22B3C4\tdevice product:oriole model:Pixel_6 device:oriole transport_id:7\n',
    );
    expect(devices).toEqual([{
      serial: 'ZX1A22B3C4',
      state: 'device',
      attributes: { product: 'oriole', model: 'Pixel_6', device: 'oriole', transport_id: '7' },
    }]);
    expect(selectDevice(devices)).toEqual(devices[0]);
  });

  it('fails distinctly for no devices, multiple devices, and an unknown requested serial', () => {
    expect(() => selectDevice(parseAdbDevices('List of devices attached\n'))).toThrow(AdbNoDevicesError);
    const devices = parseAdbDevices(
      'List of devices attached\nserial-one\tdevice\nserial-two\tdevice\n',
    );
    expect(() => selectDevice(devices)).toThrow(AdbMultipleDevicesError);
    expect(() => selectDevice(devices, 'not-present')).toThrow(AdbUnknownDeviceError);
  });

  it('reports unauthorized and offline devices as distinct selection errors', () => {
    const unauthorized = parseAdbDevices('List of devices attached\nphone-1\tunauthorized\n');
    const offline = parseAdbDevices('List of devices attached\nphone-2\toffline\n');
    expect(() => selectDevice(unauthorized)).toThrow(AdbUnauthorizedDeviceError);
    expect(() => selectDevice(offline)).toThrow(AdbOfflineDeviceError);
    expect(() => selectDevice(unauthorized, 'phone-1')).toThrow(AdbUnauthorizedDeviceError);
    expect(() => selectDevice(offline, 'phone-2')).toThrow(AdbOfflineDeviceError);
  });

  it('ignores malformed lines but preserves serial tokens without shell interpretation', () => {
    const devices = parseAdbDevices(
      'List of devices attached\nmalformed line with spaces\n$(touch_PWN);safe\tdevice\tmodel:test\n',
    );
    expect(devices).toHaveLength(1);
    expect(devices[0]?.serial).toBe('$(touch_PWN);safe');
    expect(selectDevice(devices).serial).toBe('$(touch_PWN);safe');
  });

  it('lists devices by invoking adb devices -l with a shell-free argument vector', async () => {
    let invokedArgs: readonly string[] = [];
    let invokedShell: boolean | undefined;
    const spawnImpl = ((_: string, args: readonly string[], options: { shell?: boolean }) => {
      invokedArgs = args;
      invokedShell = options.shell;
      const child = new EventEmitter() as EventEmitter & {
        stdout: PassThrough; stderr: PassThrough; stdin: PassThrough; kill(): void;
      };
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = new PassThrough();
      child.kill = () => undefined;
      queueMicrotask(() => {
        child.stdout.end('List of devices attached\nphone-1\tdevice model:Pixel\n');
        child.stderr.end();
        child.emit('close', 0);
      });
      return child;
    }) as never;

    await expect(listDevices({ spawnImpl })).resolves.toMatchObject([{ serial: 'phone-1', state: 'device' }]);
    expect(invokedArgs).toEqual(['devices', '-l']);
    expect(invokedShell).toBe(false);
  });
});
