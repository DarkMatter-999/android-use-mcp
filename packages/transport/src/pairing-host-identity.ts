import { randomUUID, X509Certificate, createPrivateKey, createPublicKey, createHash } from 'node:crypto';
import { link, lstat, mkdir, open, readFile, readdir, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import selfsigned from 'selfsigned';

export interface HostIdentity {
  hostId: string;
  certificatePem: string;
  privateKeyPem: string;
  fingerprintSha256: string;
}

const CERT_FILE = 'host-cert.pem';
const KEY_FILE = 'host-key.pem';
const ID_FILE = 'host-id';
const EXPECTED_FILES = [CERT_FILE, ID_FILE, KEY_FILE].sort();

export async function loadOrCreateHostIdentity(directory: string): Promise<HostIdentity> {
  try {
    await mkdir(directory, { recursive: true, mode: 0o700 });
  } catch (error) {
    if (!isNodeError(error, 'EEXIST')) throw error;
  }

  const directoryStat = await lstat(directory);
  if (!directoryStat.isDirectory() || directoryStat.isSymbolicLink()) {
    throw new Error('host identity path must be a real directory');
  }
  if ((directoryStat.mode & 0o777) !== 0o700) {
    throw new Error('host identity directory permissions must be 0700');
  }

  const names = await readdir(directory);
  if (names.length === 0) return createIdentity(directory);
  if (names.length !== EXPECTED_FILES.length || EXPECTED_FILES.some((name) => !names.includes(name))) {
    throw new Error('host identity directory is incomplete or contains unexpected files');
  }

  const [keyStat, certificateStat, idStat] = await Promise.all([
    lstat(join(directory, KEY_FILE)),
    lstat(join(directory, CERT_FILE)),
    lstat(join(directory, ID_FILE)),
  ]);
  if ([keyStat, certificateStat, idStat].some((value) => !value.isFile() || value.isSymbolicLink())) {
    throw new Error('host identity files must be regular files, not symlinks');
  }
  if ((keyStat.mode & 0o777) !== 0o600 || (idStat.mode & 0o777) !== 0o600) {
    throw new Error('host private key and host id permissions must be 0600');
  }
  if ((certificateStat.mode & 0o777) !== 0o644) {
    throw new Error('host certificate permissions must be 0644');
  }

  const [privateKeyPem, certificatePem, hostId] = await Promise.all([
    readFile(join(directory, KEY_FILE), 'utf8'),
    readFile(join(directory, CERT_FILE), 'utf8'),
    readFile(join(directory, ID_FILE), 'utf8'),
  ]);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\n$/i.test(hostId)) {
    throw new Error('host id is malformed');
  }
  return validateIdentity(hostId.trim(), certificatePem, privateKeyPem);
}

async function createIdentity(directory: string): Promise<HostIdentity> {
  const lockPath = join(directory, '.identity.lock');
  let lock;
  try {
    lock = await open(lockPath, 'wx', 0o600);
  } catch (error) {
    if (isNodeError(error, 'EEXIST')) throw new Error('host identity creation is already in progress');
    throw error;
  }

  const staged: string[] = [];
  const installed: string[] = [];
  try {
    if ((await readdir(directory)).some((name) => name !== '.identity.lock')) {
      throw new Error('host identity directory changed during creation');
    }
    const hostId = randomUUID();
    const generated = await selfsigned.generate(
      [{ name: 'commonName', value: `AndroidUse Host ${hostId}` }],
      {
        keyType: 'ec',
        curve: 'P-256',
        algorithm: 'sha256',
        notBeforeDate: new Date(Date.now() - 60_000),
        notAfterDate: new Date(Date.now() + 5 * 365 * 24 * 60 * 60 * 1000),
        extensions: [
          { name: 'basicConstraints', cA: false },
          { name: 'keyUsage', digitalSignature: true },
          { name: 'extKeyUsage', clientAuth: true },
          { name: 'subjectAltName', altNames: [{ type: 6, value: `urn:android-use:host:${hostId}` }] },
        ],
      },
    );
    const material = [
      [KEY_FILE, generated.private, 0o600],
      [CERT_FILE, generated.cert, 0o644],
      [ID_FILE, `${hostId}\n`, 0o600],
    ] as const;
    const temporary = new Map<string, string>();
    for (const [fileName, contents, mode] of material) {
      const tempPath = join(directory, `.${fileName}.${randomUUID()}.tmp`);
      const handle = await open(tempPath, 'wx', mode);
      try {
        await handle.writeFile(contents, 'utf8');
        await handle.sync();
      } finally {
        await handle.close();
      }
      staged.push(tempPath);
      temporary.set(fileName, tempPath);
    }
    for (const [fileName] of material) {
      const destination = join(directory, fileName);
      await link(temporary.get(fileName)!, destination);
      installed.push(destination);
    }
    for (const tempPath of staged) await unlink(tempPath);
    staged.length = 0;
    return validateIdentity(hostId, generated.cert, generated.private);
  } catch (error) {
    await Promise.all([...staged, ...installed].map((path) => unlink(path).catch(() => undefined)));
    throw error;
  } finally {
    await lock.close();
    await unlink(lockPath).catch(() => undefined);
  }
}

function validateIdentity(hostId: string, certificatePem: string, privateKeyPem: string): HostIdentity {
  const certificate = new X509Certificate(certificatePem);
  const privateKey = createPrivateKey(privateKeyPem);
  const publicFromPrivate = createPublicKey(privateKey).export({ type: 'spki', format: 'der' });
  const publicFromCertificate = certificate.publicKey.export({ type: 'spki', format: 'der' });
  if (!publicFromPrivate.equals(publicFromCertificate)) {
    throw new Error('host private key does not match host certificate');
  }
  if (privateKey.asymmetricKeyType !== 'ec' || privateKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('host identity must use a P-256 elliptic curve key');
  }
  if (certificate.ca || !certificate.keyUsage.includes('1.3.6.1.5.5.7.3.2')) {
    throw new Error('host certificate must be a non-CA client-auth identity');
  }
  if (!certificate.subjectAltName?.includes(`URI:urn:android-use:host:${hostId}`)) {
    throw new Error('host certificate is not bound to the host id');
  }
  const now = Date.now();
  if (Date.parse(certificate.validFrom) > now || Date.parse(certificate.validTo) <= now) {
    throw new Error('host certificate is not currently valid');
  }
  certificate.checkIssued(certificate);
  if (!certificate.verify(certificate.publicKey)) throw new Error('host certificate signature is invalid');
  const fingerprintSha256 = createHash('sha256').update(certificate.raw).digest('hex').toUpperCase();
  return { hostId, certificatePem, privateKeyPem, fingerprintSha256 };
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === code;
}
