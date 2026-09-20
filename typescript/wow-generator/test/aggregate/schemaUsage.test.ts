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
import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import {
  AggregateResolver,
  referencedSchemaKeys,
  requestSchemaKeys,
  SchemaUsageResolver,
} from '../../src/aggregate';
import demoSpec from '../demo.spec.json';

function resolveDemoUsage(): SchemaUsageResolver {
  const openAPI = demoSpec as unknown as OpenAPI;
  return new SchemaUsageResolver(
    openAPI,
    new AggregateResolver(openAPI).resolve(),
  );
}

describe('referencedSchemaKeys', () => {
  it('collects component schema references at any depth', () => {
    expect(
      [
        ...referencedSchemaKeys({
          type: 'object',
          properties: {
            direct: { $ref: '#/components/schemas/Direct' },
            nested: {
              type: 'array',
              items: { anyOf: [{ $ref: '#/components/schemas/Nested' }] },
            },
            extension: {
              'x-map-key-schema': { $ref: '#/components/schemas/Extension' },
            },
          },
        }),
      ].sort(),
    ).toEqual(['Direct', 'Extension', 'Nested']);
  });

  it('ignores references outside components/schemas', () => {
    expect([
      ...referencedSchemaKeys({
        $ref: '#/components/responses/wow.CommandOk',
      }),
    ]).toEqual([]);
  });

  it('terminates on cyclic structures', () => {
    const schema: Record<string, any> = {
      properties: { self: { $ref: '#/components/schemas/Self' } },
    };
    schema.properties.parent = schema;
    expect([...referencedSchemaKeys(schema)]).toEqual(['Self']);
  });
});

describe('requestSchemaKeys', () => {
  it('collects every demo command body', () => {
    const keys = requestSchemaKeys(demoSpec as unknown as OpenAPI);
    expect(keys).toContain('example.order.CreateOrder');
    expect(keys).toContain('example.cart.AddCartItem');
  });

  it('ignores response schemas', () => {
    expect(
      requestSchemaKeys({
        paths: {
          '/orders': {
            get: {
              operationId: 'order.list',
              responses: {
                '200': {
                  content: {
                    'application/json': {
                      schema: { $ref: '#/components/schemas/demo.Response' },
                    },
                  },
                },
              },
            },
          },
        },
      } as any),
    ).toEqual(new Set());
  });

  it('tolerates a document without paths', () => {
    expect(requestSchemaKeys({} as any)).toEqual(new Set());
  });
});

