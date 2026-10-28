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

import { expectTypeOf, it } from 'vitest';
import type { CountQuery, QueryModelClient } from '@ahoo-wang/wow-client';
import type {
  AggregationQuery,
  CursorQuery,
  FilterListQuery,
  FilterSingleQuery,
  PagedQuery,
} from '@ahoo-wang/wow-client/dsl';
import { filter } from '@ahoo-wang/wow-client/dsl';
import {
  useAggregateQuery,
  useCountQuery,
  useCursorQuery,
  useListQuery,
  useStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '../src';
import type {
  UseAggregateQueryOptions,
  UseAggregateQueryReturn,
  UseCountQueryOptions,
  UseCountQueryReturn,
  UseCursorQueryOptions,
  UseCursorQueryReturn,
  UseListQueryOptions,
  UseListQueryReturn,
  UseStreamQueryOptions,
  UseStreamQueryReturn,
  UsePagedQueryOptions,
  UsePagedQueryReturn,
  UseSingleQueryOptions,
  UseSingleQueryReturn,
} from '../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };
type InitialQuery<T extends { initialQuery?: unknown }> = NonNullable<
  T['initialQuery']
>;
type SetQuery<T> = T extends { setQuery: (query: infer Q) => void } ? Q : never;

it('defaults every hook to its filter request type', () => {
  const useGetListQuery = (options: UseListQueryOptions<Item, Fields>) =>
    useListQuery<Item, Fields>(options).getQuery();

  expectTypeOf(useGetListQuery).returns.toEqualTypeOf<
    FilterListQuery<Fields> | undefined
  >();
  expectTypeOf<InitialQuery<UseListQueryOptions<Item, Fields>>>().toEqualTypeOf<
    FilterListQuery<Fields>
  >();
  expectTypeOf<SetQuery<UseListQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterListQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<SetQuery<UseStreamQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterListQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UsePagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<SetQuery<UsePagedQueryReturn<Item, Fields>>>().toEqualTypeOf<
    PagedQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<SetQuery<UseSingleQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterSingleQuery<Fields>
  >();
  expectTypeOf<InitialQuery<UseCountQueryOptions<Fields>>>().toEqualTypeOf<
    CountQuery<Fields>
  >();
  expectTypeOf<SetQuery<UseCountQueryReturn<Fields>>>().toEqualTypeOf<
    CountQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseCursorQueryOptions<Item, Fields>>
  >().toEqualTypeOf<Omit<CursorQuery<Fields>, 'cursor'>>();
  expectTypeOf<SetQuery<UseCursorQueryReturn<Item, Fields>>>().toEqualTypeOf<
    Omit<CursorQuery<Fields>, 'cursor'>
  >();
  expectTypeOf<
    InitialQuery<UseAggregateQueryOptions<Item, Fields>>
  >().toEqualTypeOf<AggregationQuery<Fields, string>>();
  expectTypeOf<SetQuery<UseAggregateQueryReturn<Item, Fields>>>().toEqualTypeOf<
    AggregationQuery<Fields, string>
  >();
});

it('accepts filter request subtypes', () => {
  type ListOptions = UseListQueryOptions<Item, Fields, FilterListQuery<Fields>>;
  type StreamOptions = UseStreamQueryOptions<
    Item,
    Fields,
    FilterListQuery<Fields>
  >;
  type PagedOptions = UsePagedQueryOptions<Item, Fields, PagedQuery<Fields>>;
  type SingleOptions = UseSingleQueryOptions<
    Item,
    Fields,
    FilterSingleQuery<Fields>
  >;
  type CountOptions = UseCountQueryOptions<Fields, CountQuery<Fields>>;

  expectTypeOf((options: ListOptions) =>
    useListQuery<Item, Fields>(options),
  ).toBeFunction();
  expectTypeOf((options: StreamOptions) =>
    useStreamQuery<Item, Fields>(options),
  ).toBeFunction();
  expectTypeOf((options: PagedOptions) =>
    usePagedQuery<Item, Fields>(options),
  ).toBeFunction();
  expectTypeOf((options: SingleOptions) =>
    useSingleQuery<Item, Fields>(options),
  ).toBeFunction();
  expectTypeOf((options: CountOptions) =>
    useCountQuery<Fields>(options),
  ).toBeFunction();
});

it('takes a query client cursor and aggregate method as execute', () => {
  const usePages = (client: QueryModelClient<Item, Fields>) =>
    useCursorQuery<Item, Fields>({
      query: { filter: filter.eq<Fields>('status', 'PAID') },
      execute: (query, options) => client.cursor(query, options),
    });
  expectTypeOf(usePages)
    .returns.toHaveProperty('items')
    .toEqualTypeOf<Item[]>();
  expectTypeOf(usePages)
    .returns.toHaveProperty('fetchNext')
    .toEqualTypeOf<() => Promise<void>>();

  type Row = { status: string; orders: number };
  const useRows = (
    client: QueryModelClient<Item, Fields>,
    query: AggregationQuery<Fields, string>,
  ) =>
    useAggregateQuery<Row, Fields>({
      query,
      execute: (query, options) => client.aggregate<Row>(query, options),
    });
  expectTypeOf(useRows)
    .returns.toHaveProperty('result')
    .toEqualTypeOf<Row[] | undefined>();
});

it("refuses a cursor in the cursor hook's query: the hook owns it", () => {
  const usePages = (client: QueryModelClient<Item, Fields>) =>
    useCursorQuery<Item, Fields>({
      query: {
        filter: filter.eq<Fields>('status', 'PAID'),
        // @ts-expect-error the hook hands execute the cursor of each page
        cursor: 'c1',
      },
      execute: (query, options) => client.cursor(query, options),
    });
  expectTypeOf(usePages).toBeFunction();
});
