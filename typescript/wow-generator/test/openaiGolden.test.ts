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
 * The golden of a large document that is not a Wow one: `test/openai.spec.yml`,
 * 873 schemas and 219 operations.
 *
 * The output is 34 files and some 25,000 lines, too much to commit, so the
 * golden is the SHA-256 of each file, in the format of the generation
 * manifest: `expected/openai-spec/.wow-generator.json`. Any byte that changes
 * fails it, and the failure names the files that differ. The warnings the run
 * logs are held word for word in `expected/warnings/openai-spec.txt`.
 *
 * Generating this document takes minutes until the emit layer writes each
 * file once (refactor batch B4), so the suite runs only with
 * `WOW_GENERATOR_LARGE=1`:
 *
 *   pnpm --filter @ahoo-wang/wow-generator test:large
 *
 * After an intentional change to the output, accept it with `-u` and list
 * the files that changed in the pull request:
 *
 *   pnpm --filter @ahoo-wang/wow-generator test:large -u
 */

import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { GENERATION_MANIFEST } from '../src/utils';
import {
  generateProject,
  PACKAGE_ROOT,
  removeDirectories,
} from './support/generation';

const LARGE = process.env.WOW_GENERATOR_LARGE === '1';
const GOLDEN = 'expected/openai-spec/.wow-generator.json';
const WARNINGS = 'expected/warnings/openai-spec.txt';
/** Generating the document takes about 3.5 minutes today; allow for load. */
const GENERATION_TIMEOUT = 15 * 60 * 1000;

/** Every file under a directory but the manifest, relative, with `/`. */
function filesUnder(dir: string, prefix = ''): string[] {
  return readdirSync(dir)
    .sort()
    .flatMap(name => {
      const path = join(dir, name);
      const relative = prefix + name;
      if (statSync(path).isDirectory()) return filesUnder(path, `${relative}/`);
      return relative === GENERATION_MANIFEST ? [] : [relative];
    });
}

/**
 * The manifest of a directory, hashed here rather than read from the one the
 * generator wrote, so a generator that recorded a wrong hash cannot pass.
 */
function hashedManifest(dir: string): Record<string, string> {
  return Object.fromEntries(
    filesUnder(dir).map(file => [
      file,
      createHash('sha256')
        .update(readFileSync(join(dir, file)))
        .digest('hex'),
    ]),
  );
}

function render(files: Record<string, string>): string {
  return `${JSON.stringify({ version: 1, files }, null, 2)}\n`;
}

/** The files added, removed or changed since the committed golden. */
function differences(files: Record<string, string>): string[] {
  const path = join(PACKAGE_ROOT, GOLDEN);
  if (!existsSync(path)) return [];
  const golden: Record<string, string> = JSON.parse(
    readFileSync(path, 'utf8'),
  ).files;
  return [...new Set([...Object.keys(golden), ...Object.keys(files)])]
    .sort()
    .flatMap(file => {
      if (!(file in files)) return [`removed ${file}`];
      if (!(file in golden)) return [`added ${file}`];
      return golden[file] === files[file] ? [] : [`changed ${file}`];
    });
}

describe.runIf(LARGE)('the OpenAI document (873 schemas, not Wow)', () => {
  const directories: string[] = [];
  let run: { output: string; warnings: string };

  beforeAll(async () => {
    run = await generateProject(
      'openai',
      { input: 'test/openai.spec.yml' },
      directories,
    );
  }, GENERATION_TIMEOUT);
  afterAll(() => removeDirectories(directories));

  it('writes every file byte for byte as the hashed golden records', async () => {
    const files = hashedManifest(run.output);
    const differ = differences(files);
    await expect(
      render(files),
      differ.length > 0
        ? `${differ.length} generated file(s) differ from ${GOLDEN}: ${differ.join(', ')}`
        : undefined,
    ).toMatchFileSnapshot(join('..', GOLDEN));
  });

  it('records the same hashes in the manifest it writes', () => {
    expect(readFileSync(join(run.output, GENERATION_MANIFEST), 'utf8')).toBe(
      render(hashedManifest(run.output)),
    );
  });

  it('logs the warnings word for word', async () => {
    await expect(run.warnings).toMatchFileSnapshot(join('..', WARNINGS));
  });
});