describe('SchemaUsageResolver', () => {
  const usage = resolveDemoUsage();

  it.each([
    ['example.order.WowExampleOrderState', 'read'],
    ['example.order.OrderItem', 'read'],
    ['example.order.OrderStatus', 'read'],
    ['example.order.OrderCreated', 'read'],
    ['example.order.CreateOrder', 'write'],
    ['example.order.CreateOrder.Item', 'write'],
    ['example.order.ShippingAddress', 'shared'],
    ['example.Link', 'unknown'],
  ])('classifies %s as %s', (schemaKey, expected) => {
    expect(usage.usageOf(schemaKey)).toBe(expected);
  });

  it('classifies an unresolvable key as unknown', () => {
    expect(usage.usageOf('does.not.Exist')).toBe('unknown');
  });

  it('reports schemas both sides reach', () => {
    expect(usage.sharedKeys()).toContain('example.order.ShippingAddress');
  });

  it('contests only shared schemas the read-model rule would change', () => {
    // Every ShippingAddress property is already declared required, so both
    // sides generate the same shape and there is nothing to report.
    expect(usage.contestedKeys()).toEqual([]);
  });

  it('contests a shared schema holding a non-nullable optional property', () => {
    const components = {
      schemas: {
        'demo.Command': {
          type: 'object',
          properties: { address: { $ref: '#/components/schemas/demo.Value' } },
        },
        'demo.State': {
          type: 'object',
          properties: { address: { $ref: '#/components/schemas/demo.Value' } },
        },
        'demo.Value': {
          type: 'object',
          required: ['city'],
          properties: {
            city: { type: 'string' },
            detail: { type: 'string' },
            note: { type: 'string', nullable: true },
          },
        },
      },
    };
    const contextAggregates = new Map([
      [
        'demo',
        new Set([
          {
            state: {
              key: 'demo.State',
              schema: components.schemas['demo.State'],
            },
            commands: new Map([
              [
                'create',
                {
                  schema: {
                    key: 'demo.Command',
                    schema: components.schemas['demo.Command'],
                  },
                },
              ],
            ]),
            events: new Map(),
          },
        ]),
      ],
    ]);
    const shared = new SchemaUsageResolver(
      { components } as any,
      contextAggregates as any,
    );
    expect(shared.usageOf('demo.Value')).toBe('shared');
    expect(shared.contestedKeys()).toEqual(['demo.Value']);
  });

  it('skips a dangling reference and a schema that is itself a reference', () => {
    const components = {
      schemas: {
        'demo.Command': {
          type: 'object',
          properties: {
            alias: { $ref: '#/components/schemas/demo.Alias' },
            missing: { $ref: '#/components/schemas/demo.Missing' },
          },
        },
        'demo.State': {
          type: 'object',
          properties: { alias: { $ref: '#/components/schemas/demo.Alias' } },
        },
        'demo.Alias': { $ref: '#/components/schemas/demo.Command' },
      },
    };
    const contextAggregates = new Map([
      [
        'demo',
        new Set([
          {
            state: {
              key: 'demo.State',
              schema: components.schemas['demo.State'],
            },
            commands: new Map([
              [
                'create',
                {
                  schema: {
                    key: 'demo.Command',
                    schema: components.schemas['demo.Command'],
                  },
                },
              ],
            ]),
            events: new Map(),
          },
        ]),
      ],
    ]);
    const resolver = new SchemaUsageResolver(
      { components } as any,
      contextAggregates as any,
    );

    expect(resolver.usageOf('demo.Missing')).toBe('unknown');
    expect(resolver.usageOf('demo.Alias')).toBe('shared');
    // demo.Alias is a bare reference with no properties of its own, so it is
    // never contested; the command it aliases is, since the state reaches it.
    expect(resolver.contestedKeys()).toEqual(['demo.Command']);
  });

  it('treats an ordinary operation request body as the write side', () => {
    const components = {
      schemas: {
        'demo.State': {
          type: 'object',
          properties: { draft: { $ref: '#/components/schemas/demo.Draft' } },
        },
        'demo.Draft': {
          type: 'object',
          properties: { title: { type: 'string' } },
        },
        'demo.Filter': {
          type: 'object',
          properties: { keyword: { type: 'string' } },
        },
        'demo.Body': {
          type: 'object',
          properties: { note: { type: 'string' } },
        },
      },
      requestBodies: {
        'demo.Imported': {
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/demo.Body' },
            },
          },
        },
      },
    };
    const openAPI = {
      components,
      paths: {
        '/drafts': {
          post: {
            operationId: 'draft.import',
            requestBody: {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/demo.Draft' },
                },
              },
            },
          },
          get: {
            operationId: 'draft.list',
            parameters: [
              {
                name: 'filter',
                in: 'query',
                schema: { $ref: '#/components/schemas/demo.Filter' },
              },
            ],
          },
        },
      },
    };
    const contextAggregates = new Map([
      [
        'demo',
        new Set([
          {
            state: {
              key: 'demo.State',
              schema: components.schemas['demo.State'],
            },
            commands: new Map(),
            events: new Map(),
          },
        ]),
      ],
    ]);
    const resolver = new SchemaUsageResolver(
      openAPI as any,
      contextAggregates as any,
    );

    // The state reaches demo.Draft, but so does a plain request body: making
    // its properties required would reject a request the document allows.
    expect(resolver.usageOf('demo.Draft')).toBe('shared');
    expect(resolver.usageOf('demo.Filter')).toBe('write');
    expect(resolver.usageOf('demo.Body')).toBe('write');
    expect(resolver.usageOf('demo.State')).toBe('read');
    expect(resolver.contestedKeys()).toEqual(['demo.Draft']);
  });

  it('classifies nothing when the document has no aggregates', () => {
    const empty = new SchemaUsageResolver({} as any, new Map());
    expect(empty.usageOf('anything')).toBe('unknown');
    expect(empty.sharedKeys()).toEqual([]);
  });
});
