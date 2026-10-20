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
import { extractSchema } from '../../src/openapi/components';
import {
  extractOperationEndpoints,
  extractOperationOkResponseJsonSchema,
  extractPathParameters,
} from '../../src/openapi/operations';
import { emitDocument, openAPIDocument } from '../support/emission';

describe('OpenAPI contracts the generated code keeps', () => {
  it('keeps component aliases assignable to their target enum across files', () => {
    const project = new Project({
      useInMemoryFileSystem: true,
      compilerOptions: { strict: true, skipLibCheck: true },
    });
    emitDocument(
      openAPIDocument({
        components: {
          schemas: {
            'shared.Target': { type: 'string', enum: ['ON', 'OFF'] },
            Alias: { $ref: '#/components/schemas/shared.Target' },
            Chained: { $ref: '#/components/schemas/Alias' },
          },
        },
      }),
      { project },
    );
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
    const { project } = emitDocument(
      openAPIDocument({
        paths: {
          '/message': {
            get: {
              tags: ['Messages'],
              operationId: 'message',
              responses: {
                '200': {
                  description: 'OK',
                  content: {
                    'application/json': { schema: { type: 'string' } },
                  },
                },
              },
            },
          },
        },
      }),
    );
    const method = project
      .getSourceFileOrThrow('/out/messagesApiClient.ts')
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
      const operation = {
        tags: ['Messages'],
        operationId: 'message',
        responses: { '200': { $ref: '#/components/responses/Alias' } },
      };
      const components = {
        responses: {
          Alias: { $ref: '#/components/responses/Base' },
          Base: {
            description: 'OK',
            content: { [contentType]: { schema: { type: 'string' as const } } },
          },
        },
      };
      const { project } = emitDocument(
        openAPIDocument({
          paths: { '/message': { get: operation } },
          components,
        }),
      );
      const method = project
        .getSourceFileOrThrow('/out/messagesApiClient.ts')
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
