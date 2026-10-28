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
import type {
  QueryModelClient,
  StreamResumeOptions,
  WowProblemError,
} from '@ahoo-wang/wow-client';
import { useStreamQuery } from '../src';
import type { UseStreamQueryOptions, UseStreamQueryReturn } from '../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };

it('returns the records of a stream as items, never the stream itself', () => {
  type StreamReturn = UseStreamQueryReturn<Item, Fields>;

  expectTypeOf<StreamReturn['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<StreamReturn['position']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<StreamReturn['done']>().toEqualTypeOf<boolean>();
  expectTypeOf<StreamReturn>().not.toHaveProperty('result');
  // A failed request and an error event are both a WowProblemError.
  expectTypeOf<StreamReturn['error']>().toEqualTypeOf<Error | undefined>();
  expectTypeOf<StreamReturn['problem']>().toEqualTypeOf<
    WowProblemError | undefined
  >();
  expectTypeOf<NonNullable<UseStreamQueryOptions<Item, Fields>['onSuccess']>>()
    .parameter(0)
    .toEqualTypeOf<Item[]>();
});

it('takes the stream options, resume included', () => {
  expectTypeOf<
    NonNullable<UseStreamQueryOptions<Item, Fields>['requestOptions']>['resume']
  >().toEqualTypeOf<StreamResumeOptions | false | undefined>();
});

it('takes a query client streamRows method as execute', () => {
  const useRead = (client: QueryModelClient<Item, Fields>) =>
    useStreamQuery<Item, Fields>({
      execute: (query, options) => client.streamRows(query, options),
    }).items;
  expectTypeOf(useRead).returns.toEqualTypeOf<Item[]>();
});
