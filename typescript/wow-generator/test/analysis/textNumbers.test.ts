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
import { analyzeManifest } from '../../src/manifest/analyze';
import type { ManifestDocument } from '../../src/manifest/document';

const decimal = { type: 'number', format: 'decimal' };

const manifest: ManifestDocument = {
  format: 1,
  errors: [],
  contexts: [
    {
      name: 'shop',
      subscriptions: [],
      published: [],
      errors: [],
      aggregates: [
        {
          name: 'cart',
          state: { schema: '#/$defs/Cart' },
          snapshot: { enabled: true },
          queries: [],
          commands: [
            {
              name: 'Pay',
              aliases: [],
              schema: '#/$defs/Pay',
              creation: 'none',
              routes: [{ method: 'POST', path: '/shop/cart/{id}/pay' }],
            },
            {
              name: 'Note',
              aliases: [],
              schema: '#/$defs/Note',
              creation: 'none',
              routes: [{ method: 'POST', path: '/shop/cart/{id}/note' }],
            },
          ],
          events: [
            { name: 'Paid', aliases: [], revision: 1, schema: '#/$defs/Pay' },
            { name: 'Noted', aliases: [], revision: 1, schema: '#/$defs/Note' },
          ],
        },
      ],
    },
  ],
  $defs: {
    Cart: {
      type: 'object',
      properties: {
        id: { type: 'integer', format: 'int64' },
        count: { type: 'integer' },
        lines: { type: 'array', items: { $ref: '#/$defs/Line' } },
        totals: {
          type: 'object',
          additionalProperties: { type: ['number', 'null'], format: 'decimal' },
        },
        discount: {
          anyOf: [
            { type: 'null' },
            decimal,
            { type: 'object', properties: { rate: decimal } },
          ],
        },
        parent: { $ref: '#/$defs/Cart' },
        missing: { $ref: '#/$defs/Gone' },
      },
    },
    Line: {
      type: 'object',
      properties: {
        amount: decimal,
        big: { type: 'integer', format: 'bigint' },
        sku: { type: 'string' },
      },
    },
    Pay: { type: 'object', properties: { amount: decimal } },
    Note: { type: 'object', properties: { text: { type: 'string' } } },
  },
};

describe('the exact numbers of a Manifest (review R4-Q, Q-9)', () => {
  it('marks int64, bigint and decimal numbers through arrays, maps and alternatives, not through recursion', () => {
    const aggregate =
      analyzeManifest(manifest).model.contexts[0]!.aggregates[0]!;
    expect(aggregate.textNumbers).toEqual({
      state: {
        id: true,
        lines: { '*': { amount: true, big: true } },
        totals: { '*': true },
        discount: { rate: true },
      },
      events: { 'shop.cart.Paid': { amount: true } },
      commands: { Pay: { amount: true } },
    });
    expect(
      aggregate.commands.map(command => [command.name, command.bodyNumbers]),
    ).toEqual([
      ['Pay', true],
      ['Note', undefined],
    ]);
  });

  it('marks nothing on an aggregate without such numbers', () => {
    const plain: ManifestDocument = {
      ...manifest,
      $defs: {
        ...manifest.$defs,
        Cart: { type: 'object', properties: { count: { type: 'integer' } } },
        Pay: manifest.$defs!.Note!,
      },
    };
    expect(
      analyzeManifest(plain).model.contexts[0]!.aggregates[0]!.textNumbers,
    ).toBeUndefined();
  });
});
