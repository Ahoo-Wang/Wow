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
  LoadOwnerStateAggregateClient,
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

type Client = LoadOwnerStateAggregateClient<CartState>;

const state = { items: ['p-1'] };

const cases: ClientCase<Client>[] = [
  {
    name: 'load',
    call: (c, abort) => c.load(undefined, abort),
    method: 'GET',
    path: '/owner/o-1/cart/state',
    result: state,
  },
  {
    name: 'loadVersioned',
    call: (c, abort) => c.loadVersioned(3, undefined, abort),
    method: 'GET',
    path: '/owner/o-1/cart/state/3',
    result: state,
  },
  {
    name: 'loadTimeBased',
    call: (c, abort) => c.loadTimeBased(1_700_000_000_000, undefined, abort),
    method: 'GET',
    path: '/owner/o-1/cart/state/time/1700000000000',
    result: state,
  },
];

describe('LoadOwnerStateAggregateClient', () => {
  describeClientCases<Client>(
    () =>
      new LoadOwnerStateAggregateClient({
        basePath: 'owner/{ownerId}/cart',
        urlParams: { path: { ownerId: 'o-1' } },
        fetcher: testFetcher(),
      }),
    cases,
  );

  it('sends to the base path QueryClientFactory builds', async () => {
    const { requests } = stubFetch(() => jsonResponse(state));
    const factory = new QueryClientFactory<CartState>({
      contextAlias: 'example',
      aggregateName: 'cart',
      resourceAttribution: ResourceAttributionPathSpec.OWNER,
      urlParams: { path: { ownerId: 'o-1' } },
      fetcher: testFetcher(),
    });

    await factory.createLoadOwnerStateAggregateClient().load();
    await factory
      .createLoadOwnerStateAggregateClient({ basePath: 'custom' })
      .loadTimeBased(5);

    expect(requests.map(r => r.url)).toStrictEqual([
      `${BASE_URL}/example/owner/o-1/cart/state`,
      `${BASE_URL}/custom/state/time/5`,
    ]);
  });
});
