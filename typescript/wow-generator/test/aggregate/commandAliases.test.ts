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
import { mkdtempSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';
import { Project, ts } from 'ts-morph';
import type {
  OpenAPI,
  Operation,
  RequestBody,
  Response,
} from '@ahoo-wang/fetcher-openapi';
import { AggregateResolver } from '../../src/aggregate';
import { CodeGenerator } from '../../src';
import { CommandClientGenerator } from '../../src/client';
import { GenerateContext } from '../../src/generateContext';
import { ModelGenerator } from '../../src/model';
import { SilentLogger } from '../../src/utils/logger';

function specification(): OpenAPI {
  const body: RequestBody = {
    content: {
      'application/json': { schema: { $ref: '#/components/schemas/Rename' } },
    },
  };
  const response: Response = { description: 'Command result' };
  return {
    openapi: '3.0.4',
    info: {},
    tags: [{ name: 'example.pet' }],
    paths: {
      '/pets/rename': {
        post: {
          operationId: 'example.pet.rename',
          tags: ['example.pet'],
          requestBody: body,
          responses: {
            '200': { $ref: '#/components/responses/wow.CommandOk' },
          },
        },
      },
      '/pets/state': {
        post: {
          operationId: 'example.pet.snapshot_state.single',
          tags: ['example.pet'],
          responses: {
            '200': {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/Pet' },
                },
              },
            },
          },
        },
      },
      '/pets/count': {
        post: {
          operationId: 'example.pet.snapshot.count',
          tags: ['example.pet'],
          requestBody: {
            content: {},
            'x-wow-query-fields': { $ref: '#/components/schemas/PetFields' },
          },
          responses: {},
        },
      },
    },
    components: {
      responses: {
        'wow.CommandOk': response,
        Alias: { $ref: '#/components/responses/Second' },
        Second: { $ref: '#/components/responses/wow.CommandOk' },
        NonWow: response,
      },
      requestBodies: {
        Rename: body,
        Alias: { $ref: '#/components/requestBodies/Second' },
        Second: { $ref: '#/components/requestBodies/Rename' },
      },
      schemas: {
        Rename: {
          type: 'object',
          properties: { name: { type: 'string' } },
          required: ['name'],
        },
        Pet: { type: 'object' },
        PetFields: { type: 'string', enum: ['name'] },
      },
    },
  };
}

function command(openAPI: OpenAPI): Operation {
  return openAPI.paths['/pets/rename'].post!;
}

it.each([
  'response aliases',
  'request body reference',
  'request body aliases',
] as const)('generates and type-checks CommandClient with %s', mode => {
  const openAPI = specification();
  if (mode === 'response aliases')
    command(openAPI).responses['200'] = {
      $ref: '#/components/responses/Alias',
    };
  else
    command(openAPI).requestBody = {
      $ref: `#/components/requestBodies/${mode === 'request body reference' ? 'Rename' : 'Alias'}`,
    };
  const aggregates = new AggregateResolver(openAPI).resolve();
  expect([...aggregates.get('example')!][0].commands.has('rename')).toBe(true);
  const packageRoot = fileURLToPath(new URL('../../', import.meta.url));
  const outputDir = mkdtempSync(join(packageRoot, '.command-alias-'));
  try {
    const project = new Project({
      compilerOptions: {
        strict: true,
        skipLibCheck: true,
        noEmit: true,
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ESNext,
        moduleResolution: ts.ModuleResolutionKind.Bundler,
        experimentalDecorators: true,
        paths: Object.fromEntries(
          [
            'fetcher',
            'decorator',
            'eventstream',
            'wow',
            'openapi',
            'eventbus',
            'storage',
          ].map(name => [
            name === 'fetcher'
              ? '@ahoo-wang/fetcher'
              : `@ahoo-wang/fetcher-${name}`,
            [resolve(packageRoot, '..', name, 'src/index.ts')],
          ]),
        ),
      },
    });
    const context = new GenerateContext({
      openAPI,
      contextAggregates: aggregates,
      project,
      outputDir,
      logger: new SilentLogger(),
    });
    new ModelGenerator(context).generate();
    new CommandClientGenerator(context).generate();
    new CodeGenerator({
      inputPath: '',
      outputDir,
      logger: new SilentLogger(),
    }).optimizeSourceFiles(project.getDirectoryOrThrow(outputDir));
    const generated = project.getSourceFileOrThrow(
      join(outputDir, 'example/pet/commandClient.ts'),
    );
    expect(
      generated
        .getClassOrThrow('PetCommandClient')
        .getMethodOrThrow('rename')
        .getParameters()[0]
        .getTypeNodeOrThrow()
        .getText(),
    ).toBe('CommandRequest<RenameCommand>');
    project.createSourceFile(
      join(outputDir, 'consumer.ts'),
      `
        import { PetCommandClient } from './example/pet/commandClient';
        import type { CommandResult } from '@ahoo-wang/fetcher-wow';
        declare const client: PetCommandClient;
        const result: Promise<CommandResult> = client.rename({body: {name: 'renamed'}});
        // @ts-expect-error The generated command body requires a string name.
        client.rename({body: {name: 123}});
      `,
    );
    expect(
      project
        .getPreEmitDiagnostics()
        .map(diagnostic => diagnostic.getMessageText()),
    ).toEqual([]);
  } finally {
    rmSync(outputDir, { recursive: true, force: true });
  }
});

it('does not identify an arbitrary response with the same shape or target object as CommandOk', () => {
  const openAPI = specification();
  command(openAPI).responses['200'] = { $ref: '#/components/responses/NonWow' };
  expect(
    [...new AggregateResolver(openAPI).resolve().get('example')!][0].commands
      .size,
  ).toBe(0);
});

it('rejects cycles in command response aliases', () => {
  const openAPI = specification();
  command(openAPI).responses['200'] = { $ref: '#/components/responses/Alias' };
  openAPI.components!.responses!.Second = {
    $ref: '#/components/responses/Alias',
  };
  expect(() => new AggregateResolver(openAPI)).toThrow(/cyclic/i);
});

it.each([
  { $ref: '#/components/requestBodies/Missing' },
  { content: { 'application/json': { schema: { type: 'object' as const } } } },
])(
  'skips a command whose body cannot resolve to a component schema: %j',
  requestBody => {
    const openAPI = specification();
    command(openAPI).requestBody = requestBody;
    expect(
      [...new AggregateResolver(openAPI).resolve().get('example')!][0].commands
        .size,
    ).toBe(0);
  },
);
