import { homedir } from 'node:os';
import { join } from 'node:path';

export interface CliConfig {
  readonly url: string;
  readonly certPath: string;
  readonly pin: string;
  readonly identityDir: string;
}

const SUPPORTED_OPTIONS = new Set([
  '--url',
  '--device-certificate',
  '--device-certificate-pin',
  '--identity-dir',
]);

export function parseCliConfig(
  args: readonly string[],
  env: NodeJS.ProcessEnv = process.env,
  defaultIdentityDir = join(homedir(), '.config', 'android-use-mcp'),
): CliConfig {
  const values = new Map<string, string>();
  for (let index = 0; index < args.length; index += 1) {
    const option = args[index];
    if (option === undefined) break;
    if (!SUPPORTED_OPTIONS.has(option)) throw new Error(`Unknown option: ${option}`);
    const value = args[index + 1];
    if (value === undefined || value.length === 0 || value.startsWith('--')) {
      throw new Error(`Missing value for ${option}`);
    }
    values.set(option, value);
    index += 1;
  }

  const urlValue = values.get('--url') ?? env['ANDROID_USE_URL'];
  const certPath = values.get('--device-certificate') ?? env['ANDROID_USE_DEVICE_CERTIFICATE'];
  const pinValue = values.get('--device-certificate-pin') ?? env['ANDROID_USE_DEVICE_CERTIFICATE_PIN'];
  const identityDir = values.get('--identity-dir') ?? env['ANDROID_USE_IDENTITY_DIR'] ?? defaultIdentityDir;
  const missing = [
    urlValue === undefined && '--url',
    certPath === undefined && '--device-certificate',
    pinValue === undefined && '--device-certificate-pin',
  ].filter((entry): entry is string => Boolean(entry));
  if (missing.length > 0) throw new Error(`Missing required settings: ${missing.join(', ')}`);

  let url: URL;
  try {
    url = new URL(urlValue!);
  } catch {
    throw new Error('--url must be a valid wss:// endpoint');
  }
  if (url.protocol !== 'wss:' || !url.hostname || url.username || url.password) {
    throw new Error('--url must be a valid wss:// endpoint without embedded credentials');
  }

  const pin = pinValue!.replaceAll(':', '').toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(pin)) throw new Error('--device-certificate-pin must be a 32-byte SHA-256 fingerprint');
  return { url: url.toString(), certPath: certPath!, pin, identityDir };
}
