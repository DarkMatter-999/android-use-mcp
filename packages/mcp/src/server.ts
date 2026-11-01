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
  register('android_execute', 'Run bounded JavaScript in an isolated runtime with the Android SDK bridge.', tools.android_execute, executeToolSchema);
  register('android_disconnect', 'Close the active Android RPC connection.', tools.android_disconnect, disconnectToolSchema);
  return server;
}

export async function runStdioServer(dependencies: McpToolDependencies): Promise<McpServer> {
  const server = createAndroidMcpServer(dependencies);
  await server.connect(new StdioServerTransport());
  return server;
}
