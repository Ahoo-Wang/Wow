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
 * The layer boundaries of eslint.config.js (docs/design/refactor-2026-09.md
 * §3.2) fire: each forbidden edge is linted as a file in the layer it starts
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
  ['src/dsl/probe.ts', '../client/command/commandClient.js'],
  ['src/dsl/probe.ts', '../transport/endpoints.js'],
  ['src/dsl/probe.ts', '../error/wowError.js'],
  ['src/dsl/probe.ts', '../legacy/condition.js'],
  ['src/dsl/probe.ts', '@ahoo-wang/fetcher'],
  ['src/dsl/filter/probe.ts', '@ahoo-wang/fetcher-decorator'],
  ['src/dsl/probe.ts', 'reflect-metadata'],
  ['src/model/probe.ts', '../client/command/commandRequest.js'],
  ['src/model/probe.ts', '../transport/eventStreams.js'],
  ['src/model/probe.ts', '../dsl/filter/index.js'],
  ['src/model/probe.ts', '../error/wowError.js'],
  ['src/model/probe.ts', '@ahoo-wang/fetcher-eventstream'],
  ['src/error/probe.ts', '../client/command/commandClient.js'],
  ['src/error/probe.ts', '../transport/endpoints.js'],
  ['src/error/probe.ts', '../model/common.js'],
  ['src/error/probe.ts', '../dsl/documents.js'],
  ['src/error/probe.ts', '@ahoo-wang/fetcher'],
  ['src/transport/probe.ts', '../client/command/types.js'],
  ['src/transport/probe.ts', '../dsl/filter/index.js'],
  ['src/transport/probe.ts', '../legacy/condition.js'],
  ['src/transport/probe.ts', '@ahoo-wang/fetcher-decorator'],
  ['src/client/query/probe.ts', '../../legacy/condition.js'],
  ['src/client/command/probe.ts', '@ahoo-wang/fetcher-eventstream'],
  ['src/client/query/probe.ts', '@ahoo-wang/fetcher-eventstream'],
  ['src/client/query/requests.ts', '../../legacy/condition.js'],
  ['src/legacy/probe.ts', '../client/command/commandClient.js'],
  ['src/legacy/probe.ts', '../transport/endpoints.js'],
  ['src/legacy/probe.ts', '../model/common.js'],
  ['src/legacy/probe.ts', '../error/wowError.js'],
  ['src/legacy/probe.ts', '@ahoo-wang/fetcher'],
];

const ALLOWED: [from: string, code: string][] = [
  ['src/dsl/probe.ts', "import { DeletionState } from './deletionState.js';"],
  ['src/model/probe.ts', "import type { ErrorInfo } from '../error/index.js';"],
  ['src/transport/probe.ts', "import { WowError } from '../error/index.js';"],
  [
    'src/transport/probe.ts',
    "import { CommandStage } from '../model/index.js';",
  ],
  [
    'src/transport/probe.ts',
    "import { JsonEventStreamResultExtractor } from '@ahoo-wang/fetcher-eventstream';",
  ],
  ['src/client/probe.ts', "import { filter } from '../dsl/filter/index.js';"],
  [
    'src/client/probe.ts',
    "import { COMMAND_STREAM_ENDPOINT } from '../transport/endpoints.js';",
  ],
  [
    'src/client/query/requests.ts',
    "import type { Condition } from '../../legacy/condition.js';",
  ],
  [
    'src/legacy/probe.ts',
    "import { DeletionState } from '../dsl/deletionState.js';",
  ],
  ['src/legacy/index.ts', "export * from '../client/query/requests.js';"],
  ['src/index.ts', "export * from './client/command/index.js';"],
];

describe('layer boundaries', () => {
  it.each(FORBIDDEN)('%s may not import %s', async (file, source) => {
    expect(await restricted(file, `import { x } from '${source}';`)).toBe(true);
  });

  it('keeps fetcher-eventstream out of client/, types included', async () => {
    expect(
      await restricted(
        'src/client/probe.ts',
        "import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';",
      ),
    ).toBe(true);
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
