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

import type { OpenAPI, Operation } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import type { GeneratorConfiguration } from '../../src/api/configuration';
import { emitDocument, openAPIDocument } from '../support/emission';

function generate(
  openAPI: Partial<OpenAPI>,
  options: {
    config?: GeneratorConfiguration;
    aggregateTags?: Set<string>;
  } = {},
) {
  return emitDocument(openAPIDocument(openAPI), options);
}

function operation(extra: Partial<Operation> = {}): Operation {
  return {
    tags: ['Items'],
    operationId: 'getItem',
    responses: {},
    ...extra,
  } as Operation;
}

describe('API clients', () => {
  describe('clients', () => {
    it('writes a client per tag, under the bounded context of a Wow document', () => {
      const { file } = generate({
        info: { title: 'T', version: '1', 'x-wow-context-alias': 'shop' },
        paths: { '/items': { get: operation() } },
        tags: [{ name: 'Items', description: 'The items' }],
      });
      const client = file('shop/ItemsApiClient.ts');
      expect(
        client
          .getClassOrThrow('ItemsApiClient')
          .getJsDocs()[0]
          .getDescription()
          .trim(),
      ).toBe('The items');
      expect(client.getText()).toContain(
        'this.apiMetadata = { basePath: SHOP_BOUNDED_CONTEXT_ALIAS, ...apiMetadata };',
      );
      expect(client.getText()).toContain(
        "import { SHOP_BOUNDED_CONTEXT_ALIAS } from './boundedContext.js';",
      );
    });

    it('takes an optional apiMetadata without a bounded context', () => {
      const { file } = generate({ paths: { '/items': { get: operation() } } });
      expect(
        file('ItemsApiClient.ts')
          .getClassOrThrow('ItemsApiClient')
          .getConstructors()[0]
          .getText()
          .replace(/\s+/g, ' '),
      ).toBe('constructor(public readonly apiMetadata?: ApiMetadata) { }');
    });

    it('leaves out Wow, Actuator and aggregate tags', () => {
      const { project } = generate(
        {
          paths: {
            '/a': { get: operation({ tags: ['wow'], operationId: 'a' }) },
            '/b': { get: operation({ tags: ['Actuator'], operationId: 'b' }) },
            '/c': {
              get: operation({ tags: ['shop.order'], operationId: 'c' }),
            },
          },
        },
        { aggregateTags: new Set(['shop.order']) },
      );
      expect(project.getSourceFiles()).toEqual([]);
    });
  });

  describe('methods', () => {
    it('names the method from the operationId', () => {
      const { method } = generate({
        paths: {
          '/items': { get: operation({ operationId: 'items.get_item' }) },
        },
      });
      expect(
        method('ItemsApiClient.ts', 'ItemsApiClient', 'getItem'),
      ).toBeDefined();
    });

    it('turns path parameters into identifiers, keeping the name the path uses', () => {
      const { method } = generate({
        paths: {
          '/items/{item-id}': {
            get: operation({
              parameters: [
                {
                  name: 'item-id',
                  in: 'path',
                  required: true,
                  schema: { type: 'integer' },
                },
              ],
            }),
          },
        },
      });
      expect(
        method('ItemsApiClient.ts', 'ItemsApiClient', 'getItem')
          .getParameters()[0]
          .getText(),
      ).toBe("@path('item-id') itemId: number");
    });

    it('types a referenced request body, optional unless the document requires it', () => {
      const body = (required?: boolean) =>
        generate({
          paths: {
            '/items': {
              post: operation({
                requestBody: {
                  required,
                  content: {
                    'application/json': {
                      schema: { $ref: '#/components/schemas/Item' },
                    },
                  },
                },
              }),
            },
          },
          components: {
            schemas: {
              Item: {
                type: 'object',
                properties: {
                  id: { type: 'string' },
                  name: { type: 'string' },
                },
                required: ['name'],
              },
            },
          },
        })
          .method('ItemsApiClient.ts', 'ItemsApiClient', 'getItem')
          .getParameters()[0]
          .getText();
      expect(body(true)).toBe("@body() body: PartialBy<Item, 'id'>");
      expect(body()).toBe("@body() body?: PartialBy<Item, 'id'>");
    });

    it('types multipart bodies as FormData', () => {
      const { method } = generate({
        paths: {
          '/items': {
            post: operation({
              requestBody: {
                content: { 'multipart/form-data': { schema: {} } },
              },
            }),
          },
        },
      });
      expect(
        method('ItemsApiClient.ts', 'ItemsApiClient', 'getItem')
          .getParameters()[0]
          .getText(),
      ).toBe('@body() body?: FormData');
    });
  });

  describe('return types', () => {
    const returnType = (responses: Operation['responses'], extra = {}) =>
      generate({
        paths: { '/items': { get: operation({ responses }) } },
        ...extra,
      })
        .method('ItemsApiClient.ts', 'ItemsApiClient', 'getItem')
        .getReturnTypeNodeOrThrow()
        .getText();

    it('returns the raw Response without a success response', () => {
      expect(returnType({ '404': { description: 'no' } })).toBe(
        'Promise<Response>',
      );
    });

    it('types an inline JSON response', () => {
      expect(
        returnType({
          '200': {
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: { id: { type: 'string' } },
                },
              },
            },
          },
        }),
      ).toContain('id: string');
    });

    it('reads a string under */* as text', () => {
      expect(
        returnType({
          '200': { content: { '*/*': { schema: { type: 'string' } } } },
        }),
      ).toBe('Promise<string>');
    });

    it('types an event stream of references', () => {
      expect(
        returnType(
          {
            '200': {
              content: {
                'text/event-stream': {
                  schema: { $ref: '#/components/schemas/Events' },
                },
              },
            },
          },
          {
            components: {
              schemas: {
                Events: {
                  type: 'array',
                  items: { $ref: '#/components/schemas/Event' },
                },
                Event: { type: 'object', properties: {} },
              },
            },
          },
        ),
      ).toBe('Promise<JsonServerSentEventStream<Event>>');
    });

    it('streams anything without a reference', () => {
      expect(
        returnType({
          '200': {
            content: { 'text/event-stream': { schema: { type: 'string' } } },
          },
        }),
      ).toBe('Promise<JsonServerSentEventStream<any>>');
    });
  });

  describe('operations it leaves out', () => {
    it('warns about an operation without operationId or tags', () => {
      const { warnings } = generate({
        paths: {
          '/a': { get: operation({ operationId: undefined }) },
          '/b': { get: operation({ tags: [] }) },
        },
      });
      expect(warnings).toEqual([
        'Skipping GET /a: it has no operationId, and the operationId names its method.',
        'Skipping GET /b: it has no tag, and the tag names its API client.',
      ]);
    });

    it('leaves an aggregate route that also carries a business tag to the aggregate', () => {
      const { warnings, project } = generate(
        {
          paths: {
            '/a': { get: operation({ tags: ['shop.order', 'Items'] }) },
          },
        },
        { aggregateTags: new Set(['shop.order']) },
      );
      expect(project.getSourceFiles()).toEqual([]);
      expect(warnings).toEqual([]);
    });
  });
});
