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
 * The layer boundaries of eslint.config.js fire: each forbidden edge is linted as a file in the layer it starts
 * from, and each allowed type-only edge passes. A rule that matched nothing
 * would let every violation through silently; this is what catches that.
 */

import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

const cwd = fileURLToPath(new URL('../', import.meta.url));
const eslint = new ESLint({ cwd });
const RULE = '@typescript-eslint/no-restricted-imports';

async function restricted(file: string, code: string): Promise<boolean> {
  const [result] = await eslint.lintText(code, { filePath: `${cwd}${file}` });
  return result.messages.some(message => message.ruleId === RULE);
}

const FORBIDDEN: [from: string, source: string][] = [
  ['src/dsl/probe.ts', '../commands.js'],
  ['src/dsl/filter/probe.ts', '../../http.js'],
  ['src/dsl/probe.ts', '../index.js'],
  ['src/dsl/probe.ts', '@ahoo-wang/fetcher'],
  ['src/dsl/filter/probe.ts', '@ahoo-wang/fetcher-eventstream'],
  ['src/dsl/probe.ts', 'reflect-metadata'],
  ['src/probe.ts', './dsl/filter/index.js'],
  ['src/probe.ts', './dsl.js'],
  ['src/probe.ts', '@ahoo-wang/fetcher-decorator'],
  ['src/probe.ts', 'reflect-metadata'],
];

const ALLOWED: [from: string, code: string][] = [
  ['src/dsl/probe.ts', "import { DeletionState } from './deletionState.js';"],
  ['src/dsl/filter/probe.ts', "import type { FieldSort } from '../sort.js';"],
  [
    'src/probe.ts',
    "import type { FilterExpression } from './dsl/filter/index.js';",
  ],
  ['src/probe.ts', "import { readProblem } from './problem.js';"],
  ['src/probe.ts', "import { Fetcher } from '@ahoo-wang/fetcher';"],
  [
    'src/probe.ts',
    "import { toServerSentEventStream } from '@ahoo-wang/fetcher-eventstream';",
  ],
  ['src/index.ts', "export * from './wire.js';"],
  ['src/dsl.ts', "export * from './dsl/filter/index.js';"],
];

describe('layer boundaries', () => {
  it.each(FORBIDDEN)('%s may not import %s', async (file, source) => {
    expect(await restricted(file, `import { x } from '${source}';`)).toBe(true);
  });

  it.each(ALLOWED)('%s may: %s', async (file, code) => {
    expect(await restricted(file, code)).toBe(false);
  });

  it('holds the current source', async () => {
    const results = await eslint.lintFiles(['src/**/*.ts']);
    const violations = results.flatMap(result =>
      result.messages
        .filter(message => message.ruleId === RULE)
        .map(message => `${result.filePath}: ${message.message}`),
    );
    expect(violations).toEqual([]);
  });
});
