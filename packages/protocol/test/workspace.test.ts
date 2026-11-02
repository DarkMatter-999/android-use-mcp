import { describe, expect, it } from 'vitest';

import { PROTOCOL_VERSION } from '../src/index.js';

describe('workspace', () => {
  it('exports the frozen protocol version', () => {
    expect(PROTOCOL_VERSION).toBe(2);
  });
});
