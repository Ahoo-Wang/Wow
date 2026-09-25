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
 * Which component schemas become models, where, and with what doc: the
 * models and bounded contexts of the generation model.
 */

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { analyzeDocument, openAPIDocument } from '../support/emission';
import { wowDocument } from '../support/specs';

function modelsOf(openAPI: OpenAPI) {
  return analyzeDocument(openAPI).model.models;
}

describe('models', () => {
  it('declares a model per component schema, in document order, in the types.ts of its package', () => {
    const models = modelsOf(
      openAPIDocument({
        components: {
          schemas: {
            TestModel: {
              type: 'object',
              properties: { id: { type: 'string' } },
            },
            'wow.TestWow': { type: 'string' },
            'com.example.TestEnum': { type: 'string', enum: ['a', 'b'] },
          },
        },
      }),
    );
    expect(models.map(({ key, info, file }) => ({ key, info, file }))).toEqual([
      {
        key: 'TestModel',
        info: { name: 'TestModel', path: '/' },
        file: '/types.ts',
      },
      {
        key: 'com.example.TestEnum',
        info: { name: 'TestEnum', path: '/com/example' },
        file: '/com/example/types.ts',
      },
    ]);
  });

  it('declares none for a document without schemas', () => {
    expect(modelsOf(openAPIDocument({ components: {} }))).toEqual([]);
    expect(modelsOf(openAPIDocument({}))).toEqual([]);
  });

  it("leaves out the wrappers wow-client derives from an aggregate's state, not a business cursor", () => {
    const spec = wowDocument({ context: 'example', aggregate: 'order' });
    for (const key of [
      'example.order.OrderStateMaterializedSnapshotCursorPage',
      'example.order.OrderAggregatedDomainEventStreamCursorPage',
      'example.order.OrderCursorPage',
    ]) {
      spec.components.schemas[key] = { type: 'object' };
    }
    const keys = modelsOf(spec as OpenAPI).map(model => model.key);
    expect(keys).toContain('example.order.OrderCursorPage');
    expect(keys).not.toContain(
      'example.order.OrderStateMaterializedSnapshotCursorPage',
    );
    expect(keys).not.toContain(
      'example.order.OrderAggregatedDomainEventStreamCursorPage',
    );
  });

  it('fails on schemas that generate the same model', () => {
    const analysis = () =>
      modelsOf(
        openAPIDocument({
          components: {
            schemas: {
              'Foo-Bar': { type: 'object' },
              FooBar: { type: 'object' },
              'a.Item': { type: 'object' },
              'a.item': { type: 'string' },
              Unique: { type: 'object' },
            },
          },
        }),
      );
    expect(analysis).toThrow(GeneratorError);
    expect(analysis).toThrow(
      'Schemas generate the same model: Foo-Bar, FooBar → /FooBar; a.Item, a.item → /a/Item. Rename all but one of them in the document.',
    );
  });

  it('declares a command or event body without fields as an empty message body', () => {
    const spec = wowDocument({
      events: [
        { name: 'order_closed', body: { type: 'object' } },
        {
          name: 'order_paid',
          body: { type: 'object', additionalProperties: true },
        },
      ],
    });
    // Not a message body: an empty object that is only a model.
    spec.components.schemas['shop.order.Nothing'] = { type: 'object' };
    const empty = Object.fromEntries(
      modelsOf(spec as OpenAPI).map(model => [
        model.key,
        model.emptyMessageBody,
      ]),
    );
    expect(empty).toMatchObject({
      'shop.order.OrderClosed': true,
      'shop.order.OrderPaid': false,
      'shop.order.CreateOrder': false,
      'shop.order.Nothing': false,
    });
  });

  it('lends a command body the summary of its operation for its doc, leaving the schema as it is', () => {
    const spec = wowDocument({ commands: ['create_order'] });
    const [model] = modelsOf(spec as OpenAPI).filter(
      ({ key }) => key === 'shop.order.CreateOrder',
    );
    expect(model.docSchema).toMatchObject({ title: 'create_order' });
    expect(model.schema).not.toHaveProperty('title');
    expect(model.schema).toBe(
      spec.components.schemas['shop.order.CreateOrder'],
    );
  });
});

describe('bounded contexts', () => {
  it("declares the alias of every context with aggregates and of the document's own, sorted", () => {
    const spec = wowDocument({ context: 'shop' });
    spec.info['x-wow-context-alias'] = 'billing';
    expect(analyzeDocument(spec as OpenAPI).model.contexts).toEqual([
      {
        alias: 'billing',
        constantName: 'BILLING_BOUNDED_CONTEXT_ALIAS',
        file: 'billing/boundedContext.ts',
      },
      {
        alias: 'shop',
        constantName: 'SHOP_BOUNDED_CONTEXT_ALIAS',
        file: 'shop/boundedContext.ts',
      },
    ]);
  });

  it('declares none for a document that is not from Wow', () => {
    expect(analyzeDocument(openAPIDocument({})).model.contexts).toEqual([]);
  });
});
