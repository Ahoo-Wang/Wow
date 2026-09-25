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
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { useListStreamQuery } from '../src';
import type {
  UseFetcherListStreamQueryOptions,
  UseFetcherListStreamQueryReturn,
  UseListStreamQueryOptions,
  UseListStreamQueryReturn,
} from '../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };

it('returns the rows of a stream as items, never the stream itself', () => {
  type StreamReturn = UseListStreamQueryReturn<Item, Fields>;
  type FetcherStreamReturn = UseFetcherListStreamQueryReturn<Item, Fields>;

  expectTypeOf<StreamReturn['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<StreamReturn['done']>().toEqualTypeOf<boolean>();
  expectTypeOf<StreamReturn>().not.toHaveProperty('result');
  // A failed request is a FetcherError and an error event a WowError; both
  // are Errors, the default.
  expectTypeOf<StreamReturn['error']>().toEqualTypeOf<Error | undefined>();
  expectTypeOf<FetcherStreamReturn['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<FetcherStreamReturn>().not.toHaveProperty('result');
  expectTypeOf<
    NonNullable<UseListStreamQueryOptions<Item, Fields>['onSuccess']>
  >()
    .parameter(0)
    .toEqualTypeOf<Item[]>();
});

it('sets its own result extractor and Accept header on the endpoint hook', () => {
  expectTypeOf<
    UseFetcherListStreamQueryOptions<Item, Fields>
  >().not.toHaveProperty('resultExtractor');
  expectTypeOf<
    UseFetcherListStreamQueryOptions<Item, Fields>
  >().not.toHaveProperty('execute');
});

it('takes a query client stream method as execute', () => {
  const read = (client: SnapshotQueryClient<Item, Fields>) =>
    useListStreamQuery<Item, Fields>({
      execute: (query, attributes, abortController) =>
        client.listStateStream(query, attributes, abortController),
    }).items;
  expectTypeOf(read).returns.toEqualTypeOf<Item[]>();
});
