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

/**
 * The members of every public options and return type, name by name.
 *
 * `publicSurface.test.ts` holds the names the entry exports; this holds what
 * each of those types contains. A member added, removed or retyped here is a
 * change to the public API, made on purpose in both places: the test and the
 * reference page. The members are the package's own, declared in
 * `src/types.ts`, so nothing another package adds to its types reaches them.
 */

import { expectTypeOf, it } from 'vitest';
import type { Fetcher } from '@ahoo-wang/fetcher';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import type {
  FilterExpression,
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  PagedList,
} from '@ahoo-wang/wow-client';
import type {
  ListStreamExecutor,
  QueryExecutor,
  QueryHookOptions,
  QueryHookReturn,
  QueryStatus,
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

/** What every hook takes. */
type ExecuteOptionKeys =
  | 'query'
  | 'initialQuery'
  | 'autoExecute'
  | 'attributes'
  | 'execute'
  | 'onSuccess'
  | 'onError';
/** What a `useFetcher…` hook takes: the endpoint in place of `execute`. */
type EndpointOptionKeys =
  Exclude<ExecuteOptionKeys, 'execute'> | 'url' | 'fetcher';
/** What a request hook returns. */
type ReturnKeys =
  | 'status'
  | 'loading'
  | 'result'
  | 'error'
  | 'execute'
  | 'abort'
  | 'reset'
  | 'getQuery'
  | 'setQuery';
/** What a list-stream hook returns: the rows in place of `result`. */
type StreamReturnKeys = Exclude<ReturnKeys, 'result'> | 'items' | 'done';

it('names the four query statuses as a plain string union', () => {
  expectTypeOf<QueryStatus>().toEqualTypeOf<
    'idle' | 'loading' | 'success' | 'error'
  >();
  // A literal is a status: props, tests and stories write one directly.
  expectTypeOf<'success'>().toExtend<QueryStatus>();
  expectTypeOf<'done'>().not.toExtend<QueryStatus>();
});

it('hands every executor the query, the attributes and a controller', () => {
  expectTypeOf<QueryExecutor<string, number>>().parameters.toEqualTypeOf<
    [string, Record<string, unknown> | undefined, AbortController]
  >();
  expectTypeOf<QueryExecutor<string, number>>().returns.toEqualTypeOf<
    Promise<number>
  >();
  expectTypeOf<ListStreamExecutor<Item, string>>().toEqualTypeOf<
    QueryExecutor<string, ReadableStream<JsonServerSentEvent<Item>>>
  >();
});

it('holds the members of the shared options and return', () => {
  type Options = QueryHookOptions<string, number>;
  type Return = QueryHookReturn<string, number>;

  expectTypeOf<keyof Options>().toEqualTypeOf<ExecuteOptionKeys>();
  expectTypeOf<Options['query']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<Options['initialQuery']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<Options['autoExecute']>().toEqualTypeOf<boolean | undefined>();
  expectTypeOf<Options['attributes']>().toEqualTypeOf<
    Record<string, unknown> | undefined
  >();
  expectTypeOf<Options['execute']>().toEqualTypeOf<
    QueryExecutor<string, number>
  >();
  expectTypeOf<NonNullable<Options['onSuccess']>>().toEqualTypeOf<
    (result: number) => void | Promise<void>
  >();
  expectTypeOf<NonNullable<Options['onError']>>().toEqualTypeOf<
    (error: Error) => void | Promise<void>
  >();

  expectTypeOf<keyof Return>().toEqualTypeOf<ReturnKeys>();
  expectTypeOf<Return['status']>().toEqualTypeOf<QueryStatus>();
  expectTypeOf<Return['loading']>().toEqualTypeOf<boolean>();
  expectTypeOf<Return['result']>().toEqualTypeOf<number | undefined>();
  expectTypeOf<Return['error']>().toEqualTypeOf<Error | undefined>();
  expectTypeOf<Return['execute']>().toEqualTypeOf<() => Promise<void>>();
  expectTypeOf<Return['abort']>().toEqualTypeOf<() => void>();
  expectTypeOf<Return['reset']>().toEqualTypeOf<() => void>();
  expectTypeOf<Return['getQuery']>().toEqualTypeOf<() => string | undefined>();
  expectTypeOf<Return['setQuery']>().toEqualTypeOf<(query: string) => void>();
});

it('specializes the shared types for each request hook', () => {
  expectTypeOf<UseSingleQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterSingleQuery<Fields>, Item>
  >();
  expectTypeOf<UseSingleQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterSingleQuery<Fields>, Item>
  >();
  expectTypeOf<UseListQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterListQuery<Fields>, Item[]>
  >();
  expectTypeOf<UseListQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterListQuery<Fields>, Item[]>
  >();
  expectTypeOf<UsePagedQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterPagedQuery<Fields>, PagedList<Item>>
  >();
  expectTypeOf<UsePagedQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterPagedQuery<Fields>, PagedList<Item>>
  >();
  expectTypeOf<UseCountQueryOptions<Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterExpression<Fields>, number>
  >();
  expectTypeOf<UseCountQueryReturn<Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterExpression<Fields>, number>
  >();
});

it('takes the endpoint and the Fetcher in place of execute', () => {
  type Endpoint<Q, R> = Omit<QueryHookOptions<Q, R>, 'execute'> & {
    url: string;
    fetcher?: string | Fetcher;
  };

  expectTypeOf<
    keyof UseFetcherSingleQueryOptions<Item>
  >().toEqualTypeOf<EndpointOptionKeys>();
  expectTypeOf<
    keyof UseFetcherListQueryOptions<Item>
  >().toEqualTypeOf<EndpointOptionKeys>();
  expectTypeOf<
    keyof UseFetcherPagedQueryOptions<Item>
  >().toEqualTypeOf<EndpointOptionKeys>();
  expectTypeOf<
    keyof UseFetcherCountQueryOptions
  >().toEqualTypeOf<EndpointOptionKeys>();
  expectTypeOf<
    keyof UseFetcherListStreamQueryOptions<Item>
  >().toEqualTypeOf<EndpointOptionKeys>();

  expectTypeOf<
    Pick<UseFetcherSingleQueryOptions<Item, Fields>, EndpointOptionKeys>
  >().toEqualTypeOf<
    Pick<Endpoint<FilterSingleQuery<Fields>, Item>, EndpointOptionKeys>
  >();
  expectTypeOf<
    Pick<UseFetcherListQueryOptions<Item, Fields>, EndpointOptionKeys>
  >().toEqualTypeOf<
    Pick<Endpoint<FilterListQuery<Fields>, Item[]>, EndpointOptionKeys>
  >();
  expectTypeOf<
    Pick<UseFetcherPagedQueryOptions<Item, Fields>, EndpointOptionKeys>
  >().toEqualTypeOf<
    Pick<
      Endpoint<FilterPagedQuery<Fields>, PagedList<Item>>,
      EndpointOptionKeys
    >
  >();
  expectTypeOf<
    Pick<UseFetcherCountQueryOptions<Fields>, EndpointOptionKeys>
  >().toEqualTypeOf<
    Pick<Endpoint<FilterExpression<Fields>, number>, EndpointOptionKeys>
  >();
  expectTypeOf<
    Pick<UseFetcherListStreamQueryOptions<Item, Fields>, EndpointOptionKeys>
  >().toEqualTypeOf<
    Pick<Endpoint<FilterListQuery<Fields>, Item[]>, EndpointOptionKeys>
  >();

  expectTypeOf<UseFetcherSingleQueryReturn<Item, Fields>>().toEqualTypeOf<
    UseSingleQueryReturn<Item, Fields>
  >();
  expectTypeOf<UseFetcherListQueryReturn<Item, Fields>>().toEqualTypeOf<
    UseListQueryReturn<Item, Fields>
  >();
  expectTypeOf<UseFetcherPagedQueryReturn<Item, Fields>>().toEqualTypeOf<
    UsePagedQueryReturn<Item, Fields>
  >();
  expectTypeOf<UseFetcherCountQueryReturn<Fields>>().toEqualTypeOf<
    UseCountQueryReturn<Fields>
  >();
});

it('streams rows into items and done in place of result', () => {
  type Options = UseListStreamQueryOptions<Item, Fields>;
  type Return = UseListStreamQueryReturn<Item, Fields>;

  expectTypeOf<keyof Options>().toEqualTypeOf<ExecuteOptionKeys>();
  expectTypeOf<Options['execute']>().toEqualTypeOf<
    ListStreamExecutor<Item, FilterListQuery<Fields>>
  >();
  expectTypeOf<Omit<Options, 'execute'>>().toEqualTypeOf<
    Omit<QueryHookOptions<FilterListQuery<Fields>, Item[]>, 'execute'>
  >();

  expectTypeOf<keyof Return>().toEqualTypeOf<StreamReturnKeys>();
  expectTypeOf<Return['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<Return['done']>().toEqualTypeOf<boolean>();
  expectTypeOf<Omit<Return, 'items' | 'done'>>().toEqualTypeOf<
    Omit<QueryHookReturn<FilterListQuery<Fields>, Item[]>, 'result'>
  >();
  expectTypeOf<
    UseFetcherListStreamQueryReturn<Item, Fields>
  >().toEqualTypeOf<Return>();
});

it('reports an Error unless told otherwise', () => {
  expectTypeOf<UseSingleQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseListQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UsePagedQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseCountQueryReturn['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseListStreamQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseFetcherSingleQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseFetcherListQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseFetcherPagedQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseFetcherCountQueryReturn['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseFetcherListStreamQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();

  class NotFound extends Error {
    readonly code = 'NOT_FOUND';
  }
  expectTypeOf<
    UseSingleQueryReturn<Item, Fields, NotFound>['error']
  >().toEqualTypeOf<NotFound | undefined>();
  expectTypeOf<
    NonNullable<UseSingleQueryOptions<Item, Fields, NotFound>['onError']>
  >()
    .parameter(0)
    .toEqualTypeOf<NotFound>();
});
