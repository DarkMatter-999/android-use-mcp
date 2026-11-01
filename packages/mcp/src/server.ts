import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

import {
  createToolHandlers,
  disconnectToolSchema,
  executeToolSchema,
  formatToolError,
  pairToolSchema,
  snapshotToolSchema,
  statusToolSchema,
  screenshotToolSchema,
  openAppToolSchema,
  gestureToolSchema,
  type McpToolDependencies,
} from './tools.js';

export function createAndroidMcpServer(dependencies: McpToolDependencies): McpServer {
  const server = new McpServer({ name: 'android-use', version: '0.1.0' });
  const tools = createToolHandlers(dependencies);
  const register = (
    name: string,
    description: string,
    handler: (input: unknown) => Promise<unknown>,
    inputSchema: z.ZodType,
  ) => {
    server.registerTool(name, { description, inputSchema: inputSchema as never }, (async (input: unknown) => {
      try {
        const result = await handler(input ?? {});
        const serialized = JSON.stringify(result);
        if (serialized === undefined) throw new Error('Tool returned a non-JSON value');
        const safeResult: unknown = JSON.parse(serialized);
        return {
          content: [{ type: 'text' as const, text: serialized }],
          structuredContent: { result: safeResult },
        };
      } catch (error) {
        return { isError: true, content: [{ type: 'text' as const, text: formatToolError(error) }] };
      }
    }) as never);
  };

  register('android_status', 'Report Android device and session status.', tools.android_status, statusToolSchema);
  register('android_pair', 'Pair this host using the displayed code; device-side approval is required.', tools.android_pair, pairToolSchema);
  register('android_snapshot', 'Read a bounded accessibility snapshot of the current Android window.', tools.android_snapshot, snapshotToolSchema);
  server.registerTool('android_screenshot', {
    description: 'Capture the current Android screen through the accessibility screenshot API.',
    inputSchema: screenshotToolSchema as never,
  }, (async (input: unknown) => {
    try {
      const screenshot = await tools.android_screenshot(input ?? {});
      const metadata = {
        mimeType: screenshot.mimeType,
        width: screenshot.width,
        height: screenshot.height,
        timestampMillis: screenshot.timestampMillis,
        deviceId: screenshot.deviceId,
        windowId: screenshot.windowId,
      };
      return {
        content: [
          { type: 'image' as const, data: Buffer.from(screenshot.bytes).toString('base64'), mimeType: screenshot.mimeType },
          { type: 'text' as const, text: JSON.stringify(metadata) },
        ],
        structuredContent: metadata,
      };
    } catch (error) {
      return { isError: true, content: [{ type: 'text' as const, text: formatToolError(error) }] };
    }
  }) as never);
  register('android_open_app', 'Launch a phone-allowlisted Android app.', tools.android_open_app, openAppToolSchema);
  register('android_gesture', 'Perform a bounded coordinate gesture or supported global action.', tools.android_gesture, gestureToolSchema);
  register('android_execute', 'Run bounded JavaScript in an isolated runtime with the Android SDK bridge.', tools.android_execute, executeToolSchema);
  register('android_disconnect', 'Close the active Android RPC connection.', tools.android_disconnect, disconnectToolSchema);
  return server;
}

export async function runStdioServer(dependencies: McpToolDependencies): Promise<McpServer> {
  const server = createAndroidMcpServer(dependencies);
  await server.connect(new StdioServerTransport());
  return server;
}
