import type { RpcConnection } from './connection.js';
import type { HostIdentity } from './pairing-host-identity.js';

export interface PairingOptions {
  signal?: AbortSignal;
  pollIntervalMs?: number;
  timeoutMs?: number;
}

export interface ApprovedHost {
  hostId: string;
  hostName: string;
  fingerprintSha256: string;
}

export class PairingRejectedError extends Error {
  readonly status: 'REJECTED' | 'EXPIRED';

  constructor(status: 'REJECTED' | 'EXPIRED') {
    super(`phone ${status.toLowerCase()} the pairing request`);
    this.name = 'PairingRejectedError';
    this.status = status;
  }
}

interface PairingReply {
  challengeId: string;
  status: 'WAITING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
}

interface PairingStatusReply {
  pairing?: {
    challengeId: string;
    status: PairingReply['status'];
  };
}

export class PairingClient {
  constructor(
    private readonly connection: Pick<RpcConnection, 'request'>,
    private readonly hostIdentity: HostIdentity,
  ) {}

  async pair(code: string, hostName: string, options: PairingOptions = {}): Promise<ApprovedHost> {
    validatePairingCode(code);
    const normalizedName = validateHostName(hostName);
    const pollIntervalMs = options.pollIntervalMs ?? 500;
    const timeoutMs = options.timeoutMs ?? 5 * 60 * 1000;
    if (!Number.isSafeInteger(pollIntervalMs) || pollIntervalMs <= 0 ||
      !Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
      throw new RangeError('pairing poll interval and timeout must be positive safe integers');
    }
    throwIfAborted(options.signal);

    const submitted = await this.connection.request<PairingReply>('pairing.begin', {
      code,
      hostId: this.hostIdentity.hostId,
      hostName: normalizedName,
    }, { timeoutMs, ...(options.signal ? { signal: options.signal } : {}) });
    validatePairingReply(submitted);
    if (submitted.status === 'APPROVED') return this.approvedHost(normalizedName);
    if (submitted.status === 'REJECTED' || submitted.status === 'EXPIRED') {
      throw new PairingRejectedError(submitted.status);
    }

    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await delay(Math.min(pollIntervalMs, deadline - Date.now()), options.signal);
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      const response = await this.connection.request<PairingStatusReply>('device.status', {
        pairingChallengeId: submitted.challengeId,
      }, {
        timeoutMs: Math.min(timeoutMs, remaining),
        ...(options.signal ? { signal: options.signal } : {}),
      });
      const status = response.pairing;
      if (!status || status.challengeId !== submitted.challengeId || !isPairingStatus(status.status)) {
        throw new Error('device.status returned an invalid pairing status response');
      }
      if (status.status === 'APPROVED') return this.approvedHost(normalizedName);
      if (status.status === 'REJECTED' || status.status === 'EXPIRED') {
        throw new PairingRejectedError(status.status);
      }
    }
    throw new Error('phone approval timed out; pairing code was not resubmitted');
  }

  private approvedHost(hostName: string): ApprovedHost {
    return {
      hostId: this.hostIdentity.hostId,
      hostName,
      fingerprintSha256: this.hostIdentity.fingerprintSha256,
    };
  }
}

export function validatePairingCode(code: string): void {
  if (!/^[A-HJ-NP-Z2-9]{8}$/.test(code)) {
    throw new Error('pairing code must be eight unambiguous uppercase characters');
  }
}

function validateHostName(hostName: string): string {
  const normalized = hostName.trim();
  if (!normalized || normalized.length > 80 || /[\u0000-\u001f\u007f]/.test(normalized)) {
    throw new Error('host name must be 1-80 printable characters');
  }
  return normalized;
}

function validatePairingReply(value: PairingReply): void {
  if (!value || typeof value.challengeId !== 'string' || !value.challengeId || !isPairingStatus(value.status)) {
    throw new Error('pairing.begin returned an invalid pairing response');
  }
}

function isPairingStatus(value: unknown): value is PairingReply['status'] {
  return value === 'WAITING' || value === 'APPROVED' || value === 'REJECTED' || value === 'EXPIRED';
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    const error = new Error('pairing was cancelled');
    error.name = 'AbortError';
    throw error;
  }
}

function delay(milliseconds: number, signal?: AbortSignal): Promise<void> {
  throwIfAborted(signal);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, milliseconds);
    timer.unref?.();
    const abort = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      const error = new Error('pairing was cancelled');
      error.name = 'AbortError';
      reject(error);
    };
    signal?.addEventListener('abort', abort, { once: true });
  });
}
