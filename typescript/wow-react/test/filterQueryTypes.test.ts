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
  Condition,
  FilterExpression,
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  ListQuery,
  PagedQuery,
  SingleQuery,
} from '@ahoo-wang/fetcher-wow';
import {
  useCountQuery,
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherListStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
  useListQuery,
  useListStreamQuery,
  usePagedQuery,
  useSingleQuery,
} from '../../src';
import type {
  UseCountQueryOptions,
  UseCountQueryReturn,
  UseFetcherCountQueryOptions,
  UseFetcherCountQueryReturn,
  UseFetcherListQueryOptions,
  UseFetcherListQueryReturn,
  UseFetcherListStreamQueryOptions,
  UseFetcherListStreamQueryReturn,
  UseFetcherPagedQueryOptions,
  UseFetcherPagedQueryReturn,
  UseFetcherSingleQueryOptions,
  UseFetcherSingleQueryReturn,
  UseListQueryOptions,
  UseListQueryReturn,
  UseListStreamQueryOptions,
  UseListStreamQueryReturn,
  UsePagedQueryOptions,
  UsePagedQueryReturn,
  UseSingleQueryOptions,
  UseSingleQueryReturn,
} from '../../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };
type InitialQuery<T extends { initialQuery?: unknown }> = NonNullable<
  T['initialQuery']
>;
type SetQuery<T> = T extends { setQuery: (query: infer Q) => void } ? Q : never;

it('preserves legacy request subtypes in custom-executor hooks', () => {
  const getListQuery = (options: UseListQueryOptions<Item, Fields>) =>
    useListQuery<Item, Fields>(options).getQuery();

  expectTypeOf<ReturnType<typeof getListQuery>>().toEqualTypeOf<
    ListQuery<Fields> | undefined
  >();
  expectTypeOf<InitialQuery<UseListQueryOptions<Item, Fields>>>().toEqualTypeOf<
    ListQuery<Fields>
  >();
  expectTypeOf<SetQuery<UseListQueryReturn<Item, Fields>>>().toEqualTypeOf<
    ListQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseListStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseListStreamQueryReturn<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UsePagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<SetQuery<UsePagedQueryReturn<Item, Fields>>>().toEqualTypeOf<
    PagedQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<SingleQuery<Fields>>();
  expectTypeOf<SetQuery<UseSingleQueryReturn<Item, Fields>>>().toEqualTypeOf<
    SingleQuery<Fields>
  >();
  expectTypeOf<InitialQuery<UseCountQueryOptions<Fields>>>().toEqualTypeOf<
    Condition<Fields>
  >();
  expectTypeOf<SetQuery<UseCountQueryReturn<Fields>>>().toEqualTypeOf<
    Condition<Fields>
  >();
});

it('preserves legacy request subtypes in Fetcher hooks', () => {
  const getPagedQuery = (options: UseFetcherPagedQueryOptions<Item, Fields>) =>
    useFetcherPagedQuery<Item, Fields>(options).getQuery();

  expectTypeOf<ReturnType<typeof getPagedQuery>>().toEqualTypeOf<
    PagedQuery<Fields> | undefined
  >();
  expectTypeOf<
    InitialQuery<UseFetcherListQueryOptions<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherListQueryReturn<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherListStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherListStreamQueryReturn<Item, Fields>>
  >().toEqualTypeOf<ListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherPagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherPagedQueryReturn<Item, Fields>>
  >().toEqualTypeOf<PagedQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<SingleQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherSingleQueryReturn<Item, Fields>>
  >().toEqualTypeOf<SingleQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherCountQueryOptions<Fields>>
  >().toEqualTypeOf<Condition<Fields>>();
  expectTypeOf<SetQuery<UseFetcherCountQueryReturn<Fields>>>().toEqualTypeOf<
    Condition<Fields>
  >();
});

it('accepts filter request subtypes in custom-executor hooks', () => {
  type ListOptions = UseListQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type StreamOptions = UseListStreamQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type PagedOptions = UsePagedQueryOptions<
    Item,
    Fields,
    Error,
    FilterPagedQuery<Fields>
  >;
  type SingleOptions = UseSingleQueryOptions<
    Item,
    Fields,
    Error,
    FilterSingleQuery<Fields>
  >;
  type CountOptions = UseCountQueryOptions<
    Fields,
    Error,
    FilterExpression<Fields>
  >;

  expectTypeOf((options: ListOptions) =>
    useListQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: StreamOptions) =>
    useListStreamQuery<Item, Fields, Error>(options),
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
  type StreamOptions = UseFetcherListStreamQueryOptions<
    Item,
    Fields,
    Error,
    FilterListQuery<Fields>
  >;
  type PagedOptions = UseFetcherPagedQueryOptions<
    Item,
    Fields,
    Error,
    FilterPagedQuery<Fields>
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
    FilterExpression<Fields>
  >;

  expectTypeOf((options: ListOptions) =>
    useFetcherListQuery<Item, Fields, Error>(options),
  ).toBeFunction();
  expectTypeOf((options: StreamOptions) =>
    useFetcherListStreamQuery<Item, Fields, Error>(options),
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
