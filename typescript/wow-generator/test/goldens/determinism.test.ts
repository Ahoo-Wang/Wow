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

import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import {
  coldDirectory,
  PACKAGE_ROOT,
  removeDirectories,
} from '../support/generation';

/**
 * The same document and configuration give the same bytes wherever the
 * generator runs, whatever the locale and the working directory (invariant 1
 * of docs/design/architecture.md). The runs go through the built CLI in a
 * child process, because ICU reads the locale once, when a process starts.
 */

const CLI = join(PACKAGE_ROOT, 'dist', 'cli.js');
const TURKISH = 'tr_TR.UTF-8';
const ENGLISH = 'en_US.UTF-8';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

function localeEnv(locale: string): NodeJS.ProcessEnv {
  return { ...process.env, LANG: locale, LC_ALL: locale };
}

function readTree(dir: string): Record<string, string> {
  const files: Record<string, string> = {};
  const visit = (current: string) => {
    for (const name of readdirSync(current).sort()) {
      const path = join(current, name);
      if (statSync(path).isDirectory()) visit(path);
      else files[relative(dir, path)] = readFileSync(path, 'utf8');
    }
  };
  visit(dir);
  return files;
}

/**
 * Generates a document with the built CLI in a new directory.
 *
 * @param locale - `LANG` and `LC_ALL` of the run
 * @param input - The document, an absolute path
 * @param fromParent - Run from the directory above the project, naming the
 * output and the tsconfig through it, rather than from the project itself
 * @returns Each file written, by its path under the output directory
 */
function generate(
  locale: string,
  input: string,
  fromParent: boolean,
  config?: string,
): Record<string, string> {
  const project = coldDirectory('determinism', directories);
  writeFileSync(
    join(project, 'tsconfig.json'),
    JSON.stringify({
      compilerOptions: {
        target: 'ES2022',
        module: 'ESNext',
        moduleResolution: 'bundler',
        strict: true,
        experimentalDecorators: true,
        skipLibCheck: true,
        noEmit: true,
      },
      include: ['src/**/*'],
    }),
  );
  const cwd = fromParent ? dirname(project) : project;
  const base = relative(cwd, project);
  const args = [
    CLI,
    'generate',
    '-i',
    input,
    '-o',
    join(base, 'src', 'generated'),
    '-t',
    join(base, 'tsconfig.json'),
    ...(config ? ['-c', config] : []),
  ];
  execFileSync(process.execPath, args, {
    cwd,
    env: localeEnv(locale),
    encoding: 'utf8',
    timeout: 60000,
  });
  return readTree(join(project, 'src', 'generated'));
}

/** A document whose operations sort differently under Turkish collation. */
function localeSensitiveSpec(): string {
  const ok = {
    '200': {
      description: 'OK',
      content: { 'application/json': { schema: { type: 'string' } } },
    },
  };
  const dir = coldDirectory('determinism-spec', directories);
  const path = join(dir, 'openapi.json');
  writeFileSync(
    path,
    JSON.stringify({
      openapi: '3.0.1',
      info: { title: 'Locale', version: '1.0.0' },
      tags: [{ name: 'catalog' }],
      paths: {
        // `ItemsList` sorts after `index` in English and before it in
        // Turkish, where `I` is the capital of the dotless `ı`.
        '/items': {
          get: { tags: ['catalog'], operationId: 'ItemsList', responses: ok },
        },
        '/index': {
          get: { tags: ['catalog'], operationId: 'index', responses: ok },
        },
      },
    }),
  );
  return path;
}

describe('generated output does not depend on where the generator runs', () => {
  it('runs the children under the Turkish locale', () => {
    expect(
      execFileSync(
        process.execPath,
        ['-p', 'new Intl.Collator().resolvedOptions().locale'],
        { env: localeEnv(TURKISH), encoding: 'utf8' },
      ).trim(),
    ).toBe('tr-TR');
  });

  it('orders operations the same under the Turkish and English locales', () => {
    const spec = localeSensitiveSpec();
    const english = generate(ENGLISH, spec, false);
    const client = english['catalogApiClient.ts'];
    expect(client.indexOf('index(')).toBeLessThan(client.indexOf('itemsList('));
    expect(generate(TURKISH, spec, true)).toEqual(english);
  }, 60000);

  it('writes expected/demo-spec byte for byte under the Turkish locale, from another directory', () => {
    const generated = generate(
      TURKISH,
      join(PACKAGE_ROOT, 'test', 'demo.spec.json'),
      true,
      join(PACKAGE_ROOT, 'test', 'wow-generator.config.json'),
    );
    expect(generated).toEqual(
      readTree(join(PACKAGE_ROOT, 'expected', 'demo-spec')),
    );
  }, 60000);
});
