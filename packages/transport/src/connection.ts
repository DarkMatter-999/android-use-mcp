import { createHash, createPrivateKey, createPublicKey, randomUUID, X509Certificate } from 'node:crypto';
import { WebSocket, type ClientOptions } from 'ws';
import {
  PROTOCOL_VERSION,
  RPC_CANCEL_METHOD,
  parseRpcResponse,
  serializeRpcMessage,
  type RpcErrorCode,
  type RpcRequest,
  type RpcResponse,
} from '@android-use/protocol';
import type { HostIdentity } from './pairing-host-identity.js';
import { RemoteRpcError, RequestTimeoutError, TransportError } from './errors.js';
import { validateDevicePinConfiguration, verifyDevicePin } from './pinning.js';

export interface RpcConnectionConfig {
  url: string;
  hostIdentity: HostIdentity;
  deviceCertificatePem: string;
  deviceFingerprintSha256: string;
  requestTimeoutMs?: number;
  heartbeatIntervalMs?: number;
  idleTimeoutMs?: number;
}

export interface RpcRequestOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
  requestId?: string;
}

export type RpcConnectionEvent =
  | { type: 'connected' }
  | { type: 'disconnected'; reason: string }
  | { type: 'closed' };

interface PendingRequest {
  method: string;
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
  signal?: AbortSignal;
  abortHandler?: () => void;
  outcomeUnknown: boolean;
}

export class RpcConnection {
  private readonly pending = new Map<string, PendingRequest>();
  private readonly listeners = new Set<(event: RpcConnectionEvent) => void>();
  private heartbeat?: NodeJS.Timeout;
  private lastActivity = Date.now();
  private receivedPong = true;
  private state: 'open' | 'closed' = 'open';
  private explicitClose = false;

  private constructor(
    private readonly socket: WebSocket,
    private readonly config: RpcConnectionConfig,
  ) {
    this.socket.on('message', (data) => this.receive(data.toString()));
    this.socket.on('pong', () => {
      this.receivedPong = true;
    });
    this.socket.on('ping', () => { this.lastActivity = Date.now(); });
    this.socket.on('close', (_code, reason) => this.disconnected(reason.toString() || 'socket closed'));
    this.socket.on('error', (error) => {
      if (this.state === 'open') this.disconnected(error.message);
    });
    this.emit({ type: 'connected' });
    this.startHeartbeat();
  }

