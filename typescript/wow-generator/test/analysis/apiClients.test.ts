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
 * What the analysis decides for the API clients of a document, read off the
 * generation model without writing a line: clients, method names, parameter
 * order, body and response kinds, name clashes. The code they become is held
 * by test/emitters/apiClients.test.ts and the goldens.
 */

import type {
  OpenAPI,
  Operation,
  Parameter,
  Responses,
} from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import type { ApiMethodModel } from '../../src/analysis/model';
import type { GeneratorConfiguration } from '../../src/api/configuration';
import { GeneratorError } from '../../src/api/errors';
import { analyzeDocument, openAPIDocument } from '../support/emission';

function operation(extra: Partial<Operation> = {}): Operation {
  return {
    tags: ['Items'],
    operationId: 'getItem',
    responses: {},
    ...extra,
  } as Operation;
}

function analysis(openAPI: Partial<OpenAPI>, config?: GeneratorConfiguration) {
  return analyzeDocument(openAPIDocument(openAPI), { config });
}

/** The one method of the one client of a document. */
function methodOf(
  openAPI: Partial<OpenAPI>,
  config?: GeneratorConfiguration,
): ApiMethodModel {
  const { apiClients } = analysis(openAPI, config).model;
  expect(apiClients).toHaveLength(1);
  expect(apiClients[0].methods).toHaveLength(1);
  return apiClients[0].methods[0];
}

