/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Round two of the deep review, security: a Manifest's `$defs` key reaches the
 * generated client's source. Every description goes through `escapeJsDoc`,
 * but the comment of a recursive type's exact numbers (`textNumbers.ts`)
 * writes the key as it is, so a key holding a comment terminator ends the
 * comment and the rest of the key is code in the generated client.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { generateCold, removeDirectories } from '../support/generation';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const KEY = 'shop.Tree*/ export const injected = 1; /*';
const ref = { $ref: `#/$defs/${KEY}` };

const MANIFEST = {
  format: 1,
  contexts: [
    {
      name: 'shop',
      aggregates: [{ name: 'tree', state: { schema: `#/$defs/${KEY}` } }],
    },
  ],
  $defs: {
    [KEY]: {
      type: 'object',
      required: ['amount', 'children'],
      properties: {
        amount: { type: 'integer', format: 'int64' },
        children: { type: 'array', items: ref },
      },
    },
  },
};

describe('a Manifest key in the generated source', () => {
  it('never ends the comment it is written into', async () => {
    const result = await generateCold(MANIFEST, directories);
    // A key the generator refuses is answered with a specification error, and nothing is written.
    if (result.exitCode !== 0) {
      expect(result.errors).toHaveLength(1);
      return;
    }
    const text = readFileSync(
      join(result.output, 'shop/textNumbers.ts'),
      'utf8',
    );
    expect(text).not.toContain('*/ export const injected = 1;');
  });
});