  static async connect(config: RpcConnectionConfig): Promise<RpcConnection> {
    const parsedUrl = validateConfig(config);
    const options: ClientOptions = {
      cert: config.hostIdentity.certificatePem,
      key: config.hostIdentity.privateKeyPem,
      ca: config.deviceCertificatePem,
      rejectUnauthorized: true,
      minVersion: 'TLSv1.3',
      maxVersion: 'TLSv1.3',
      perMessageDeflate: false,
      maxPayload: 2 * 1024 * 1024,
      handshakeTimeout: 10_000,
      checkServerIdentity: (( _hostname: string, certificate: unknown) =>
        verifyDevicePin(
          certificate as { raw: Buffer | string },
          config.deviceCertificatePem,
          config.deviceFingerprintSha256,
        )) as unknown as NonNullable<ClientOptions['checkServerIdentity']>,
    };
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(parsedUrl, options);
      const fail = (error: Error) => {
        socket.removeAllListeners();
        socket.terminate();
        reject(new TransportError('secure WebSocket connection failed', { cause: error }));
      };
      socket.once('open', () => {
        socket.removeListener('error', fail);
        resolve(new RpcConnection(socket, config));
      });
      socket.once('error', fail);
    });
  }

  request<T = unknown>(method: string, params: Record<string, unknown>, options: RpcRequestOptions = {}): Promise<T> {
    if (this.state !== 'open' || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new TransportError('RPC connection is closed'));
    }
    if (options.signal?.aborted) return Promise.reject(abortError());
    const requestId = options.requestId ?? randomUUID();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
      return Promise.reject(new TypeError('requestId must be a UUID'));
    }
    if (this.pending.has(requestId)) return Promise.reject(new TypeError('requestId is already in flight'));
    const timeoutMs = options.timeoutMs ?? this.config.requestTimeoutMs ?? 30_000;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
      return Promise.reject(new RangeError('timeoutMs must be a positive safe integer'));
    }
    const request: RpcRequest = { protocolVersion: PROTOCOL_VERSION, requestId, method, params };
    let serialized: string;
    try {
      serialized = serializeRpcMessage(request);
    } catch (error) {
      return Promise.reject(error);
    }
    return new Promise<T>((resolve, reject) => {
      const pending: PendingRequest = {
        method,
        resolve: (value) => resolve(value as T),
        reject,
        timer: setTimeout(() => {
          this.pending.delete(requestId);
          this.cleanupPending(pending);
          this.sendCancel(requestId);
          reject(new RequestTimeoutError(requestId, isActionMethod(method)));
        }, timeoutMs),
        ...(options.signal ? { signal: options.signal } : {}),
        outcomeUnknown: isActionMethod(method),
      };
      pending.timer.unref?.();
      if (options.signal) {
        pending.abortHandler = () => {
          if (!this.pending.delete(requestId)) return;
          this.cleanupPending(pending);
          this.sendCancel(requestId);
          reject(abortError());
        };
        options.signal.addEventListener('abort', pending.abortHandler, { once: true });
      }
      this.pending.set(requestId, pending);
      if (options.signal?.aborted) {
        pending.abortHandler?.();
        return;
      }
      try {
        this.lastActivity = Date.now();
        this.socket.send(serialized, (error) => {
          if (!error) return;
          const current = this.pending.get(requestId);
          if (!current) return;
          this.pending.delete(requestId);
          this.cleanupPending(current);
          reject(new TransportError('failed to send RPC request', {
            cause: error,
            outcomeUnknown: current.outcomeUnknown,
          }));
        });
      } catch (error) {
        this.pending.delete(requestId);
        this.cleanupPending(pending);
        reject(new TransportError('failed to send RPC request', {
          cause: error,
          outcomeUnknown: pending.outcomeUnknown,
        }));
      }
    });
  }

  cancel(requestId: string): void {
    const pending = this.pending.get(requestId);
    if (!pending) return;
    this.pending.delete(requestId);
    this.cleanupPending(pending);
    this.sendCancel(requestId);
    pending.reject(new TransportError('RPC request was cancelled', {
      outcomeUnknown: pending.outcomeUnknown,
    }));
  }

  onEvent(callback: (event: RpcConnectionEvent) => void): () => void {
    this.listeners.add(callback);
    if (this.state === 'open') callback({ type: 'connected' });
    return () => this.listeners.delete(callback);
  }

  close(): void {
    if (this.state !== 'open') return;
    this.explicitClose = true;
    this.state = 'closed';
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.rejectPending(new TransportError('RPC connection was closed'));
    this.emit({ type: 'closed' });
    if (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING) {
      this.socket.close(1000, 'client closed');
    }
  }

  private receive(text: string): void {
    this.lastActivity = Date.now();
    let response: RpcResponse;
    try {
      response = parseRpcResponse(text);
    } catch (error) {
      this.disconnected('invalid RPC response');
      this.socket.terminate();
      return;
    }
    const pending = this.pending.get(response.requestId);
    if (!pending) return;
    this.pending.delete(response.requestId);
    this.cleanupPending(pending);
    if (response.error) {
      pending.reject(new RemoteRpcError(
        response.error.code as RpcErrorCode,
        response.error.message,
        response.error.details,
      ));
    } else {
      pending.resolve(response.result);
    }
  }

  private sendCancel(cancelRequestId: string): void {
    if (this.socket.readyState !== WebSocket.OPEN) return;
    const request: RpcRequest = {
      protocolVersion: PROTOCOL_VERSION,
      requestId: randomUUID(),
      method: RPC_CANCEL_METHOD,
      params: { cancelRequestId },
    };
    try {
      this.socket.send(serializeRpcMessage(request));
    } catch {
      // Cancellation is best-effort; the original request's outcome remains unknown.
    }
  }

  private startHeartbeat(): void {
    const interval = this.config.heartbeatIntervalMs ?? 15_000;
    const idleTimeout = this.config.idleTimeoutMs ?? 60_000;
    if (interval <= 0 || idleTimeout <= interval) throw new RangeError('invalid heartbeat/idle timeout configuration');
    this.heartbeat = setInterval(() => {
      if (this.state !== 'open') return;
      if (!this.receivedPong || Date.now() - this.lastActivity > idleTimeout) {
        this.disconnected('heartbeat or idle timeout');
        this.socket.terminate();
        return;
      }
      this.receivedPong = false;
      this.socket.ping();
    }, interval);
    this.heartbeat.unref?.();
  }

  private disconnected(reason: string): void {
    if (this.state !== 'open') return;
    this.state = 'closed';
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.rejectPending(new TransportError('RPC connection disconnected', {
      outcomeUnknown: [...this.pending.values()].some((request) => request.outcomeUnknown),
    }));
    if (!this.explicitClose) this.emit({ type: 'disconnected', reason });
  }

  private rejectPending(error: TransportError): void {
    for (const pending of this.pending.values()) {
      this.cleanupPending(pending);
      pending.reject(new TransportError(error.message, {
        cause: error,
        outcomeUnknown: pending.outcomeUnknown,
      }));
    }
    this.pending.clear();
  }

  private cleanupPending(pending: PendingRequest): void {
    clearTimeout(pending.timer);
    if (pending.signal && pending.abortHandler) {
      pending.signal.removeEventListener('abort', pending.abortHandler);
    }
  }

  private emit(event: RpcConnectionEvent): void {
    for (const listener of this.listeners) {
      try { listener(event); } catch { /* Event subscribers cannot disrupt transport state. */ }
    }
  }
}

