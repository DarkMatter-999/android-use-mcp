import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { describe, expect, it, vi } from 'vitest';

describe('MCP usage examples', () => {
  it('provides valid Claude Code and OpenCode local stdio configurations without secrets', async () => {
    const repositoryRoot = resolve(import.meta.dirname, '../../..');
    const claude = JSON.parse(await readFile(resolve(repositoryRoot, 'examples/mcp-config/claude-code.json'), 'utf8'));
    const opencode = JSON.parse(await readFile(resolve(repositoryRoot, 'examples/mcp-config/opencode.json'), 'utf8'));
    const claudeServer = claude.mcpServers['android-use'];
    const openCodeServer = opencode.mcp.servers['android-use'];

    expect(claudeServer).toMatchObject({ command: 'node' });
    expect(claudeServer.args).toContain('--device-certificate-pin');
    expect(claudeServer.args).toContain('<sha256-certificate-pin>');
    expect(openCodeServer).toMatchObject({ type: 'local', enabled: true });
    expect(openCodeServer.command).toContain('./packages/mcp/dist/src/cli.js');
    expect(JSON.stringify([claude, opencode])).not.toMatch(/-----BEGIN (?:RSA )?PRIVATE KEY-----/);
  });

  it('submits one batched snapshot and lazy action through android_execute', async () => {
    const callTool = vi.fn(async () => ({ content: [{ type: 'text', text: 'ok' }] }));
    const examplePath = resolve(import.meta.dirname, '../../../examples/basic-automation/index.ts');
    const { runBasicAutomation } = await import(pathToFileURL(examplePath).href);
    await runBasicAutomation({ callTool });

    expect(callTool).toHaveBeenCalledTimes(1);
    expect(callTool).toHaveBeenCalledWith({
      name: 'android_execute',
      arguments: {
        timeoutMs: 1_000,
        code: `(async () => {
  const snapshot = await android.snapshot();
  const action = await android.getByText('Continue', { exact: true }).click();
  return { revision: snapshot.revision, action };
})()`,
      },
    });
  });
});
