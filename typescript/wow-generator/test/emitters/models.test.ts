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
 * The declaration a model's schema calls for, and its doc comment. How a
 * schema resolves to a type is table-driven in test/types/typeResolver.test.ts,
 * and what the types admit in test/models/.
 */

import type { OpenAPI, Schema } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { emitDocument, openAPIDocument } from '../support/emission';
import { writeModels } from '../support/models';
import { wowDocument } from '../support/specs';

/** Collapses every run of whitespace, which the indentation settles. */
function words(text: string): string {
  return text.replace(/\s+/g, ' ');
}

/** The text of the one statement a schema generates as `Model`, as words. */
function declarationOf(schema: Schema): string {
  const file = writeModels({ Model: schema });
  const statements = file.getStatements();
  expect(statements).toHaveLength(1);
  return words(statements[0].getText());
}

function expectDeclaration(schema: Schema, declaration: string): void {
  expect(declarationOf(schema)).toBe(words(declaration));
}

describe('model declarations', () => {
  it('writes a string enum as an enum', () => {
    expectDeclaration(
      { type: 'string', enum: ['value1', 'value2'] },
      "export enum Model { VALUE1 = 'value1', VALUE2 = 'value2' }",
    );
  });

  it('writes an object as an interface of its properties', () => {
    expectDeclaration(
      { type: 'object', properties: { id: { type: 'string' } } },
      'export interface Model { id: string; }',
    );
  });

  it('writes an array, a composition and a primitive as type aliases', () => {
    expectDeclaration(
      { type: 'array', items: { type: 'string' } },
      'export type Model = string[];',
    );
    expectDeclaration(
      { oneOf: [{ type: 'string' }, { type: 'number' }] },
      'export type Model = (string | number);',
    );
    expectDeclaration({ type: 'boolean' }, 'export type Model = boolean;');
  });

  it('writes an allOf as the intersection that excludes primitives', () => {
    expectDeclaration(
      {
        allOf: [
          { $ref: '#/components/schemas/BaseModel' },
          { type: 'object', properties: { extra: { type: 'boolean' } } },
        ],
      },
      'export type Model = globalThis.Exclude<(BaseModel & { extra: boolean; }), string | number | boolean | readonly unknown[]> & ({ readonly [globalThis.Symbol.iterator]?: never } | null);',
    );
  });

  it('writes a string-keyed map as an interface with an index signature', () => {
    expectDeclaration(
      { type: 'object', additionalProperties: { type: 'string' } },
      'export interface Model { [key: string]: string; }',
    );
  });

  it('adds an index signature after the properties for additional properties', () => {
    expectDeclaration(
      {
        type: 'object',
        properties: { id: { type: 'string' } },
        additionalProperties: true,
      },
      'export interface Model { id: string; /** Additional properties */ [key: string]: any; }',
    );
    expectDeclaration(
      {
        type: 'object',
        properties: { id: { type: 'number' } },
        required: ['id'],
        additionalProperties: { type: 'number' },
      },
      'export interface Model { id: number; /** Additional properties */ [key: string]: number; }',
    );
  });

  it('takes the intersection form when a required property clashes with the index signature', () => {
    // An interface may only carry a named property assignable to its index
    // signature (TS2411), which a `string` beside a `number` index is not
    // however the document declares it required.
    const text = declarationOf({
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
      additionalProperties: { type: 'number' },
    });
    expect(text).toMatch(/^export type Model = /);
    expect(text).toContain('globalThis.Record<string, number>');
  });
});

describe('model doc comments', () => {
  it('documents the model from its schema and its key', () => {
    const { file } = emitDocument(
      openAPIDocument({
        components: {
          schemas: {
            'com.example.Order': {
              type: 'object',
              title: 'An order',
              properties: { id: { type: 'string', description: 'Its id' } },
            },
          },
        },
      }),
    );
    expect(file('com/example/types.ts').getFullText()).toBe(
      '/**\n * An order\n * - key: com.example.Order\n */\nexport interface Order {\n    /** Its id */\n    id: string;\n}\n',
    );
  });

  it('embeds the complete schema with schemaDocs full', () => {
    const { file } = emitDocument(
      openAPIDocument({
        components: { schemas: { Flag: { type: 'boolean' } } },
      }),
      { schemaDocs: 'full' },
    );
    expect(file('types.ts').getFullText()).toContain(
      ' * - schema: \n * ```json\n * {\n *   "type": "boolean"\n * }\n * ```',
    );
  });

  it('reads the doc of a command body with what the Wow metadata lends it, and the type without', () => {
    const { file } = emitDocument(
      wowDocument({ commands: ['create_order'] }) as OpenAPI,
    );
    const types = file('shop/order/types.ts');
    const createOrder = types.getInterfaceOrThrow('CreateOrder');
    expect(createOrder.getJsDocs()[0].getInnerText()).toContain('create_order');
    expect(createOrder.getText()).toContain('id: string;');
  });

  it('writes a command or event body without fields as an empty record', () => {
    const { file } = emitDocument(
      wowDocument({
        events: [{ name: 'order_closed', body: { type: 'object' } }],
      }) as OpenAPI,
    );
    const alias = file('shop/order/types.ts').getTypeAliasOrThrow(
      'OrderClosed',
    );
    expect(alias.getTypeNodeOrThrow().getText()).toBe(
      'globalThis.Record<string, never>',
    );
    expect(alias.getJsDocs()[0].getInnerText()).toContain(
      '- key: shop.order.OrderClosed',
    );
  });
});
