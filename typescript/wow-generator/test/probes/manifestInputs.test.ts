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
 * Probes of what a caller sends: a read-only property, a computed one the
 * service writes and never reads, is required in the type the service writes
 * and optional, never absent, in the input a caller sends, at any depth.
 */

import { afterAll, describe, expect, it } from 'vitest';
import { removeDirectories } from '../support/generation';
import { generateCompiling, sharedGeneration } from '../support/probes';
import { manifest, schema } from '../support/manifestProbes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const computed = { type: 'string', readOnly: true };
const ref = (key: string) => ({ $ref: schema(key) });

/** The issue's reproduction (#6642), and the places an input may hold a computed property. */
const document = manifest(
  {
    state: { schema: schema('a.OrderState') },
    commands: [
      {
        name: 'CreateOrder',
        schema: schema('a.CreateOrder'),
        creation: 'required',
        routes: [{ method: 'POST', path: '/probe/thing' }],
      },
      {
        name: 'Rename',
        schema: schema('a.Rename'),
        routes: [{ method: 'POST', path: '/probe/thing/{id}/rename' }],
      },
    ],
  },
  {
    'a.ProductRef': {
      type: 'object',
      required: ['id', 'code', 'brandName', 'searchCode'],
      properties: {
        id: { type: 'string' },
        code: { type: 'string' },
        brandName: { type: 'string' },
        searchCode: computed,
      },
    },
    'a.Card': {
      type: 'object',
      required: ['kind', 'masked'],
      properties: { kind: { const: 'card' }, masked: computed },
    },
    'a.Wallet': {
      type: 'object',
      required: ['kind'],
      properties: { kind: { const: 'wallet' } },
    },
    'a.CreateOrder': {
      type: 'object',
      required: ['product', 'lines', 'byCode', 'payment', 'note', 'label'],
      properties: {
        product: ref('a.ProductRef'),
        lines: { type: 'array', items: ref('a.ProductRef') },
        byCode: { type: 'object', additionalProperties: ref('a.ProductRef') },
        payment: { oneOf: [ref('a.Card'), ref('a.Wallet')] },
        note: {
          type: 'object',
          required: ['text', 'length'],
          properties: { text: { type: 'string' }, length: computed },
        },
        label: computed,
      },
    },
    // Holds nothing computed: one type, as before.
    'a.Rename': {
      type: 'object',
      required: ['name'],
      properties: { name: { type: 'string' } },
    },
    'a.OrderState': {
      type: 'object',
      required: ['id', 'product', 'audit'],
      properties: {
        id: { type: 'string' },
        product: ref('a.ProductRef'),
        audit: ref('a.Audit'),
      },
    },
    // Holds a computed property, but no caller sends it: no input of it.
    'a.Audit': {
      type: 'object',
      required: ['by', 'summary'],
      properties: { by: { type: 'string' }, summary: computed },
    },
    // A type of the service's own whose name the input of ProductRef would take.
    'a.ProductRefInput': {
      type: 'object',
      required: ['raw'],
      properties: { raw: { type: 'string' } },
    },
    'a.Search': {
      type: 'object',
      required: ['product'],
      properties: { product: ref('a.ProductRef') },
    },
  },
  {
    routes: [
      {
        name: 'search',
        route: { method: 'POST', path: '/probe/search' },
        request: ref('a.Search'),
        response: ref('a.ProductRefInput'),
      },
    ],
  },
);

const consumer = `
import type { OrderState, ProductRef } from './out/probe/types.js';
import { ProbeRoutes, ThingCommandClient } from './out/index.js';
declare const state: OrderState;
const commands = new ThingCommandClient();
const product = { id: 'p', code: 'c', brandName: 'b' };
const rest = {
  lines: [product],
  byCode: { c: product },
  payment: { kind: 'card' as const },
  note: { text: 'n' },
};
// The issue's reproduction: the computed properties left out, at every depth.
void commands.createOrder({ product, ...rest });
// A value read from the service, its computed properties in it, handed back.
void commands.createOrder({
  product: state.product,
  lines: [state.product],
  byCode: { c: state.product },
  payment: { kind: 'card', masked: '**42' },
  note: { text: 'n', length: '1' },
  label: 'l',
});
// @ts-expect-error a computed property is still typed
void commands.createOrder({ product: { ...product, searchCode: 1 }, ...rest });
// @ts-expect-error a property the service reads is still required
void commands.createOrder({ product: { id: 'p', code: 'c' }, ...rest });
void new ProbeRoutes().search({ product });
// What the service writes keeps the property required and read-only.
// @ts-expect-error searchCode is required in what the service writes
const written: ProductRef = { id: 'p', code: 'c', brandName: 'b' };
void written;
// @ts-expect-error searchCode is read-only
state.product.searchCode = 'x';
`;

const generated = sharedGeneration(() => generateCompiling(document, consumer));

describe('A computed property in what a caller sends', () => {
  it('is optional in the input of every type a caller sends that holds one, at any depth', async () => {
    const types = (await generated()).read('probe/types.ts');
    expect(types).toContain(
      'export interface ProductRef {\n  id: string;\n  code: string;\n  brandName: string;\n  readonly searchCode: string;\n}',
    );
    expect(types).toContain(
      'export interface ProductRefInput2 {\n  id: string;\n  code: string;\n  brandName: string;\n  readonly searchCode?: string;\n}',
    );
    expect(types).toContain(
      'export interface CreateOrderInput {\n  product: ProductRefInput2;\n  lines: ProductRefInput2[];\n  byCode: Record<string, ProductRefInput2>;\n  payment: CardInput | Wallet;\n  note: { text: string; readonly length?: string; };\n  readonly label?: string;\n}',
    );
    expect(types).toContain('export interface CardInput {');
    expect(types).toContain(
      'export interface SearchInput {\n  product: ProductRefInput2;\n}',
    );
  });

  it('names in its doc the type it is the input of', async () => {
    const types = (await generated()).read('probe/types.ts');
    expect(types).toContain(
      '/**\n * What a caller sends for `ProductRef`: its read-only properties, at any depth, which the service computes and never reads, may be left out or handed back.\n * - key: `a.ProductRef`\n */\nexport interface ProductRefInput2 {',
    );
  });

  it("takes a service's own type of the input's name before the input", async () => {
    const types = (await generated()).read('probe/types.ts');
    expect(types).toContain(
      'export interface ProductRefInput {\n  raw: string;\n}',
    );
    expect((await generated()).read('probe/routes.ts')).toContain(
      'search(body: SearchInput, options?: RequestOptions): Promise<ProductRefInput>',
    );
  });

  it('declares no input of a type no caller sends, nor of one holding nothing computed', async () => {
    const { read } = await generated();
    const types = read('probe/types.ts');
    expect(types).toContain('readonly summary: string;');
    expect(types).not.toContain('AuditInput');
    expect(types).not.toContain('RenameInput');
    expect(types).not.toContain('OrderStateInput');
    expect(types).not.toContain('WalletInput');
    const client = read('probe/thing/commandClient.ts');
    expect(client).toContain(
      'createOrder(body: CreateOrderInput, options?: CommandOptions)',
    );
    expect(client).toContain(
      'rename(id: string, body: Rename, options?: CommandOptions)',
    );
  });
});
