import { createHash } from 'node:crypto';
import { createServer } from 'node:https';
import { generate } from 'selfsigned';
import { WebSocket, WebSocketServer } from 'ws';
import { PROTOCOL_VERSION, serializeRpcMessage, type RpcRequest } from '@android-use/protocol';

export interface TestTlsServer {
  url: string;
  certificatePem: string;
  fingerprintSha256: string;
  received: RpcRequest[];
  close(): Promise<void>;
}

export const NO_RESPONSE = Symbol('NO_RESPONSE');
export const DROP_CONNECTION = Symbol('DROP_CONNECTION');

export async function startTestTlsServer(options: {
  requireClientCertificate?: boolean;
  clientCertificatePem?: string;
  tlsVersion?: 'TLSv1.2' | 'TLSv1.3';
  respond?: (request: RpcRequest) => unknown | typeof NO_RESPONSE | typeof DROP_CONNECTION;
  autoPong?: boolean;
} = {}): Promise<TestTlsServer> {
  const identity = await generate([{ name: 'commonName', value: 'AndroidUse test device' }], {
    keyType: 'ec',
    curve: 'P-256',
    algorithm: 'sha256',
    extensions: [
      { name: 'basicConstraints', cA: false },
      { name: 'keyUsage', digitalSignature: true },
      { name: 'extKeyUsage', serverAuth: true },
    ],
  });
  const received: RpcRequest[] = [];
  const httpsServer = createServer({
    cert: identity.cert,
    key: identity.private,
    minVersion: options.tlsVersion ?? 'TLSv1.3',
    maxVersion: options.tlsVersion ?? 'TLSv1.3',
    requestCert: options.requireClientCertificate ?? true,
    ...(options.requireClientCertificate && options.clientCertificatePem
      ? { ca: options.clientCertificatePem, rejectUnauthorized: true }
      : { rejectUnauthorized: false }),
  });
  const websocketServer = new WebSocketServer({ server: httpsServer, autoPong: options.autoPong ?? true });
  websocketServer.on('connection', (socket: WebSocket) => {
    socket.on('message', (data) => {
      const request = JSON.parse(data.toString()) as RpcRequest;
      received.push(request);
      const custom = options.respond?.(request);
      if (custom === NO_RESPONSE) return;
      if (custom === DROP_CONNECTION) {
        socket.terminate();
        return;
      }
      const response = custom && typeof custom === 'object' && 'error' in custom
        ? { protocolVersion: PROTOCOL_VERSION, requestId: request.requestId, ...custom }
        : custom !== undefined
          ? { protocolVersion: PROTOCOL_VERSION, requestId: request.requestId, result: custom }
          : {
              protocolVersion: PROTOCOL_VERSION,
              requestId: request.requestId,
              result: request.method === 'test.error' ? undefined : { method: request.method, params: request.params },
              error: request.method === 'test.error' ? { code: 'UNSUPPORTED', message: 'test remote error' } : undefined,
            };
      socket.send(serializeRpcMessage(response as Parameters<typeof serializeRpcMessage>[0]));
    });
  });
  await new Promise<void>((resolve, reject) => {
    httpsServer.once('error', reject);
    httpsServer.listen(0, '127.0.0.1', resolve);
  });
  const address = httpsServer.address();
  if (!address || typeof address === 'string') throw new Error('test TLS server did not bind TCP');
  const der = Buffer.from(identity.cert.replace(/-----[^-]+-----/g, '').replace(/\s/g, ''), 'base64');
  return {
    url: `wss://127.0.0.1:${address.port}/rpc`,
    certificatePem: identity.cert,
    fingerprintSha256: createHash('sha256').update(der).digest('hex').toUpperCase(),
    received,
    async close() {
      for (const client of websocketServer.clients) client.terminate();
      await new Promise<void>((resolve) => websocketServer.close(() => resolve()));
      await new Promise<void>((resolve) => httpsServer.close(() => resolve()));
    },
  };
}
