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

import {
  existsSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
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

/** A document of one operation answering an Item. */
const spec = document(
  {
    '/items': {
      get: {
        tags: ['Items'],
        operationId: 'items',
        responses: {
          '200': {
            description: 'ok',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Item' },
              },
            },
          },
        },
      },
    },
  },
  { Item: { type: 'object', properties: { id: { type: 'string' } } } },
);

/** Every file under [directory], by its path relative to it, with its bytes and modification time. */
function snapshot(directory: string): Map<string, string> {
  return new Map(
    readdirSync(directory, { recursive: true, withFileTypes: true })
      .filter(entry => entry.isFile())
      .map(entry => {
        const path = join(entry.parentPath, entry.name);
        return [
          path.slice(directory.length + 1),
          `${statSync(path).mtimeMs} ${readFileSync(path, 'utf8')}`,
        ];
      }),
  );
}

/** Runs `generate --check` on [output], the output of an earlier run of [dir]'s document. */
async function check(dir: string, output: string) {
  const logger = recordingLogger();
  const errors: string[] = [];
  const infos: string[] = [];
  logger.error = (message: string) => {
    errors.push(message);
  };
  logger.info = (message: string) => {
    infos.push(message);
  };
  const before = existsSync(output) ? snapshot(output) : undefined;
  const exitCode = await runGenerate(
    { input: join(dir, 'openapi.json'), output, check: true },
    logger,
  );
  // --check writes nothing: every file, its bytes and its time, as it was.
  expect(existsSync(output) ? snapshot(output) : undefined).toEqual(before);
  return { exitCode, errors, infos };
}

describe('generate --check', () => {
  it('passes on the output a run wrote, writing nothing', async () => {
    const first = await generateCold(spec, directories);
    expect(first.exitCode).toBe(EXIT_CODES.success);

    const result = await check(first.dir, first.output);

    expect(result.exitCode).toBe(EXIT_CODES.success);
    expect(result.errors).toEqual([]);
    expect(result.infos).toEqual([
      expect.stringMatching(/^Checked \d+ files in .+: up to date$/),
    ]);
  });

  it('lists a file edited by hand and one deleted, and leaves both as they are', async () => {
    const first = await generateCold(spec, directories);
    const manifest = JSON.parse(
      readFileSync(join(first.output, '.wow-generator.json'), 'utf8'),
    ) as { files: Record<string, string> };
    const [edited, deleted] = Object.keys(manifest.files).sort();
    writeFileSync(join(first.output, edited), '// edited\n');
    rmSync(join(first.output, deleted));

    const result = await check(first.dir, first.output);

    expect(result.exitCode).toBe(EXIT_CODES.check);
    expect(result.errors[0]).toMatch(
      /^--check: 2 files of .+ differ from what the run would write; regenerate without --check:$/,
    );
    expect(result.errors.slice(1)).toEqual(
      [deleted, edited].sort().map(path => `  ${path}`),
    );
  });

  it('lists a file of the last run the run no longer writes, and the manifest, but never a file it does not own', async () => {
    const first = await generateCold(spec, directories);
    const manifestPath = join(first.output, '.wow-generator.json');
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
      files: Record<string, string>;
    };
    // A file an earlier run wrote, as that run recorded it; and one of the user's, which no manifest names.
    writeFileSync(join(first.output, 'gone.ts'), 'export {};\n');
    manifest.files['gone.ts'] = '0'.repeat(64);
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
    writeFileSync(
      join(first.output, 'custom.ts'),
      'export const custom = 1;\n',
    );

    const result = await check(first.dir, first.output);

    expect(result.exitCode).toBe(EXIT_CODES.check);
    expect(result.errors.slice(1)).toEqual([
      '  .wow-generator.json',
      '  gone.ts',
    ]);
    expect(result.errors[0]).toMatch(/^--check: 2 files of /);
  });

  it('lists every file and the manifest for an output directory never generated', async () => {
    const first = await generateCold(spec, directories);
    const files = Object.keys(
      JSON.parse(
        readFileSync(join(first.output, '.wow-generator.json'), 'utf8'),
      ).files,
    );
    rmSync(first.output, { recursive: true });

    const result = await check(first.dir, first.output);

    expect(result.exitCode).toBe(EXIT_CODES.check);
    expect(result.errors.slice(1)).toEqual(
      ['.wow-generator.json', ...files].sort().map(path => `  ${path}`),
    );
  });
});
