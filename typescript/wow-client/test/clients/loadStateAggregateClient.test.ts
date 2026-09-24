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
import {
  LoadStateAggregateClient,
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '../../src';
import {
  BASE_URL,
  describeClientCases,
  jsonResponse,
  stubFetch,
  testFetcher,
  type ClientCase,
} from './fetchStub';

interface CartState {
  items: string[];
}

type Client = LoadStateAggregateClient<CartState>;

const state = { items: ['p-1'] };

const cases: ClientCase<Client>[] = [
  {
    name: 'load',
    call: (c, abort) => c.load('cart-1', undefined, abort),
    method: 'GET',
    path: '/cart/cart-1/state',
    result: state,
  },
  {
    name: 'loadVersioned',
    call: (c, abort) => c.loadVersioned('cart-1', 3, undefined, abort),
    method: 'GET',
    path: '/cart/cart-1/state/3',
    result: state,
  },
  {
    name: 'loadTimeBased',
    call: (c, abort) =>
      c.loadTimeBased('cart-1', 1_700_000_000_000, undefined, abort),
    method: 'GET',
    path: '/cart/cart-1/state/time/1700000000000',
    result: state,
  },
];

describe('LoadStateAggregateClient', () => {
  describeClientCases<Client>(
    () =>
      new LoadStateAggregateClient({
        basePath: 'cart',
        fetcher: testFetcher(),
      }),
    cases,
  );

  it('sends to the base path QueryClientFactory builds', async () => {
    const { requests } = stubFetch(() => jsonResponse(state));
    const factory = new QueryClientFactory<CartState>({
      contextAlias: 'example',
      aggregateName: 'cart',
      resourceAttribution: ResourceAttributionPathSpec.TENANT,
      urlParams: { path: { tenantId: 't-1' } },
      fetcher: testFetcher(),
    });

    await factory.createLoadStateAggregateClient().load('cart-1');
    await factory
      .createLoadStateAggregateClient({ basePath: 'custom' })
      .loadVersioned('cart-1', 2);

    expect(requests.map(r => r.url)).toStrictEqual([
      `${BASE_URL}/example/tenant/t-1/cart/cart-1/state`,
      `${BASE_URL}/custom/cart-1/state/2`,
    ]);
  });
});
