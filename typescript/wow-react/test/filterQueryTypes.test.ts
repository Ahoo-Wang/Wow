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
  FilterExpression,
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  WowError,
} from '@ahoo-wang/wow-client';
import type { FetcherError } from '@ahoo-wang/fetcher';
import type {
  Condition,
  ListQuery,
  PagedQuery,
  SingleQuery,
} from '@ahoo-wang/wow-client/legacy';
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
} from '../src';
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
} from '../src';

type Fields = 'id' | 'status';
type Item = { id: string; status: string };
type InitialQuery<T extends { initialQuery?: unknown }> = NonNullable<
  T['initialQuery']
>;
type SetQuery<T> = T extends { setQuery: (query: infer Q) => void } ? Q : never;

it('defaults custom-executor hooks to filter request subtypes', () => {
  const getListQuery = (options: UseListQueryOptions<Item, Fields>) =>
    useListQuery<Item, Fields>(options).getQuery();

  expectTypeOf<ReturnType<typeof getListQuery>>().toEqualTypeOf<
    FilterListQuery<Fields> | undefined
  >();
  expectTypeOf<InitialQuery<UseListQueryOptions<Item, Fields>>>().toEqualTypeOf<
    FilterListQuery<Fields>
  >();
  expectTypeOf<SetQuery<UseListQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterListQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseListStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseListStreamQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UsePagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterPagedQuery<Fields>>();
  expectTypeOf<SetQuery<UsePagedQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterPagedQuery<Fields>
  >();
  expectTypeOf<
    InitialQuery<UseSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<SetQuery<UseSingleQueryReturn<Item, Fields>>>().toEqualTypeOf<
    FilterSingleQuery<Fields>
  >();
  expectTypeOf<InitialQuery<UseCountQueryOptions<Fields>>>().toEqualTypeOf<
    FilterExpression<Fields>
  >();
  expectTypeOf<SetQuery<UseCountQueryReturn<Fields>>>().toEqualTypeOf<
    FilterExpression<Fields>
  >();
});

it('defaults Fetcher hooks to filter request subtypes', () => {
  const getPagedQuery = (options: UseFetcherPagedQueryOptions<Item, Fields>) =>
    useFetcherPagedQuery<Item, Fields>(options).getQuery();

  expectTypeOf<ReturnType<typeof getPagedQuery>>().toEqualTypeOf<
    FilterPagedQuery<Fields> | undefined
  >();
  expectTypeOf<
    InitialQuery<UseFetcherListQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherListQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherListStreamQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherListStreamQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterListQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherPagedQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterPagedQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherPagedQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterPagedQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherSingleQueryOptions<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<
    SetQuery<UseFetcherSingleQueryReturn<Item, Fields>>
  >().toEqualTypeOf<FilterSingleQuery<Fields>>();
  expectTypeOf<
    InitialQuery<UseFetcherCountQueryOptions<Fields>>
  >().toEqualTypeOf<FilterExpression<Fields>>();
  expectTypeOf<SetQuery<UseFetcherCountQueryReturn<Fields>>>().toEqualTypeOf<
    FilterExpression<Fields>
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

it('still accepts the legacy request subtypes in custom-executor hooks', () => {
  type ListOptions = UseListQueryOptions<
    Item,
    Fields,
    Error,
    ListQuery<Fields>
  >;
  type StreamOptions = UseListStreamQueryOptions<
    Item,
    Fields,
    Error,
    ListQuery<Fields>
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
    SingleQuery<Fields>
  >;
  type CountOptions = UseCountQueryOptions<Fields, Error, Condition<Fields>>;

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

it('still accepts the legacy request subtypes in Fetcher hooks', () => {
  type ListOptions = UseFetcherListQueryOptions<
    Item,
    Fields,
    Error,
    ListQuery<Fields>
  >;
  type StreamOptions = UseFetcherListStreamQueryOptions<
    Item,
    Fields,
    Error,
    ListQuery<Fields>
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
    SingleQuery<Fields>
  >;
  type CountOptions = UseFetcherCountQueryOptions<
    Fields,
    Error,
    Condition<Fields>
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
