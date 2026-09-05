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

import { describe, expect, it } from 'vitest';
import { Project } from 'ts-morph';
import { ApiClientGenerator, CommandClientGenerator } from '../src/client';
import { AggregateResolver } from '../src/aggregate';
import type { OpenAPI, Parameter, Reference } from '@ahoo-wang/fetcher-openapi';
import { GenerateContext } from '../src/generateContext';
import { ModelGenerator } from '../src/model';
import {
  extractOperationEndpoints,
  extractOperationOkResponseJsonSchema,
  extractPathParameters,
  extractSchema,
} from '../src/utils';

const logger = {
  info() {},
  success() {},
  error() {},
  progress() {},
  progressWithCount() {},
};

describe('review regressions', () => {
  it('keeps component aliases assignable to their target enum across files', () => {
    const project = new Project({
      useInMemoryFileSystem: true,
      compilerOptions: { strict: true, skipLibCheck: true },
    });
    const context = new GenerateContext({
      project,
      outputDir: '/out',
      contextAggregates: new Map(),
      logger,
      openAPI: {
        openapi: '3.0.4',
        info: {},
        paths: {},
        components: {
          schemas: {
            'shared.Target': { type: 'string', enum: ['ON', 'OFF'] },
            Alias: { $ref: '#/components/schemas/shared.Target' },
            Chained: { $ref: '#/components/schemas/Alias' },
          },
        },
      },
    });
    new ModelGenerator(context).generate();
    project.getSourceFileOrThrow('/out/types.ts').addStatements(`
      import { Target as ValueTarget } from './shared/types';
      const alias: Alias = ValueTarget.ON;
      declare const response: Alias;
      const target: ValueTarget = response;
      const chained: Chained = alias;
    `);
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
  });

  it('uses JSON extraction for application/json string responses', () => {
    const project = new Project({ useInMemoryFileSystem: true });
    const context = new GenerateContext({
      project,
      outputDir: '/out',
      contextAggregates: new Map(),
      logger,
      openAPI: {
        openapi: '3.0.4',
        info: {},
        paths: {
          '/message': {
            get: {
              tags: ['Messages'],
              operationId: 'message',
              responses: {
                '200': {
                  content: {
                    'application/json': { schema: { type: 'string' } },
                  },
                },
              },
            },
          },
        },
      },
    });
    new ApiClientGenerator(context).generate();
    const method = project
      .getSourceFileOrThrow('/out/MessagesApiClient.ts')
      .getClassOrThrow('MessagesApiClient')
      .getMethodOrThrow('message');
    expect(
      method
        .getDecoratorOrThrow('get')
        .getArguments()
        .map(arg => arg.getText()),
    ).toEqual(["'/message'"]);
    expect(method.getReturnTypeNodeOrThrow().getText()).toBe('Promise<string>');
  });

  it.each([
    ['application/json', 'Promise<string>', undefined],
    ['*/*', 'Promise<string>', '{resultExtractor:ResultExtractors.Text}'],
    [
      'text/event-stream',
      'Promise<JsonServerSentEventStream<any>>',
      '{headers:{Accept:ContentTypeValues.TEXT_EVENT_STREAM},resultExtractor:JsonEventStreamResultExtractor,}',
    ],
  ])(
    'preserves %s decoding through response aliases',
    (contentType, returnType, metadata) => {
      const project = new Project({ useInMemoryFileSystem: true });
      const operation = {
        tags: ['Messages'],
        operationId: 'message',
        responses: { '200': { $ref: '#/components/responses/Alias' } },
      };
      const components = {
        responses: {
          Alias: { $ref: '#/components/responses/Base' },
          Base: {
            content: { [contentType]: { schema: { type: 'string' as const } } },
          },
        },
      };
      const context = new GenerateContext({
        project,
        outputDir: '/out',
        contextAggregates: new Map(),
        logger,
        openAPI: {
          openapi: '3.0.4',
          info: {},
          paths: { '/message': { get: operation } },
          components,
        },
      });
      new ApiClientGenerator(context).generate();
      const method = project
        .getSourceFileOrThrow('/out/MessagesApiClient.ts')
        .getClassOrThrow('MessagesApiClient')
        .getMethodOrThrow('message');
      expect(method.getReturnTypeNodeOrThrow().getText()).toBe(returnType);
      expect(
        method
          .getDecoratorOrThrow('get')
          .getArguments()
          .map(arg => arg.getText().replace(/\s/g, '')),
      ).toEqual(metadata ? ["'/message'", metadata] : ["'/message'"]);
      if (contentType === 'application/json') {
        expect(
          extractOperationOkResponseJsonSchema(operation, components),
        ).toEqual({ type: 'string' });
      }
    },
  );

  it('inherits path parameters and allows operation overrides', () => {
    const [endpoint] = extractOperationEndpoints({
      '/pets/{id}': {
        parameters: [
          {
            name: 'id',
            in: 'path',
            required: true,
            schema: { type: 'string' },
          },
        ],
        get: { responses: {} },
      },
    });
    expect(extractPathParameters(endpoint.operation, {})).toEqual([
      { name: 'id', in: 'path', required: true, schema: { type: 'string' } },
    ]);
    const [overridden] = extractOperationEndpoints({
      '/pets/{id}': {
        parameters: [{ name: 'id', in: 'path', schema: { type: 'string' } }],
        get: {
          parameters: [{ name: 'id', in: 'path', schema: { type: 'integer' } }],
          responses: {},
        },
      },
    });
    expect(extractPathParameters(overridden.operation, {})).toEqual([
      { name: 'id', in: 'path', schema: { type: 'integer' } },
    ]);
  });

  it.each(['path', 'operation', 'override'] as const)(
    'binds component path parameters in command clients at %s level',
    location => {
      const parameters: (Parameter | Reference)[] = [
        { $ref: '#/components/parameters/RegionAlias' },
        { $ref: '#/components/parameters/wow.id' },
        { $ref: '#/components/parameters/wow.tenantId' },
        { $ref: '#/components/parameters/wow.ownerId' },
        { $ref: '#/components/parameters/Filter' },
      ];
      const openAPI: OpenAPI = {
        openapi: '3.0.4',
        info: {},
        tags: [{ name: 'example.pet' }],
        paths: {
          '/tenant/{tenantId}/owner/{ownerId}/pets/{region}/{id}/rename': {
            parameters: location === 'operation' ? undefined : parameters,
            post: {
              tags: ['example.pet'],
              operationId: 'example.pet.rename',
              parameters:
                location === 'operation'
                  ? parameters
                  : location === 'override'
                    ? [{ $ref: '#/components/parameters/NumericRegion' }]
                    : undefined,
              requestBody: {
                content: {
                  'application/json': {
                    schema: { $ref: '#/components/schemas/Rename' },
                  },
                },
              },
              responses: {
                '200': { $ref: '#/components/responses/wow.CommandOk' },
              },
            },
          },
          '/pets/state': {
            post: {
              tags: ['example.pet'],
              operationId: 'example.pet.snapshot_state.single',
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
              tags: ['example.pet'],
              operationId: 'example.pet.snapshot.count',
              requestBody: {
                'x-wow-query-fields': {
                  $ref: '#/components/schemas/PetFields',
                },
                content: {},
              },
              responses: {},
            },
          },
        },
        components: {
          schemas: {
            Rename: {
              type: 'object',
              properties: { name: { type: 'string' } },
              required: ['name'],
            },
            Pet: { type: 'object' },
            PetFields: { type: 'string', enum: ['name'] },
          },
          parameters: {
            RegionAlias: { $ref: '#/components/parameters/Region' },
            Region: {
              name: 'region',
              in: 'path',
              required: true,
              schema: { type: 'string' },
            },
            NumericRegion: {
              name: 'region',
              in: 'path',
              required: true,
              schema: { type: 'integer' },
            },
            'wow.id': {
              name: 'id',
              in: 'path',
              required: true,
              schema: { type: 'string' },
            },
            'wow.tenantId': {
              name: 'tenantId',
              in: 'path',
              required: true,
              schema: { type: 'string' },
            },
            'wow.ownerId': {
              name: 'ownerId',
              in: 'path',
              required: true,
              schema: { type: 'string' },
            },
            Filter: { name: 'filter', in: 'query', schema: { type: 'string' } },
          },
        },
      };
      const project = new Project({ useInMemoryFileSystem: true });
      const contextAggregates = new AggregateResolver(openAPI).resolve();
      const context = new GenerateContext({
        project,
        outputDir: '/out',
        openAPI,
        contextAggregates,
        logger,
        config: { apiClients: { 'example.pet': { ignorePathParameters: [] } } },
      });
      new CommandClientGenerator(context).generate();
      const commandFile = project.getSourceFileOrThrow(
        '/out/example/pet/commandClient.ts',
      );
      const method = commandFile
        .getClassOrThrow('PetCommandClient')
        .getMethodOrThrow('rename');
      expect(
        method.getParameters().map(parameter => parameter.getName()),
      ).toEqual(['region', 'id', 'commandRequest', 'attributes']);
      for (const name of ['region', 'id']) {
        const parameter = method.getParameterOrThrow(name);
        expect(
          parameter.getDecoratorOrThrow('path').getArguments()[0].getText(),
        ).toBe(`'${name}'`);
        expect(parameter.getTypeNodeOrThrow().getText()).toBe(
          name === 'region' && location === 'override' ? 'number' : 'string',
        );
      }
      expect(
        commandFile
          .getClassOrThrow('PetStreamCommandClient')
          .getExtends()
          ?.getText(),
      ).toBe('PetCommandClient<CommandResultEventStream>');
    },
  );

  it('resolves reusable component aliases and rejects reference cycles', () => {
    const components = {
      schemas: {
        Alias: { $ref: '#/components/schemas/Value' },
        Value: { type: 'string' as const },
      },
    };
    expect(
      extractSchema({ $ref: '#/components/schemas/Alias' }, components),
    ).toEqual({ type: 'string' });
    expect(() =>
      extractSchema(
        { $ref: '#/components/schemas/Alias' },
        { schemas: { Alias: { $ref: '#/components/schemas/Alias' } } },
      ),
    ).toThrow(/cyclic/i);
  });
});
