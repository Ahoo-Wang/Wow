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

// Type-checked by `pnpm test:type`.

import { expectTypeOf, it } from 'vitest';
import type { QueryModelClient } from '@ahoo-wang/wow-client';
import { useStreamQuery } from '../src';
import type {
  UseFetcherStreamQueryOptions,
  UseFetcherStreamQueryReturn,
  UseStreamQueryOptions,
  UseStreamQueryReturn,
} from '../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };

it('returns the rows of a stream as items, never the stream itself', () => {
  type StreamReturn = UseStreamQueryReturn<Item, Fields>;
  type FetcherStreamReturn = UseFetcherStreamQueryReturn<Item, Fields>;

  expectTypeOf<StreamReturn['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<StreamReturn['done']>().toEqualTypeOf<boolean>();
  expectTypeOf<StreamReturn>().not.toHaveProperty('result');
  // A failed request and an error event are both a WowProblemError, an
  // Error, the default.
  expectTypeOf<StreamReturn['error']>().toEqualTypeOf<Error | undefined>();
  expectTypeOf<FetcherStreamReturn['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<FetcherStreamReturn>().not.toHaveProperty('result');
  expectTypeOf<NonNullable<UseStreamQueryOptions<Item, Fields>['onSuccess']>>()
    .parameter(0)
    .toEqualTypeOf<Item[]>();
});

it('sets its own result extractor and Accept header on the endpoint hook', () => {
  expectTypeOf<UseFetcherStreamQueryOptions<Item, Fields>>().not.toHaveProperty(
    'resultExtractor',
  );
  expectTypeOf<UseFetcherStreamQueryOptions<Item, Fields>>().not.toHaveProperty(
    'execute',
  );
});

it('takes a query client stream method as execute', () => {
  const useRead = (client: QueryModelClient<Item, Fields>) =>
    useStreamQuery<Item, Fields>({
      execute: (query, options) => client.stream(query, options),
    }).items;
  expectTypeOf(useRead).returns.toEqualTypeOf<Item[]>();
});
