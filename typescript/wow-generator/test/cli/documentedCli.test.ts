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
 * What the READMEs and the reference say of the CLI's output and the
 * programmatic exit codes, held to what the package does (#7061).
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Project } from 'ts-morph';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import type { Logger } from '../../src/api/logger';
import { runGenerate } from '../../src/cli/runGenerate';
import { PROJECT_SEAM } from '../../src/pipeline/seams';
import { manifest } from '../support/manifestProbes';
import {
  coldDirectory,
  PACKAGE_ROOT,
  removeDirectories,
} from '../support/generation';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const read = (path: string) => readFileSync(join(PACKAGE_ROOT, path), 'utf8');

describe('the documented CLI and API', () => {
  it('names the configuration in the summary line by its path relative to the working directory, as the READMEs say', async () => {
    const dir = coldDirectory('documented-summary', directories);
    const input = join(dir, 'manifest.json');
    writeFileSync(input, JSON.stringify(manifest()));
    const config = join(dir, 'wow-generator.config.json');
    writeFileSync(config, JSON.stringify({}));
    const lines: string[] = [];
    const logger: Logger = {
      debug() {},
      info(message: string) {
        lines.push(message);
      },
      warn() {},
      error() {},
    };
    const exitCode = await runGenerate(
      { input, output: join(dir, 'out'), config },
      logger,
      { [PROJECT_SEAM]: new Project({ skipAddingFilesFromTsConfig: true }) },
    );
    expect(exitCode).toBe(EXIT_CODES.success);
    const summary = lines.find(line => line.startsWith('Generated ')) ?? '';
    expect(summary).not.toContain(` with ${config}`);
    expect(summary).toMatch(/ with \S*wow-generator\.config\.json/);
    for (const readme of ['README.md', 'README.zh-CN.md']) {
      const text = read(readme);
      expect(text, readme).toContain(
        'Generated 12 files into src/generated with wow-generator.config.json, 1 warning',
      );
      expect(text, readme).not.toMatch(
        /absolute path of the configuration|配置的绝对路径/,
      );
    }
  });

  it('lists every exit code of EXIT_CODES in the programmatic API reference, en and zh', () => {
    for (const language of ['en', 'zh']) {
      const reference = read(
        `../../documentation/docs/${language}/reference/typescript/wow-generator/programmatic-api.md`,
      );
      const unlisted = Object.entries(EXIT_CODES)
        .filter(([key, code]) => !reference.includes(`\`${key}\` ${code}`))
        .map(([key, code]) => `${key} ${code}`);
      expect(unlisted, language).toEqual([]);
    }
  });
});
