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

import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { expect, it } from 'vitest';
import { Project } from 'ts-morph';
import { GenerateContext } from '../src/generateContext';
import { ModelGenerator } from '../src/model';

it('compiles same-named component aliases and reuses their distinct imported names', () => {
  const outputDir = mkdtempSync(join(tmpdir(), 'fetcher-import-alias-'));
  try {
    const project = new Project({ skipAddingFilesFromTsConfig: true });
    const context = new GenerateContext({
      project,
      outputDir,
      contextAggregates: new Map(),
      logger: {
        info() {},
        success() {},
        error() {},
        progress() {},
        progressWithCount() {},
      },
      openAPI: {
        openapi: '3.0.4',
        info: {},
        paths: {},
        components: {
          schemas: {
            User: { $ref: '#/components/schemas/shared.User' },
            Reused: { $ref: '#/components/schemas/shared.User' },
            Pair: {
              type: 'object',
              properties: {
                left: { $ref: '#/components/schemas/shared.User' },
                right: { $ref: '#/components/schemas/other.User' },
              },
            },
            'shared.User': {
              type: 'object',
              properties: { id: { type: 'string' } },
            },
            'other.User': {
              type: 'object',
              properties: { age: { type: 'number' } },
            },
          },
        },
      },
    });
    new ModelGenerator(context).generate();
    project.saveSync();
    writeFileSync(
      join(outputDir, 'consumer.ts'),
      `
   import type { User, Reused, Pair } from './types';
   const user: User = {id:'u'};
   const reused: Reused = user;
   const pair: Pair = {left:reused,right:{age:21}};
   // @ts-expect-error The User alias preserves the shared target fields.
   const wrongUser: User = {age:21};
   // @ts-expect-error The second import still resolves the other target.
   const wrongPair: Pair = {left:user,right:{id:'u'}};
   void pair; void wrongUser; void wrongPair;
  `,
    );
    writeFileSync(
      join(outputDir, 'tsconfig.json'),
      JSON.stringify({
        compilerOptions: {
          strict: true,
          noEmit: true,
          skipLibCheck: true,
          module: 'ESNext',
          moduleResolution: 'Bundler',
          types: [],
        },
        include: ['**/*.ts'],
      }),
    );
    const require = createRequire(import.meta.url);
    const result = spawnSync(
      process.execPath,
      [
        require.resolve('typescript/lib/tsc.js'),
        '-p',
        join(outputDir, 'tsconfig.json'),
      ],
      { encoding: 'utf8' },
    );
    expect(result.status, result.stdout + result.stderr).toBe(0);
  } finally {
    rmSync(outputDir, { recursive: true, force: true });
  }
});

it.each([true, false])(
  'reserves EnumText declarations regardless of schema order (enum first: %s)',
  enumFirst => {
    const project = new Project({
      useInMemoryFileSystem: true,
      compilerOptions: { strict: true, skipLibCheck: true },
    });
    const status = {
      'local.Status': {
        type: 'string' as const,
        enum: ['ON'],
        'x-enum-text': { ON: 'On' },
      },
    };
    const alias = {
      'local.Alias': { $ref: '#/components/schemas/remote.StatusEnumText' },
    };
    const context = new GenerateContext({
      project,
      outputDir: '/out',
      contextAggregates: new Map(),
      logger: {
        info() {},
        success() {},
        error() {},
        progress() {},
        progressWithCount() {},
      },
      openAPI: {
        openapi: '3.0.4',
        info: {},
        paths: {},
        components: {
          schemas: {
            ...(enumFirst ? status : alias),
            ...(enumFirst ? alias : status),
            'remote.StatusEnumText': {
              type: 'object',
              properties: { id: { type: 'string' } },
              required: ['id'],
            },
          },
        },
      },
    });
    new ModelGenerator(context).generate();
    project.createSourceFile(
      '/out/consumer.ts',
      `
    import { StatusEnumText } from './local/types';
    import type { Alias } from './local/types';
    const label: StatusEnumText = StatusEnumText.ON;
    const value: Alias = {id: 'a'};
    // @ts-expect-error The alias keeps the remote object type.
    const wrong: Alias = label;
    void value; void wrong;
  `,
    );
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
  },
);
