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

import { expect, it } from 'vitest';
import { Project, ts } from 'ts-morph';
import { ApiClientGenerator } from '../src/client';
import { GenerateContext } from '../src/generateContext';
import { ModelGenerator, resolveReferenceModelInfo } from '../src/model';
import { SilentLogger } from '../src/utils/logger';
import {
  extractOperationEndpoints,
  extractPathParameters,
  extractResponse,
} from '../src/utils';
import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';

it.each([false, true])(
  'does not resolve an external response as a local namesake (alias: %s)',
  alias => {
    const foreign = { $ref: 'common.yaml#/components/responses/Success' };
    const openAPI: OpenAPI = {
      openapi: '3.0.4',
      info: {},
      paths: {
        '/items': {
          get: {
            tags: ['Items'],
            operationId: 'items',
            responses: {
              '200': alias ? { $ref: '#/components/responses/Alias' } : foreign,
            },
          },
        },
      },
      components: {
        responses: {
          Alias: foreign,
          Success: {
            content: { 'application/json': { schema: { type: 'string' } } },
          },
        },
      },
    };
    expect(extractResponse(foreign, openAPI.components!)).toBeUndefined();
    const project = new Project({
      compilerOptions: {
        strict: true,
        skipLibCheck: true,
        experimentalDecorators: true,
        module: ts.ModuleKind.ESNext,
        moduleResolution: ts.ModuleResolutionKind.Bundler,
        target: ts.ScriptTarget.ES2022,
      },
    });
    const outputDir = `${process.cwd()}/test-output/external-response`;
    const context = new GenerateContext({
      project,
      outputDir,
      openAPI,
      contextAggregates: new Map(),
      logger: new SilentLogger(),
    });
    new ApiClientGenerator(context).generate();
    const method = project
      .getSourceFileOrThrow(`${outputDir}/ItemsApiClient.ts`)
      .getClassOrThrow('ItemsApiClient')
      .getMethodOrThrow('items');
    expect(method.getReturnTypeNodeOrThrow().getText()).toBe(
      'Promise<Response>',
    );
    expect(method.getDecoratorOrThrow('get').getText()).toContain(
      'ResultExtractors.Response',
    );
    expect(
      project.getPreEmitDiagnostics().map(d => d.getMessageText()),
    ).toEqual([]);
  },
);

it.each([false, true])(
  'handles inherited external parameters without local substitution (namesake: %s)',
  namesake => {
    const openAPI: OpenAPI = {
      openapi: '3.0.4',
      info: {},
      paths: {
        '/items/{id}': {
          parameters: [{ $ref: 'common.yaml#/components/parameters/Id' }],
          get: { tags: ['Items'], operationId: 'item', responses: {} },
        },
      },
      components: {
        parameters: namesake
          ? { Id: { in: 'path', name: 'wrong', schema: { type: 'integer' } } }
          : {},
      },
    };
    const [endpoint] = extractOperationEndpoints(
      openAPI.paths,
      openAPI.components,
    );
    expect(
      extractPathParameters(endpoint.operation, openAPI.components!),
    ).toEqual([]);
    const project = new Project({ useInMemoryFileSystem: true });
    const context = new GenerateContext({
      project,
      outputDir: '/out',
      openAPI,
      contextAggregates: new Map(),
      logger: new SilentLogger(),
    });
    new ApiClientGenerator(context).generate();
    const method = project
      .getSourceFileOrThrow('/out/ItemsApiClient.ts')
      .getClassOrThrow('ItemsApiClient')
      .getMethodOrThrow('item');
    expect(method.getParameters().map(p => p.getName())).toEqual([
      'httpRequest',
      'attributes',
    ]);
  },
);

it.each([false, true])(
  'rejects external schema aliases without local binding (namesake: %s)',
  namesake => {
    const reference = { $ref: 'common.yaml#/components/schemas/User' };
    const openAPI: OpenAPI = {
      openapi: '3.0.4',
      info: {},
      paths: {},
      components: {
        schemas: {
          Alias: reference,
          ...(namesake
            ? {
                User: {
                  type: 'object' as const,
                  properties: { id: { type: 'string' as const } },
                },
              }
            : {}),
        },
      },
    };
    const message = `Unsupported schema reference: ${reference.$ref}. Bundle or inline external schemas before generation.`;
    expect(() =>
      resolveReferenceModelInfo(reference, openAPI.components),
    ).toThrow(message);
    const context = new GenerateContext({
      project: new Project({ useInMemoryFileSystem: true }),
      outputDir: '/out',
      openAPI,
      contextAggregates: new Map(),
      logger: new SilentLogger(),
    });
    expect(() => new ModelGenerator(context).generate()).toThrow(message);
  },
);
