import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { describe, expect, it, vi } from 'vitest';

import { createAndroidMcpServer } from '../src/server.js';
import { createAndroidMcpServer as createPublicServer } from '../src/index.js';
import type { McpToolDependencies } from '../src/tools.js';

function fakeDependencies(overrides: Partial<McpToolDependencies> = {}): McpToolDependencies {
  return {
    status: vi.fn(async () => ({ sessionActive: false, revision: 3 })),
    pair: vi.fn(async ({ hostName }) => ({ hostName, paired: true })),
    snapshot: vi.fn(async ({ mode }) => ({ mode, revision: 3, elements: [] })),
    execute: vi.fn(async () => ({ result: 4, logs: [], actions: [], durationMs: 1 })),
    disconnect: vi.fn(async () => ({ disconnected: true })),
    ...overrides,
  };
}

describe('Android MCP server', () => {
  it('initializes, lists exactly the core tools, delegates a call, and shuts down', async () => {
    expect(createPublicServer).toBe(createAndroidMcpServer);
    const dependencies = fakeDependencies();
    const server = createAndroidMcpServer(dependencies);
    const client = new Client({ name: 'test-client', version: '1.0.0' });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

    await server.connect(serverTransport);
    await client.connect(clientTransport);
    expect(client.getServerVersion()).toMatchObject({ name: 'android-use', version: '0.1.0' });
    const listed = await client.listTools();
    expect(listed.tools.map(({ name }) => name)).toEqual([
      'android_status', 'android_pair', 'android_snapshot', 'android_execute', 'android_disconnect',
    ]);
    expect(listed.tools.find(({ name }) => name === 'android_pair')?.inputSchema.required)
      .toEqual(['code', 'hostName']);
    expect(listed.tools.find(({ name }) => name === 'android_execute')?.inputSchema.properties)
      .toMatchObject({ code: { maxLength: 100_000 }, timeoutMs: { minimum: 50, maximum: 60_000 } });

    const result = await client.callTool({ name: 'android_status', arguments: {} });
    expect(result).toMatchObject({
      structuredContent: { result: { sessionActive: false, revision: 3 } },
      content: [{ type: 'text', text: '{"sessionActive":false,"revision":3}' }],
    });
    expect(dependencies.status).toHaveBeenCalledTimes(1);

    await client.close();
    await server.close();
  });

  it('returns safe MCP errors instead of leaking dependency exceptions', async () => {
    const dependencies = fakeDependencies({
      status: vi.fn(async () => { throw Object.assign(new Error('secret socket path'), { code: 'DISCONNECTED' }); }),
    });
    const server = createAndroidMcpServer(dependencies);
    const client = new Client({ name: 'test-client', version: '1.0.0' });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: 'android_status', arguments: {} });

    expect(result.isError).toBe(true);
    expect(result.content).toEqual([{
      type: 'text',
      text: 'The Android device is disconnected. Check the configured connection and retry.',
    }]);
    expect(JSON.stringify(result)).not.toContain('secret socket path');

    await client.close();
    await server.close();
  });

  it('returns malformed tool input as an MCP error without invoking device dependencies', async () => {
    const dependencies = fakeDependencies();
    const server = createAndroidMcpServer(dependencies);
    const client = new Client({ name: 'test-client', version: '1.0.0' });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const result = await client.callTool({ name: 'android_pair', arguments: { code: 'bad', hostName: 'OpenCode' } });

    expect(result.isError).toBe(true);
    expect(result.content).toMatchObject([{ type: 'text', text: expect.stringMatching(/input validation error/i) }]);
    expect(dependencies.pair).not.toHaveBeenCalled();

    await client.close();
    await server.close();
  });
});
