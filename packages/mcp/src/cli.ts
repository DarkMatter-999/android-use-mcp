#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { loadOrCreateHostIdentity, type HostIdentity, type RpcConnectionConfig } from '@android-use/transport';

import { parseCliConfig } from './cli-config.js';
import { runStdioServer } from './server.js';
import { createRuntimeDependencies } from './runtime-dependencies.js';
import type { McpToolDependencies } from './tools.js';

export interface CliServices {
  readCertificate(path: string): Promise<string>;
  loadIdentity(directory: string): Promise<HostIdentity>;
  createDependencies(config: RpcConnectionConfig): McpToolDependencies;
  runServer(dependencies: McpToolDependencies): Promise<{ close(): Promise<void> }>;
  writeStderr(message: string): void;
}

export interface CliSignalRegistrar {
  once(signal: 'SIGINT' | 'SIGTERM', listener: () => Promise<void>): unknown;
}

const defaultServices: CliServices = {
  readCertificate: (path) => readFile(path, 'utf8'),
  loadIdentity: loadOrCreateHostIdentity,
  createDependencies: createRuntimeDependencies,
  runServer: runStdioServer,
  writeStderr: (message) => { process.stderr.write(message); },
};

const processSignals: CliSignalRegistrar = {
  once: (signal, listener) => process.once(signal, listener),
};

export async function runCli(
  args: readonly string[],
  env: NodeJS.ProcessEnv,
  services: CliServices = defaultServices,
  signals: CliSignalRegistrar = processSignals,
): Promise<number> {
  try {
    const config = parseCliConfig(args, env, resolve(homedir(), '.config', 'android-use-mcp'));
    const [deviceCertificatePem, hostIdentity] = await Promise.all([
      services.readCertificate(config.certPath),
      services.loadIdentity(config.identityDir),
    ]);
    const rpcConfig: RpcConnectionConfig = {
      url: config.url,
      hostIdentity,
      deviceCertificatePem,
      deviceFingerprintSha256: config.pin,
    };
    const dependencies = services.createDependencies(rpcConfig);
    const server = await services.runServer(dependencies);

    let shutdownPromise: Promise<void> | undefined;
    const shutdown = (): Promise<void> => {
      if (shutdownPromise) return shutdownPromise;
      shutdownPromise = (async () => {
        try {
          await server.close();
        } finally {
          await dependencies.disconnect();
        }
      })().catch(() => undefined);
      return shutdownPromise;
    };
    signals.once('SIGINT', shutdown);
    signals.once('SIGTERM', shutdown);
    return 0;
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Startup failed';
    services.writeStderr(`android-use MCP: ${message}\n`);
    return 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  void runCli(process.argv.slice(2), process.env).then((exitCode) => {
    if (exitCode !== 0) process.exitCode = exitCode;
  });
}
