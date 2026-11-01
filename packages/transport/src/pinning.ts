import { X509Certificate, createHash } from 'node:crypto';

interface PeerCertificateBytes {
  raw: Buffer | string;
}

export function certificateFingerprintSha256(certificatePem: string): string {
  const certificate = new X509Certificate(certificatePem);
  return createHash('sha256').update(certificate.raw).digest('hex').toUpperCase();
}

export function verifyDevicePin(
  peer: PeerCertificateBytes,
  expectedCertificatePem: string,
  expectedFingerprintSha256: string,
): Error | undefined {
  try {
    const expectedCertificate = new X509Certificate(expectedCertificatePem);
    const expectedFingerprint = normalizeFingerprint(expectedFingerprintSha256);
    const configuredFingerprint = createHash('sha256').update(expectedCertificate.raw).digest('hex').toUpperCase();
    const peerFingerprint = createHash('sha256').update(peer.raw).digest('hex').toUpperCase();
    if (configuredFingerprint !== expectedFingerprint || peerFingerprint !== expectedFingerprint) {
      return new Error('device certificate SHA-256 pin mismatch');
    }
    // The trust anchor and certificate validity are still checked by TLS; the
    // exact certificate pin binds the phone identity where its cert has no DNS SAN.
    return undefined;
  } catch (error) {
    return error instanceof Error ? error : new Error('invalid device certificate pin');
  }
}

export function validateDevicePinConfiguration(certificatePem: string, fingerprintSha256: string): void {
  const normalized = normalizeFingerprint(fingerprintSha256);
  const certificate = new X509Certificate(certificatePem);
  if (certificateFingerprintSha256(certificatePem) !== normalized) {
    throw new Error('configured device certificate does not match its SHA-256 pin');
  }
  if (certificate.ca || Date.parse(certificate.validFrom) > Date.now() || Date.parse(certificate.validTo) <= Date.now()) {
    throw new Error('device certificate must be a currently valid leaf certificate');
  }
  if (!certificate.verify(certificate.publicKey)) throw new Error('device certificate must be self-signed');
}

function normalizeFingerprint(value: string): string {
  const normalized = value.replaceAll(':', '').toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(normalized)) throw new Error('device SHA-256 fingerprint must contain 32 bytes');
  return normalized;
}
