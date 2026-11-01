export interface McpToolCaller {
  callTool(request: { name: 'android_execute'; arguments: { code: string; timeoutMs: number } }): Promise<unknown>;
}

/** One MCP call batches a snapshot read and a lazy semantic click in the isolate. */
export async function runBasicAutomation(client: McpToolCaller): Promise<unknown> {
  const code = `(async () => {
  const snapshot = await android.snapshot();
  const action = await android.getByText('Continue', { exact: true }).click();
  return { revision: snapshot.revision, action };
})()`;

  return client.callTool({
    name: 'android_execute',
    arguments: { code, timeoutMs: 1_000 },
  });
}
