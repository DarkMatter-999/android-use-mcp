import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

interface PackageManifest {
  scripts?: Record<string, string>;
  bin?: Record<string, string>;
  exports?: { '.': { types?: string; import?: string } };
}

describe('built MCP package contract', () => {
  it('exposes a Node-loadable ESM CLI and runtime entrypoint with source types', async () => {
    const manifest = JSON.parse(
      await readFile(resolve(import.meta.dirname, '../package.json'), 'utf8'),
    ) as PackageManifest;

    expect(manifest.scripts?.['build']).toBe(`tsc -p tsconfig.build.json --noEmit false && node --input-type=module -e "import('node:fs').then(({ chmodSync }) => chmodSync('dist/src/cli.js', 0o755))"`);
    expect(manifest.bin?.['android-use-mcp']).toBe('./dist/src/cli.js');
    expect(manifest.exports?.['.']).toEqual({
      types: './src/index.ts',
      import: './dist/src/index.js',
    });
  });
});
