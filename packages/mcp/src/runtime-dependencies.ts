import { AndroidDevice } from '@android-use/client';
import { executeCode } from '@android-use/runtime';
import { PairingClient, RpcConnection, type RpcConnectionConfig } from '@android-use/transport';

import type { McpGestureInput, McpToolDependencies } from './tools.js';

export interface RuntimeDependencyFactories {
  connect(config: RpcConnectionConfig): Promise<RpcConnection>;
}

export function createRuntimeDependencies(
  config: RpcConnectionConfig,
  factories: RuntimeDependencyFactories = { connect: (value) => RpcConnection.connect(value) },
): McpToolDependencies {
  let connection: RpcConnection | undefined;
  let device: AndroidDevice | undefined;
  let connectionAttempt: Promise<AndroidDevice> | undefined;
  let unsubscribeFromConnection: (() => void) | undefined;

  const connectedDevice = async (): Promise<AndroidDevice> => {
    if (connection && device) return device;
    const attempt = connectionAttempt ?? (connectionAttempt = (async () => {
      const connected = await factories.connect(config);
      connection = connected;
      device = new AndroidDevice(connected);
      unsubscribeFromConnection = connected.onEvent((event) => {
        if (event.type === 'disconnected' || event.type === 'closed') {
          if (connection === connected) {
            unsubscribeFromConnection?.();
            unsubscribeFromConnection = undefined;
            connection = undefined;
            device = undefined;
          }
        }
      });
      return device;
    })());
    try {
      return await attempt;
    } finally {
      if (connectionAttempt === attempt) connectionAttempt = undefined;
    }
  };

  return {
    status: async () => (await connectedDevice()).status(),
    pair: async ({ code, hostName }) => {
      await connectedDevice();
      const result = await new PairingClient(connection!, config.hostIdentity).pair(code, hostName);
      return result;
    },
    snapshot: async ({ mode }) => (await connectedDevice()).snapshot({ mode }),
    execute: async (code, timeoutMs) => executeCode(code, { device: await connectedDevice(), timeoutMs }),
    disconnect: async () => {
      await connectionAttempt?.catch(() => undefined);
      unsubscribeFromConnection?.();
      unsubscribeFromConnection = undefined;
      connection?.close();
      connection = undefined;
      device = undefined;
      return { disconnected: true };
    },
    screenshot: async () => (await connectedDevice()).screenshot(),
    listApps: async () => (await connectedDevice()).listApps(),
    openApp: async (packageName, timeoutMs) => (await connectedDevice()).launchApp(packageName, { timeoutMs }),
    gesture: async (input: McpGestureInput) => {
      const device = await connectedDevice();
      if (!('x' in input)) {
        return device.performGlobalAction(input.action, { timeoutMs: input.timeoutMs });
      }
      return device.performGesture(input.action, input);
    },
  };
}
