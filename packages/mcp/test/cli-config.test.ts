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
      certPath: '/tmp/override.pem',
      pin: 'CD'.repeat(32),
      identityDir: '/tmp/override-identity',
    });
  });

  it('uses environment values and the secure default identity directory', () => {
    const { ANDROID_USE_IDENTITY_DIR: _identityDir, ...withoutIdentityDir } = validEnv;
    expect(parseCliConfig([], withoutIdentityDir, '/home/test/.config/android-use-mcp')).toEqual({
      url: validEnv.ANDROID_USE_URL,
      certPath: validEnv.ANDROID_USE_DEVICE_CERTIFICATE,
      pin: 'AB'.repeat(32),
      identityDir: '/home/test/.config/android-use-mcp',
    });
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
