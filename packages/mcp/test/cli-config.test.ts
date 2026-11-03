import { describe, expect, it } from 'vitest';

import { parseCliConfig } from '../src/cli-config.js';

const validEnv = {
  ANDROID_USE_URL: 'wss://192.0.2.10:47831/rpc',
  ANDROID_USE_DEVICE_CERTIFICATE: '/tmp/device-cert.pem',
  ANDROID_USE_DEVICE_CERTIFICATE_PIN: 'ab'.repeat(32),
  ANDROID_USE_IDENTITY_DIR: '/tmp/android-use-identity',
};

describe('MCP CLI configuration', () => {
  it('applies explicit flags over environment values', () => {
    expect(parseCliConfig([
      '--url', 'wss://192.0.2.20:47831/rpc',
      '--device-certificate', '/tmp/override.pem',
      '--device-certificate-pin', 'cd'.repeat(32),
      '--identity-dir', '/tmp/override-identity',
    ], validEnv)).toEqual({
      url: 'wss://192.0.2.20:47831/rpc',
      discovery: false,
      certPath: '/tmp/override.pem',
      pin: 'CD'.repeat(32),
      identityDir: '/tmp/override-identity',
    });
  });

  it('uses environment values and the secure default identity directory', () => {
    const { ANDROID_USE_IDENTITY_DIR: _identityDir, ...withoutIdentityDir } = validEnv;
    expect(parseCliConfig([], withoutIdentityDir, '/home/test/.config/android-use-mcp')).toEqual({
      url: validEnv.ANDROID_USE_URL,
      discovery: false,
      certPath: validEnv.ANDROID_USE_DEVICE_CERTIFICATE,
      pin: 'AB'.repeat(32),
      identityDir: '/home/test/.config/android-use-mcp',
    });
  });

  it('supports explicit discovery without a URL and requires exactly one connection mode', () => {
    const { ANDROID_USE_URL: _url, ...withoutUrl } = validEnv;
    expect(parseCliConfig(['--discover'], withoutUrl)).toEqual({
      url: undefined,
      discovery: true,
      certPath: validEnv.ANDROID_USE_DEVICE_CERTIFICATE,
      pin: 'AB'.repeat(32),
      identityDir: validEnv.ANDROID_USE_IDENTITY_DIR,
    });
    expect(parseCliConfig([], { ...withoutUrl, ANDROID_USE_DISCOVERY: 'true' }).discovery).toBe(true);
    expect(() => parseCliConfig([], {})).toThrow(/missing required settings/i);
    expect(parseCliConfig(['--discover'], validEnv).url).toBeUndefined();
    expect(() => parseCliConfig(['--discover', '--url', validEnv.ANDROID_USE_URL], withoutUrl))
      .toThrow(/cannot be used with --url/i);
    expect(() => parseCliConfig([], { ...withoutUrl, ANDROID_USE_DISCOVERY: 'true', ANDROID_USE_URL: validEnv.ANDROID_USE_URL }))
      .toThrow(/cannot be used with --url/i);
  });

  it('still requires certificate and pin in discovery mode', () => {
    expect(() => parseCliConfig(['--discover'], { ANDROID_USE_DEVICE_CERTIFICATE: '/tmp/cert.pem' }))
      .toThrow(/device-certificate-pin/i);
    expect(() => parseCliConfig(['--discover'], { ANDROID_USE_DEVICE_CERTIFICATE_PIN: 'AB'.repeat(32) }))
      .toThrow(/device-certificate/i);
  });

  it('rejects missing, unknown, malformed URL, and malformed certificate pin settings', () => {
    expect(() => parseCliConfig([], {})).toThrow(/missing required settings/i);
    expect(() => parseCliConfig(['--surprise', 'value'], validEnv)).toThrow(/unknown option/i);
    expect(() => parseCliConfig(['--url'], validEnv)).toThrow(/missing value/i);
    expect(() => parseCliConfig([], { ...validEnv, ANDROID_USE_URL: 'http://example.invalid/rpc' }))
      .toThrow(/url.*wss/i);
    expect(() => parseCliConfig([], { ...validEnv, ANDROID_USE_DEVICE_CERTIFICATE_PIN: 'not-a-pin' }))
      .toThrow(/sha-256/i);
  });
});