function validateConfig(config: RpcConnectionConfig): URL {
  const url = new URL(config.url);
  if (url.protocol !== 'wss:') throw new Error('RPC connection requires wss://');
  if (!url.hostname || url.username || url.password) throw new Error('invalid secure RPC endpoint');
  if (!config.hostIdentity?.certificatePem || !config.hostIdentity.privateKeyPem) {
    throw new Error('host client certificate and private key are required');
  }
  const hostCertificate = new X509Certificate(config.hostIdentity.certificatePem);
  const hostKey = createPrivateKey(config.hostIdentity.privateKeyPem);
  if (hostCertificate.ca || !hostCertificate.keyUsage.includes('1.3.6.1.5.5.7.3.2')) {
    throw new Error('host identity certificate must be a client-auth leaf');
  }
  if (!hostCertificate.subjectAltName?.includes(`URI:urn:android-use:host:${config.hostIdentity.hostId}`)) {
    throw new Error('host identity certificate is not bound to its host id');
  }
  if (Date.parse(hostCertificate.validFrom) > Date.now() || Date.parse(hostCertificate.validTo) <= Date.now()) {
    throw new Error('host identity certificate is not currently valid');
  }
  if (!hostCertificate.verify(hostCertificate.publicKey)) throw new Error('host identity certificate signature is invalid');
  if (createHash('sha256').update(hostCertificate.raw).digest('hex').toUpperCase() !==
    config.hostIdentity.fingerprintSha256.replaceAll(':', '').toUpperCase()) {
    throw new Error('host identity certificate does not match its fingerprint');
  }
  if (!hostCertificate.publicKey.export({ type: 'spki', format: 'der' }).equals(
    createPublicKey(hostKey).export({ type: 'spki', format: 'der' }),
  )) throw new Error('host client key does not match its certificate');
  validateDevicePinConfiguration(config.deviceCertificatePem, config.deviceFingerprintSha256);
  const heartbeat = config.heartbeatIntervalMs ?? 15_000;
  const idleTimeout = config.idleTimeoutMs ?? 60_000;
  if (!Number.isSafeInteger(heartbeat) || !Number.isSafeInteger(idleTimeout) || heartbeat <= 0 || idleTimeout <= heartbeat) {
    throw new RangeError('invalid heartbeat/idle timeout configuration');
  }
  return url;
}
function abortError(): Error {
  const error = new Error('RPC request was aborted');
  error.name = 'AbortError';
  return error;
}
function isActionMethod(method: string): boolean {
  return method === 'element.action' || method === 'gesture.perform';
}
