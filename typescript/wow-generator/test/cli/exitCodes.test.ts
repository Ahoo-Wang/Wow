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

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import { runGenerate } from '../../src/cli/runGenerate';
import {
  generateCold,
  recordingLogger,
  removeDirectories,
} from '../support/generation';
import { document } from '../support/specs';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

/** A document whose one operation answers with the given schema. */
function answering(schema: unknown, schemas: Record<string, unknown>) {
  return document(
    {
      '/items': {
        get: {
          tags: ['Items'],
          operationId: 'items',
          responses: {
            '200': {
              description: 'ok',
              content: { 'application/json': { schema } },
            },
          },
        },
      },
    },
    schemas,
  );
}

/** Runs the CLI again on the output directory of an earlier run. */
async function regenerate(input: string, output: string) {
  const logger = recordingLogger();
  const errors: string[] = [];
  logger.error = (message: string) => {
    errors.push(message);
  };
  const exitCode = await runGenerate({ input, output }, logger);
  return { exitCode, errors };
}

describe('a document the generator cannot turn into code', () => {
  it('fails with the specification exit code on a cyclic reference', async () => {
    const result = await generateCold(
      answering(
        { $ref: '#/components/schemas/A' },
        {
          A: { $ref: '#/components/schemas/B' },
          B: { $ref: '#/components/schemas/A' },
        },
      ),
      directories,
    );

    expect(result.exitCode).toBe(EXIT_CODES.specification);
    expect(result.errors).toEqual([
      expect.stringMatching(
        /^Cyclic component reference: #\/components\/schemas\/[AB]$/,
      ),
    ]);
  });

  it('fails with the specification exit code on an external reference', async () => {
    const result = await generateCold(
      answering(
        { $ref: '#/components/schemas/Alias' },
        { Alias: { $ref: 'common.yaml#/components/schemas/User' } },
      ),
      directories,
    );

    expect(result.exitCode).toBe(EXIT_CODES.specification);
    expect(result.errors).toEqual([
      'Unsupported schema reference: common.yaml#/components/schemas/User. Bundle or inline external schemas before generation.',
    ]);
  });
});

describe('an output directory the generator cannot write as asked', () => {
  const spec = answering(
    { $ref: '#/components/schemas/Item' },
    { Item: { type: 'object', properties: { id: { type: 'string' } } } },
  );

  it('fails with the output exit code when the manifest is not JSON', async () => {
    const first = await generateCold(spec, directories);
    expect(first.exitCode).toBe(EXIT_CODES.success);
    const manifest = join(first.output, '.wow-generator.json');
    writeFileSync(manifest, '{ not json');

    const result = await regenerate(
      join(first.dir, 'openapi.json'),
      first.output,
    );

    expect(result.exitCode).toBe(EXIT_CODES.output);
    expect(result.errors).toEqual([
      expect.stringMatching(
        /^Cannot parse the generation manifest .+ Resolve the merge conflict in it if it has one, or delete it; without it this run cannot remove the stale files of the last run\.$/,
      ),
    ]);
  });

  it('fails with the output exit code, asking for an upgrade, when a newer generator wrote the manifest', async () => {
    const first = await generateCold(spec, directories);
    const manifest = join(first.output, '.wow-generator.json');
    writeFileSync(manifest, JSON.stringify({ version: 2, files: {} }));

    const result = await regenerate(
      join(first.dir, 'openapi.json'),
      first.output,
    );

    expect(result.exitCode).toBe(EXIT_CODES.output);
    expect(result.errors).toEqual([
      `The generation manifest ${manifest} was written by a newer wow-generator (manifest version 2); this one reads version 1. Upgrade wow-generator.`,
    ]);
  });

  it('fails with the output exit code when the manifest names a path outside the output', async () => {
    const first = await generateCold(spec, directories);
    const manifest = join(first.output, '.wow-generator.json');
    const recorded = JSON.parse(readFileSync(manifest, 'utf8'));
    recorded.files['../outside.ts'] = Object.values(recorded.files)[0];
    writeFileSync(manifest, JSON.stringify(recorded));

    const result = await regenerate(
      join(first.dir, 'openapi.json'),
      first.output,
    );

    expect(result.exitCode).toBe(EXIT_CODES.output);
    expect(result.errors).toEqual([
      expect.stringMatching(/outside the output directory/),
    ]);
  });
});