describe('API client analysis', () => {
  it('declares a client per tag, sorted, and a method per operation, by operation id', () => {
    const { apiClients } = analysis({
      paths: {
        '/b': {
          get: operation({ tags: ['Orders'], operationId: 'listOrders' }),
        },
        '/a': { get: operation({ operationId: 'b.second' }) },
        '/c': { post: operation({ operationId: 'a.first' }) },
      },
      tags: [{ name: 'Items', description: 'The items' }],
    }).model;
    expect(
      apiClients.map(client => ({
        tagName: client.tagName,
        className: client.className,
        file: client.file,
        description: client.description,
        methods: client.methods.map(({ name, httpMethod, path }) => [
          name,
          httpMethod,
          path,
        ]),
      })),
    ).toEqual([
      {
        tagName: 'Items',
        className: 'ItemsApiClient',
        file: '/ItemsApiClient.ts',
        description: 'The items',
        methods: [
          ['first', 'post', '/c'],
          ['second', 'get', '/a'],
        ],
      },
      {
        tagName: 'Orders',
        className: 'OrdersApiClient',
        file: '/OrdersApiClient.ts',
        description: '',
        methods: [['listOrders', 'get', '/b']],
      },
    ]);
  });

  it('puts the clients of a Wow document under its bounded context, which is their base path', () => {
    const [client] = analysis({
      info: { title: 'T', version: '1', 'x-wow-context-alias': 'shop' },
      paths: { '/items': { get: operation() } },
    }).model.apiClients;
    expect(client.file).toBe('shop/ItemsApiClient.ts');
    expect(client.basePath).toEqual({
      alias: 'shop',
      constantName: 'SHOP_BOUNDED_CONTEXT_ALIAS',
    });
  });

  it.each([
    [
      'operationId, last segment, camel-cased',
      { operationId: 'items.get_item' },
      undefined,
      'getItem',
    ],
    [
      'x-fetcher-method',
      { 'x-fetcher-method': 'fetchOne' },
      undefined,
      'fetchOne',
    ],
    [
      'configuration over x-fetcher-method',
      { 'x-fetcher-method': 'fetchOne' },
      { apiClients: { Items: { methodNames: { getItem: 'load' } } } },
      'load',
    ],
  ] as [
    string,
    Partial<Operation>,
    GeneratorConfiguration | undefined,
    string,
  ][])('names a method from the %s', (_, extra, config, name) => {
    expect(
      methodOf({ paths: { '/items': { get: operation(extra) } } }, config).name,
    ).toBe(name);
  });

  it('fails when two operations of a client name the same method', () => {
    const clash = () =>
      analysis({
        paths: {
          '/a': { get: operation({ operationId: 'users.list' }) },
          '/b': { get: operation({ operationId: 'orders.list' }) },
        },
      });
    expect(clash).toThrow(GeneratorError);
    expect(clash).toThrow(
      'Operations orders.list and users.list of tag Items both generate the method ItemsApiClient.list().',
    );
  });

  it('keeps the parameters in the order their types resolve, each with the name the request uses', () => {
    const parameters: Parameter[] = [
      {
        name: 'X-Tenant',
        in: 'header',
        required: true,
        schema: { type: 'string' },
      },
      { name: 'page', in: 'query', schema: { type: 'integer' } },
      {
        name: 'item-id',
        in: 'path',
        required: true,
        schema: { type: 'string' },
      },
      { name: 'q', in: 'query', required: true },
      { name: 'session', in: 'cookie', schema: { type: 'string' } },
    ];
    const { model, warnings } = analysis({
      paths: {
        '/items/{item-id}': {
          post: operation({
            parameters,
            requestBody: {
              required: true,
              content: { 'text/plain': { schema: { type: 'string' } } },
            },
          }),
        },
      },
    });
    const [method] = model.apiClients[0].methods;
    expect(
      method.parameters.map(({ name, location, parameterName, required }) => [
        name,
        location,
        parameterName,
        required,
      ]),
    ).toEqual([
      ['itemId', 'path', 'item-id', true],
      ['page', 'query', 'page', false],
      ['q', 'query', 'q', true],
      ['xTenant', 'header', 'X-Tenant', true],
    ]);
    expect(method.parameters[2].schema).toBeUndefined();
    expect(method.body).toEqual({
      name: 'body',
      required: true,
      content: { kind: 'text' },
    });
    expect(warnings).toEqual([
      'POST /items/{item-id} leaves out its cookie parameter(s) session: the browser sends cookies, and fetch cannot set them.',
    ]);
  });

  it('names the body after the parameters, clear of their names', () => {
    const method = methodOf({
      paths: {
        '/items': {
          post: operation({
            parameters: [
              { name: 'body', in: 'query', schema: { type: 'string' } },
            ],
            requestBody: { content: { 'application/octet-stream': {} } },
          }),
        },
      },
    });
    expect(method.parameters.map(parameter => parameter.name)).toEqual([
      'body',
    ]);
    expect(method.body).toEqual({
      name: 'body2',
      required: false,
      content: { kind: 'binary' },
    });
  });

  it.each([
    ['application/json', { kind: 'json', optionalFields: ['id'] }],
    ['application/hal+json', { kind: 'json', optionalFields: ['id'] }],
    ['multipart/form-data', { kind: 'formData' }],
    ['application/x-www-form-urlencoded', { kind: 'urlEncoded' }],
    ['text/csv', { kind: 'text' }],
    ['application/octet-stream', { kind: 'binary' }],
  ])('reads a %s body as %j', (contentType, content) => {
    const method = methodOf({
      paths: {
        '/items': {
          post: operation({
            requestBody: {
              content: {
                [contentType]: {
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
            properties: { id: { type: 'string' }, name: { type: 'string' } },
            required: ['name'],
          },
        },
      },
    });
    expect(method.body?.content).toMatchObject(content);
  });

  it('reads a body through a request body reference, and none from one that points at nothing', () => {
    const body = (reference: string) =>
      methodOf({
        paths: {
          '/items': { post: operation({ requestBody: { $ref: reference } }) },
        },
        components: {
          requestBodies: {
            Upload: { content: { 'multipart/form-data': {} } },
          },
        },
      }).body;
    expect(body('#/components/requestBodies/Upload')?.content).toEqual({
      kind: 'formData',
    });
    expect(body('#/components/requestBodies/Missing')).toBeUndefined();
  });

  it.each([
    [
      'no success response',
      { '404': { description: 'no' } },
      { kind: 'response' },
    ],
    [
      'a JSON response',
      {
        '201': {
          content: { 'application/json': { schema: { type: 'integer' } } },
        },
      },
      { kind: 'json', schema: { type: 'integer' }, wildcard: false },
    ],
    [
      'a response of any media type',
      { '200': { content: { '*/*': { schema: { type: 'string' } } } } },
      { kind: 'json', schema: { type: 'string' }, wildcard: true },
    ],
    [
      'an event stream of anything',
      {
        '200': {
          content: { 'text/event-stream': { schema: { type: 'string' } } },
        },
      },
      { kind: 'eventStream', serverSentEvent: false },
    ],
    [
      'a text response',
      { '200': { content: { 'text/plain': {} } } },
      { kind: 'text' },
    ],
    [
      'a binary response',
      { '200': { content: { 'application/pdf': {} } } },
      { kind: 'response' },
    ],
  ] as [string, Responses, ApiMethodModel['returns']][])(
    'returns from %s',
    (_, responses, returns) => {
      expect(
        methodOf({ paths: { '/items': { get: operation({ responses }) } } })
          .returns,
      ).toEqual(returns);
    },
  );

  it.each([
    ['Event', false],
    ['ServerSentEventOfItem', true],
  ])('streams the items of an event stream of %s', (item, serverSentEvent) => {
    const method = methodOf({
      paths: {
        '/items': {
          get: operation({
            responses: {
              '200': {
                content: {
                  'text/event-stream': {
                    schema: { $ref: '#/components/schemas/Events' },
                  },
                },
              },
            },
          }),
        },
      },
      components: {
        schemas: {
          Events: {
            type: 'array',
            items: { $ref: `#/components/schemas/${item}` },
          },
          [item]: { type: 'object' },
        },
      },
    });
    expect(method.returns).toEqual({
      kind: 'eventStream',
      items: { $ref: `#/components/schemas/${item}` },
      serverSentEvent,
    });
  });

  it('documents a method with the operation and the descriptions of its parameters, required first', () => {
    const method = methodOf({
      paths: {
        '/items/{id}': {
          get: operation({
            summary: 'Get an item',
            description: 'By id',
            parameters: [
              { name: 'page', in: 'query', description: 'The page' },
              {
                name: 'id',
                in: 'path',
                required: true,
                description: 'The\n  item id',
              },
            ],
          }),
        },
      },
    });
    expect(method.docs).toEqual([
      'Get an item',
      'By id',
      '- operationId: `getItem`',
      '- path: `/items/{id}`',
      '@param id - The item id',
      '@param page - The page',
    ]);
  });

  describe('resource attribution parameters', () => {
    const tenantItems = (wow: boolean, config?: GeneratorConfiguration) =>
      methodOf(
        {
          ...(wow
            ? {
                info: {
                  title: 'T',
                  version: '1',
                  'x-wow-context-alias': 'shop',
                },
              }
            : {}),
          paths: {
            '/tenant/{tenantId}/items': {
              get: operation({
                parameters: [{ name: 'tenantId', in: 'path', required: true }],
              }),
            },
          },
        },
        config,
      ).parameters.map(parameter => parameter.name);

    it("leaves tenantId to Wow's interceptor in a Wow document only", () => {
      expect(tenantItems(true)).toEqual([]);
      expect(tenantItems(false)).toEqual(['tenantId']);
    });

    it('leaves out the path parameters the configuration names instead', () => {
      expect(
        tenantItems(true, {
          apiClients: { Items: { ignorePathParameters: [] } },
        }),
      ).toEqual(['tenantId']);
    });
  });

  describe('operations and tags left out', () => {
    it('skips an operation without operationId or tags, with a warning', () => {
      const { model, warnings } = analysis({
        paths: {
          '/a': { get: operation({ operationId: undefined }) },
          '/b': { get: operation({ tags: [] }) },
        },
      });
      expect(model.apiClients).toEqual([]);
      expect(warnings).toEqual([
        'Skipping GET /a: it has no operationId, and the operationId names its method.',
        'Skipping GET /b: it has no tag, and the tag names its API client.',
      ]);
    });

    it("leaves Wow's, the actuator's and the aggregates' tags to their own clients, without a warning", () => {
      const { model, warnings } = analyzeDocument(
        openAPIDocument({
          paths: {
            '/a': { get: operation({ tags: ['wow'], operationId: 'a' }) },
            '/b': { get: operation({ tags: ['Actuator'], operationId: 'b' }) },
            '/c': {
              get: operation({ tags: ['shop.order'], operationId: 'c' }),
            },
            '/d': {
              get: operation({
                tags: ['shop.order', 'Items'],
                operationId: 'd',
              }),
            },
          },
          tags: [{ name: 'wow' }, { name: 'shop.order' }],
        }),
        { aggregateTags: ['shop.order'] },
      );
      expect(model.apiClients).toEqual([]);
      expect(warnings).toEqual([]);
    });

    it('numbers the second of two tags that name the same client, with a warning', () => {
      const { model, warnings } = analysis({
        paths: {
          '/a': {
            get: operation({ tags: ['user-controller'], operationId: 'a' }),
          },
          '/b': {
            get: operation({ tags: ['UserController'], operationId: 'b' }),
          },
        },
      });
      expect(
        model.apiClients.map(({ tagName, className }) => [tagName, className]),
      ).toEqual([
        ['UserController', 'UserControllerApiClient'],
        ['user-controller', 'UserController2ApiClient'],
      ]);
      expect(warnings).toEqual([
        'Tags UserController and user-controller both name the API client UserControllerApiClient; user-controller generates UserController2ApiClient.',
      ]);
    });
  });
});
