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

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  type AggregateType,
  CommandClient,
  EventQueryClient,
  SnapshotQueryClient,
  StateClient,
} from '../src/index.js';
import { filter } from '../src/dsl.js';
import { sseResponse, stubFetch, testFetcher } from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

/** The product type as wow-generator writes it, with its exact numbers. */
const PRODUCT: AggregateType = {
  context: 'eshop-catalog',
  name: 'product',
  textNumbers: {
    state: { price: true, sales: true },
    events: { 'eshop-catalog.product.ProductPriceChanged': { price: true } },
    commands: { ChangeProductPrice: { price: true } },
  },
};

const raw = (text: string) =>
  new Response(text, { headers: { 'Content-Type': 'application/json' } });
const client = () => ({ fetcher: testFetcher() });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('exact numbers through the clients (review R4-Q, Q-9)', () => {
  it('reads a state view and a snapshot page with their marked numbers as text', async () => {
    stubFetch(request =>
      request.url.endsWith('/state')
        ? raw(
            '{"version":1,"state":{"price":9007199254740993.10,"sales":12,"stock":3}}',
          )
        : raw(
            '{"total":1,"list":[{"aggregateId":"p1","state":{"price":19.90,"sales":9007199254740993}}]}',
          ),
    );
    const view = await new StateClient<{ price: string }>(
      PRODUCT,
      client(),
    ).get('p1');
    expect(view.state).toEqual({
      price: '9007199254740993.10',
      sales: '12',
      stock: 3,
    });
    const page = await new SnapshotQueryClient(PRODUCT, client()).paged({
      filter: filter.matchAll(),
    });
    expect(page.list[0]!.state).toEqual({
      price: '19.90',
      sales: '9007199254740993',
    });
  });

  it('reads an event body by its name, in a stream and in a trace', async () => {
    stubFetch(request =>
      request.url.endsWith('/trace')
        ? raw(
            '{"commits":[{"state":{"price":1.50},"events":[{"name":"eshop-catalog.product.ProductPriceChanged","body":{"price":1.50}}]}]}',
          )
        : sseResponse(
            'id:0\nevent:row\ndata:{"events":[{"body":{"price":2.50},"name":"eshop-catalog.product.ProductPriceChanged"},{"name":"eshop-catalog.product.ProductPublished","body":{"price":2.50}}]}\n\n' +
              'event:end\ndata:{"reason":"complete","resumable":true}\n\n',
          ),
    );
    const rows: unknown[] = [];
    for await (const row of await new EventQueryClient(
      PRODUCT,
      client(),
    ).stream({
      filter: filter.matchAll(),
    })) {
      rows.push(row);
    }
    expect(rows).toEqual([
      {
        events: [
          {
            body: { price: '2.50' },
            name: 'eshop-catalog.product.ProductPriceChanged',
          },
          {
            name: 'eshop-catalog.product.ProductPublished',
            body: { price: 2.5 },
          },
        ],
      },
    ]);
    const trace = await new StateClient(PRODUCT, client()).trace('p1');
    expect(trace.commits[0]).toEqual({
      state: { price: '1.50' },
      events: [
        {
          name: 'eshop-catalog.product.ProductPriceChanged',
          body: { price: '1.50' },
        },
      ],
    });
  });

  it('sends a command body with its marked numbers unquoted', async () => {
    const { requests } = stubFetch(() => raw('{"status":"succeeded"}'));
    await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
      bodyNumbers: {
        'eshop-catalog.product.ChangeProductPrice':
          PRODUCT.textNumbers!.commands!.ChangeProductPrice,
      },
    }).wait({
      command: 'eshop-catalog.product.ChangeProductPrice',
      body: { price: '9007199254740993.10', note: '1.5' },
      id: 'p1',
    });
    expect(requests[0]!.text).toBe(
      '{"price":9007199254740993.10,"note":"1.5"}',
    );
    expect(requests[0]!.headers.get('Content-Type')).toBe('application/json');
  });

  it('reads with plain JSON.parse when the type marks nothing', async () => {
    stubFetch(() => raw('{"version":1,"state":{"price":19.9}}'));
    const view = await new StateClient(
      { context: 'shop', name: 'cart' },
      client(),
    ).get('c1');
    expect(view.state).toEqual({ price: 19.9 });
  });
});
