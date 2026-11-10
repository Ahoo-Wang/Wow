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
 * Each form of exact-number marks, held to the wire: a Long past 2^53 and a
 * decimal with more digits than a double keeps are read and written back
 * exactly, at any depth, through recursion, in each branch of a union, and
 * on every path a client reads or writes JSON by.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DatasetClient,
  parseJson,
  StateClient,
  stringifyJson,
  textNumberOr,
  type TextNumbers,
  textNumbersBy,
} from '../src/index.js';
import { filter } from '../src/dsl.js';
import { ServiceRoutes } from '../src/routes.js';
import { stubFetch, testFetcher } from './fixtures/fetchStub';

/** A Long one past 2^53, which a double rounds to 9007199254740992. */
const LONG = '9007199254740993';
/** A decimal with more digits than a double keeps. */
const DECIMAL = '12345678901234567890.25';

const client = () => ({ fetcher: testFetcher() });

/** `text` read with `marks` and written back with them: the same text. */
function roundTrip(text: string, marks: TextNumbers): unknown {
  const read = parseJson(text, marks);
  expect(stringifyJson(read, marks)).toBe(text);
  return read;
}

afterEach(() => vi.unstubAllGlobals());

describe('exact numbers', () => {
  it('keep a Long and a decimal at depth 1, and leave an unmarked number a number', () => {
    expect(
      roundTrip(`{"id":${LONG},"price":${DECIMAL},"count":3}`, {
        id: true,
        price: true,
      }),
    ).toStrictEqual({ id: LONG, price: DECIMAL, count: 3 });
  });

  it('keep them at every depth of a recursive type, through its lazy marks', () => {
    const tree: TextNumbers = { amount: true, children: { '*': () => tree } };
    const node = (depth: number): string =>
      `{"amount":${LONG},"children":[${depth === 0 ? '' : node(depth - 1)}]}`;
    let read = roundTrip(node(5), tree) as {
      amount: unknown;
      children: unknown[];
    };
    for (let depth = 0; depth <= 5; depth++) {
      expect(read.amount).toBe(LONG);
      read = read.children[0] as typeof read;
    }
    expect(read).toBeUndefined();
  });

  it('keep them in each branch of a union its discriminant tells apart, and only there', () => {
    const price = textNumbersBy('kind', {
      decimal: { amount: true },
      long: { amount: true, id: true },
    });
    const marks: TextNumbers = { prices: { '*': price } };
    const text =
      `{"prices":[{"amount":${DECIMAL},"kind":"decimal"},` +
      `{"kind":"long","amount":${LONG},"id":${LONG}},` +
      `{"kind":"count","amount":3}]}`;
    expect(roundTrip(text, marks)).toStrictEqual({
      prices: [
        { amount: DECIMAL, kind: 'decimal' },
        { kind: 'long', amount: LONG, id: LONG },
        // The count branch is no exact number: `amount + 1` is 4.
        { kind: 'count', amount: 3 },
      ],
    });
  });

  it('take no case for a value a discriminant cannot choose by, which is no object', () => {
    const marks: TextNumbers = {
      price: textNumbersBy('kind', { decimal: { amount: true } }),
    };
    expect(stringifyJson({ price: 3 }, marks)).toBe('{"price":3}');
    expect(stringifyJson({ price: '3' }, marks)).toBe('{"price":"3"}');
    expect(parseJson('{"price":3}', marks)).toStrictEqual({ price: 3 });
  });

  it('keep a number or an object of numbers, whichever a value is', () => {
    const marks: TextNumbers = { price: textNumberOr({ amount: true }) };
    expect(roundTrip(`{"price":${DECIMAL}}`, marks)).toStrictEqual({
      price: DECIMAL,
    });
    expect(roundTrip(`{"price":{"amount":${DECIMAL}}}`, marks)).toStrictEqual({
      price: { amount: DECIMAL },
    });
    expect(
      roundTrip(`{"price":[${LONG}]}`, { price: textNumberOr({ '*': true }) }),
    ).toStrictEqual({ price: [LONG] });
  });

  it("read a state's recursive marks through StateClient", async () => {
    const state: TextNumbers = { amount: true, children: { '*': () => state } };
    stubFetch(
      () =>
        new Response(
          `{"version":1,"state":{"amount":${LONG},"children":[{"amount":${LONG},"children":[]}]}}`,
          { headers: { 'Content-Type': 'application/json' } },
        ),
    );
    const snapshot = await new StateClient<{
      amount: string;
      children: { amount: string }[];
    }>({ context: 'shop', name: 'tree', textNumbers: { state } }, client()).get(
      't1',
    );
    expect(snapshot.state.amount).toBe(LONG);
    expect(snapshot.state.children[0].amount).toBe(LONG);
  });

  it("write a service route's request and read its response exactly", async () => {
    const { requests } = stubFetch(
      () =>
        new Response(`{"amount":${LONG},"lines":[{"price":${DECIMAL}}]}`, {
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    const routes = new ServiceRoutes(client(), {
      manifest: {
        format: 1,
        contexts: [
          {
            name: 'shop',
            routes: [
              { name: 'money', route: { method: 'POST', path: '/shop/money' } },
            ],
          },
        ],
      },
      textNumbers: {
        'shop.money': {
          request: { amount: true },
          response: { amount: true, lines: { '*': { price: true } } },
        },
      },
    });
    const answer = await routes.call('shop', 'money', {
      body: { amount: LONG },
    });
    expect(requests[0].text).toBe(`{"amount":${LONG}}`);
    expect(answer).toStrictEqual({ amount: LONG, lines: [{ price: DECIMAL }] });
  });

  it("read a dataset's rows with the marks its caller knows", async () => {
    stubFetch(
      () =>
        new Response(`[{"total":${DECIMAL}}]`, {
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    const rows = await new DatasetClient(client())
      .dataset<{ total: string }>('shop', 'sales', { total: true })
      .list({ filter: filter.matchAll() });
    expect(rows).toStrictEqual([{ total: DECIMAL }]);
  });
});
