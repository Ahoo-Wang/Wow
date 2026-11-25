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

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { analyzeManifest } from '../../src/manifest/analyze';
import type { JsonSchema, ManifestDocument } from '../../src/manifest/document';
import { SchemaTypes } from '../../src/manifest/schemaTypes';
import { PACKAGE_ROOT } from '../support/generation';

const ref = (key: string) => ({ $ref: `#/$defs/${key}` });

/** The state fields of the state `a.State` among `defs`. */
const fieldsOf = (defs: Record<string, JsonSchema>) =>
  new SchemaTypes(defs).fields(ref('a.State'), 'state');

describe("an aggregate's state fields, as the Kotlin StateFields.of walks them", () => {
  it("names the paths within the eShop order's items, as EshopOrderingContract.Order does", () => {
    const ordering = JSON.parse(
      readFileSync(
        join(PACKAGE_ROOT, 'test', 'manifests', 'eshop-ordering.json'),
        'utf8',
      ),
    ) as ManifestDocument;
    const order = analyzeManifest(ordering).model.contexts[0].aggregates[0];
    // STATE__ITEMS__PRODUCT_ID, STATE__ITEMS__QUANTITY, STATE__ITEMS__UNIT_PRICE.
    expect(order.stateFields).toEqual(
      expect.arrayContaining([
        'state.items',
        'state.items.productId',
        'state.items.quantity',
        'state.items.unitPrice',
      ]),
    );
  });

  it('reads a nullable alternative, of anyOf or oneOf, as the value it holds', () => {
    expect(
      fieldsOf({
        'a.State': {
          type: 'object',
          properties: {
            address: { anyOf: [{ type: 'null' }, ref('a.Address')] },
            billing: { oneOf: [ref('a.Address'), { type: 'null' }] },
          },
        },
        'a.Address': {
          type: 'object',
          properties: { city: { type: 'string' } },
        },
      }),
    ).toEqual([
      'state.address',
      'state.address.city',
      'state.billing',
      'state.billing.city',
    ]);
  });

  it('names the elements of an array of arrays, and of a nullable array, after the array', () => {
    const line = {
      type: 'object',
      properties: { sku: { type: 'string' } },
    };
    expect(
      fieldsOf({
        'a.State': {
          type: 'object',
          properties: {
            grid: { type: 'array', items: { type: 'array', items: line } },
            lines: {
              anyOf: [{ type: 'array', items: line }, { type: 'null' }],
            },
          },
        },
      }),
    ).toEqual([
      'state.grid',
      'state.grid.sku',
      'state.lines',
      'state.lines.sku',
    ]);
  });

  it('stops at a definition already on the path, a polymorphic value and a map', () => {
    expect(
      fieldsOf({
        'a.State': {
          type: 'object',
          properties: {
            children: { type: 'array', items: ref('a.State') },
            payment: {
              anyOf: [ref('a.Card'), ref('a.Cash'), { type: 'null' }],
            },
            labels: {
              type: 'object',
              additionalProperties: { type: 'string' },
            },
          },
        },
        'a.Card': { type: 'object', properties: { last4: {} } },
        'a.Cash': { type: 'object', properties: { amount: {} } },
      }),
    ).toEqual(['state.children', 'state.payment', 'state.labels']);
  });

  it('takes a property it cannot read into as a leaf: a schema of true, another document, a missing definition', () => {
    expect(
      fieldsOf({
        'a.State': {
          type: 'object',
          properties: {
            any: true,
            remote: { $ref: 'other.json#/Address' },
            gone: ref('a.Missing'),
          },
        },
      }),
    ).toEqual(['state.any', 'state.remote', 'state.gone']);
  });
});

describe('a parameterized type', () => {
  const tree = 'a.Node(java.lang.String?)';
  const defs: Record<string, JsonSchema> = {
    [tree]: {
      type: 'object',
      properties: { children: { type: 'array', items: ref(tree) } },
    },
    'a.Page(a.b.Item,java.util.List(a.Outer$Inner))': {
      type: 'object',
      properties: {},
    },
  };

  it('is written in place unless it refers to itself', () => {
    const types = new SchemaTypes(defs);
    expect(types.inlined(tree)).toBe(false);
    expect(
      types.inlined('a.Page(a.b.Item,java.util.List(a.Outer$Inner))'),
    ).toBe(true);
    expect(types.inlined('a.Node')).toBe(false);
  });

  it('is named by its class, Of and its arguments, several joined by And and nested ones in their place', () => {
    const map = 'java.util.Map(java.lang.String,java.util.List(a.Item?))';
    const types = new SchemaTypes(defs);
    types.name([...Object.keys(defs), map], new Set());
    expect(types.names.get(tree)).toBe('NodeOfString');
    expect(
      types.names.get('a.Page(a.b.Item,java.util.List(a.Outer$Inner))'),
    ).toBe('PageOfItemAndListOfOuterInner');
    expect(types.names.get(map)).toBe('MapOfStringAndListOfItem');
  });

  it("qualifies a clash by its class's package, as other names are, and numbers it once that is used up", () => {
    const types = new SchemaTypes({});
    types.name(
      [
        'a.Node(java.lang.String)',
        'b.Node(java.lang.String)',
        'c.NodeOfString',
        'Node(x.String)',
        'Node(y.String)',
      ],
      new Set(),
    );
    expect(Object.fromEntries(types.names)).toEqual({
      // Its arguments' packages qualify nothing.
      'Node(x.String)': 'NodeOfString',
      'Node(y.String)': 'NodeOfString2',
      'a.Node(java.lang.String)': 'ANodeOfString',
      'b.Node(java.lang.String)': 'BNodeOfString',
      'c.NodeOfString': 'CNodeOfString',
    });
  });
});
