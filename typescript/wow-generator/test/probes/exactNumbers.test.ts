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
 * Exact numbers through a generated client, held to the wire (#5448, #5529,
 * #5530): a Long past 2^53 and a decimal with more digits than a double
 * keeps are read and written exactly, at depth 1, through recursion, in each
 * branch of a union, in a command body and a service route, and the
 * generated types say `string` where the client answers one.
 */

import { afterAll, describe, expect, it } from 'vitest';
import {
  BUNDLER_OPTIONS,
  removeDirectories,
  runGenerated,
  typeCheck,
} from '../support/generation';
import { generateCompiling, generateFailing } from '../support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

const LONG = '9007199254740993';
const DECIMAL = '12345678901234567890.25';

const ref = (key: string) => ({ $ref: `#/$defs/${key}` });
const long = { type: 'integer', format: 'int64' };
const decimal = { type: 'number', format: 'decimal' };
const object = (
  properties: Record<string, unknown>,
  required = Object.keys(properties),
) => ({ type: 'object', required, properties });
/** A subtype as Jackson's `@JsonTypeInfo` writes it into the Manifest: its discriminator in an `allOf` part. */
const subtype = (kind: string, properties: Record<string, unknown>) => ({
  allOf: [
    { type: 'object', properties: { kind: { const: kind } } },
    object(properties),
  ],
});

const MANIFEST = {
  format: 1,
  contexts: [
    {
      name: 'shop',
      aggregates: [
        {
          name: 'tree',
          state: { schema: '#/$defs/shop.Tree' },
          commands: [
            {
              name: 'Graft',
              schema: '#/$defs/shop.Graft',
              routes: [{ method: 'POST', path: '/shop/tree/{id}/graft' }],
            },
          ],
        },
        { name: 'price', state: { schema: '#/$defs/shop.Prices' } },
      ],
      routes: [
        {
          name: 'money',
          route: { method: 'POST', path: '/shop/money' },
          request: ref('shop.Money'),
          response: ref('shop.Money'),
        },
      ],
    },
  ],
  $defs: {
    'shop.Tree': object(
      {
        amount: long,
        children: { type: 'array', items: ref('shop.Tree') },
        // The tree again, with an exact number beside it.
        graft: { allOf: [ref('shop.Tree'), object({ fee: decimal })] },
      },
      ['amount', 'children'],
    ),
    'shop.Graft': object({ branch: ref('shop.Tree') }),
    'shop.Prices': object({
      either: {
        anyOf: [{ type: 'null' }, decimal, object({ amount: decimal })],
      },
      tagged: {
        oneOf: [
          object({ kind: { const: 'decimal' }, amount: decimal }),
          object({
            kind: { const: 'count' },
            amount: { type: 'integer', format: 'int32' },
          }),
        ],
      },
      shape: ref('shop.Shape'),
    }),
    'shop.Shape': {
      anyOf: [
        subtype('circle', { radius: decimal }),
        subtype('square', { side: { type: 'number', format: 'double' } }),
        subtype('stack', { count: long, top: ref('shop.Shape') }),
      ],
    },
    'shop.Money': object({
      amount: long,
      lines: { type: 'array', items: object({ price: decimal }) },
    }),
  },
};

describe('exact numbers through a generated client', () => {
  it('reads and writes them exactly through recursion, unions, a command body and a service route', async () => {
    const generation = await generateCompiling(
      MANIFEST,
      `import { ShopClient, type Tree, type Prices, type Money } from './out/index.js';
       declare const shop: ShopClient;
       const tree: Tree = await shop.tree.state.get('t1').then(snapshot => snapshot.state);
       const deep: string = tree.children[0]!.children[0]!.amount;
       const fee: string | undefined = tree.graft?.fee;
       const prices: Prices = await shop.price.state.get('p1').then(snapshot => snapshot.state);
       const count: number = prices.tagged.kind === 'count' ? prices.tagged.amount + 1 : 0;
       const exact: string = prices.tagged.kind === 'decimal' ? prices.tagged.amount : '';
       const money: Money = await shop.routes.money({ amount: '1', lines: [] });
       const amount: string = money.amount;
       void [deep, fee, count, exact, amount];`,
    );
    directories.push(generation.dir);
    expect(generation.read('shop/textNumbers.ts')).toContain(
      "export const treeNumbers: TextNumbers = { amount: true, children: { '*': () => treeNumbers }, graft: () => treeNumbers2 };",
    );
    // The tree with its fee recurs as the tree does: a constant of its own.
    expect(generation.read('shop/textNumbers.ts')).toContain(
      "/** The exact numbers of `shop.Tree` with what an `allOf` says beside it, a recursive type: its own marks, and others', read them lazily. */\nexport const treeNumbers2: TextNumbers = { amount: true, children: { '*': () => treeNumbers }, graft: () => treeNumbers2, fee: true };",
    );
    const output = generation.output;
    expect(typeCheck(output, BUNDLER_OPTIONS)).toEqual([]);
    const printed = runGenerated(
      output,
      `const answers = {
  '/shop/tree/t1/state': '{"version":1,"state":{"amount":${LONG},"children":[{"amount":${LONG},"children":[{"amount":${LONG},"children":[]}]}],"graft":{"amount":${LONG},"children":[],"fee":${DECIMAL},"graft":{"amount":${LONG},"children":[],"fee":${DECIMAL}}}}}',
  '/shop/price/p1/state': '{"version":1,"state":{"either":${DECIMAL},"tagged":{"kind":"count","amount":3},"shape":{"kind":"stack","count":${LONG},"top":{"kind":"circle","radius":${DECIMAL}}}}}',
  '/shop/price/p2/state': '{"version":1,"state":{"either":{"amount":${DECIMAL}},"tagged":{"amount":${DECIMAL},"kind":"decimal"},"shape":{"kind":"square","side":0.1}}}',
  '/shop/money': '{"amount":${LONG},"lines":[{"price":${DECIMAL}}]}',
  '/shop/tree/t1/graft': '{"status":"succeeded"}',
};
globalThis.fetch = async (url, init) => {
  requests.push({ url: String(url), body: init?.body ?? null });
  const path = new URL(String(url)).pathname;
  return new Response(answers[path], { headers: { 'Content-Type': 'application/json' } });
};
const fetcher = new Fetcher({ baseURL: 'http://shop', headers: {} });
const { ShopClient } = await import('./shop/index.js');
const shop = new ShopClient({ fetcher });
const tree = (await shop.tree.state.get('t1')).state;
const first = (await shop.price.state.get('p1')).state;
const second = (await shop.price.state.get('p2')).state;
const money = await shop.routes.money({ amount: '${LONG}', lines: [{ price: '${DECIMAL}' }] });
await shop.tree.commands.graft('t1', { branch: { amount: '${LONG}', children: [{ amount: '${LONG}', children: [] }] } });
console.log(JSON.stringify({ tree, first, second, money, sent: requests.filter(r => r.body).map(r => r.body) }));`,
    );
    const { tree, first, second, money, sent } = JSON.parse(printed);
    expect(tree).toEqual({
      amount: LONG,
      children: [{ amount: LONG, children: [{ amount: LONG, children: [] }] }],
      graft: {
        amount: LONG,
        children: [],
        fee: DECIMAL,
        graft: { amount: LONG, children: [], fee: DECIMAL },
      },
    });
    expect(first).toEqual({
      either: DECIMAL,
      // The count branch is a number: amount + 1 is 4.
      tagged: { kind: 'count', amount: 3 },
      shape: {
        kind: 'stack',
        count: LONG,
        top: { kind: 'circle', radius: DECIMAL },
      },
    });
    expect(second).toEqual({
      either: { amount: DECIMAL },
      tagged: { amount: DECIMAL, kind: 'decimal' },
      shape: { kind: 'square', side: 0.1 },
    });
    expect(money).toEqual({ amount: LONG, lines: [{ price: DECIMAL }] });
    expect(sent).toEqual([
      `{"amount":${LONG},"lines":[{"price":${DECIMAL}}]}`,
      `{"branch":{"amount":${LONG},"children":[{"amount":${LONG},"children":[]}]}}`,
    ]);
  }, 60000);

  it('refuses a union whose alternatives read a number differently with nothing to tell them apart', async () => {
    const error = await generateFailing({
      format: 1,
      contexts: [
        {
          name: 'shop',
          aggregates: [
            { name: 'price', state: { schema: '#/$defs/shop.Price' } },
          ],
        },
      ],
      $defs: {
        'shop.Price': object({
          quote: {
            anyOf: [
              object({ amount: decimal, currency: { type: 'string' } }),
              object({ amount: { type: 'integer', format: 'int32' } }),
            ],
          },
        }),
      },
    });
    expect(error).toContain(
      'The alternatives of shop.Price.quote disagree at amount: one is `decimal`, read as its exact text, another `int32`, read as a number, and no member with a constant value tells them apart. Give the alternatives a discriminator (@JsonTypeInfo with @JsonSubTypes), or the same number format.',
    );
  });
});
