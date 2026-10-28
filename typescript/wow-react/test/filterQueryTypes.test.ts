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
import type {
  CountQuery,
  PagedQuery,
  QueryModelClient,
} from '@ahoo-wang/wow-client';
import type {
  CursorPage,
  CursorQuery,
  FilterListQuery,
  FilterSingleQuery,
} from '@ahoo-wang/wow-client/dsl';
import {
  useCountQuery,
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
  useListQuery,
  useStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '../src';
import type {
  UseCountQueryOptions,
  UseCountQueryReturn,
  UseFetcherCountQueryOptions,
  UseFetcherCountQueryReturn,
  UseFetcherListQueryOptions,
  UseFetcherListQueryReturn,
  UseFetcherStreamQueryOptions,
  UseFetcherStreamQueryReturn,
  UseFetcherPagedQueryOptions,
  UseFetcherPagedQueryReturn,
  UseFetcherSingleQueryOptions,
  UseFetcherSingleQueryReturn,
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

it('defaults custom-executor hooks to filter request subtypes', () => {
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
});

it('defaults Fetcher hooks to filter request subtypes', () => {
  const useGetPagedQuery = (
    options: UseFetcherPagedQueryOptions<Item, Fields>,
  ) => useFetcherPagedQuery<Item, Fields>(options).getQuery();

  expectTypeOf(useGetPagedQuery).returns.toEqualTypeOf<
    PagedQuery<Fields> | undefined
  >();
  expectTypeOf<
    InitialQuery<UseFetcherListQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherListQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherStreamQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherPagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherPagedQueryReturn<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherSingleQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherCountQueryOptions<Fields>>
  >().toEqualTypeOf<CountQuery<Fields>>();
  expectTypeOf<SetQuery<UseFetcherCountQueryReturn<Fields>>>().toEqualTypeOf<
    CountQuery<Fields>
  >();
});

it('accepts filter request subtypes in custom-executor hooks', () => {
  type ListOptions = UseListQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type StreamOptions = UseStreamQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type PagedOptions = UsePagedQueryOptions<
    Item,
    Fields,
    Error,
    PagedQuery<Fields>
  >;
  type SingleOptions = UseSingleQueryOptions<
    Item,
    Fields,
    Error,
    FilterSingleQuery<Fields>
  >;
  type CountOptions = UseCountQueryOptions<Fields, Error, CountQuery<Fields>>;

  expectTypeOf((options: ListOptions) =>
    useListQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: StreamOptions) =>
    useStreamQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: PagedOptions) =>
    usePagedQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: SingleOptions) =>
    useSingleQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: CountOptions) =>
    useCountQuery<Fields, Error>(options),
  ).toBeFunction();
});

it('accepts filter request subtypes in Fetcher hooks', () => {
  type ListOptions = UseFetcherListQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type StreamOptions = UseFetcherStreamQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type PagedOptions = UseFetcherPagedQueryOptions<
    Item,
    Fields,
    Error,
    PagedQuery<Fields>
  >;
  type SingleOptions = UseFetcherSingleQueryOptions<
    Item,
    Fields,
    Error,
    FilterSingleQuery<Fields>
  >;
  type CountOptions = UseFetcherCountQueryOptions<
    Fields,
    Error,
    CountQuery<Fields>
  >;

  expectTypeOf((options: ListOptions) =>
    useFetcherListQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: StreamOptions) =>
    useFetcherStreamQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: PagedOptions) =>
    useFetcherPagedQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: SingleOptions) =>
    useFetcherSingleQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: CountOptions) =>
    useFetcherCountQuery<Fields, Error>(options),
  ).toBeFunction();
});

it('runs a cursor query through useSingleQuery until a cursor hook exists', () => {
  // The README's pattern: the page is the result, setQuery moves to the next.
  const usePage = (
    client: QueryModelClient<Item, Fields>,
    first: CursorQuery<Fields>,
  ) =>
    useSingleQuery<CursorPage<Item>, Fields, Error, CursorQuery<Fields>>({
      initialQuery: first,
      execute: (query, options) => client.cursor(query, options),
    });
  expectTypeOf(usePage)
    .returns.toHaveProperty('result')
    .toEqualTypeOf<CursorPage<Item> | undefined>();
  expectTypeOf(usePage)
    .returns.toHaveProperty('setQuery')
    .parameter(0)
    .toEqualTypeOf<CursorQuery<Fields>>();
});
