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
import type {
  CountQuery,
  CountResult,
  PagedQuery,
  PagedResult,
  RequestOptions,
  StreamedRow,
  StreamOptions,
  WowProblemError,
} from '@ahoo-wang/wow-client';
import type {
  AggregationQuery,
  CursorPage,
  CursorQuery,
  FilterListQuery,
  FilterSingleQuery,
} from '@ahoo-wang/wow-client/dsl';
import type {
  StreamExecutor,
  QueryExecutor,
  QueryHookOptions,
  QueryHookReturn,
  QueryStatus,
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

/** What every hook takes. */
type OptionKeys =
  | 'query'
  | 'initialQuery'
  | 'autoExecute'
  | 'requestOptions'
  | 'execute'
  | 'onSuccess'
  | 'onError';
/** What a request hook returns. */
type ReturnKeys =
  | 'status'
  | 'loading'
  | 'result'
  | 'error'
  | 'problem'
  | 'execute'
  | 'abort'
  | 'reset'
  | 'getQuery'
  | 'setQuery';
/** What the list-stream hook returns: the records in place of `result`. */
type StreamReturnKeys =
  | Exclude<ReturnKeys, 'result'>
  | 'items'
  | 'position'
  | 'done';
/** What the cursor hook returns: the rows of its pages in place of `result`. */
type CursorReturnKeys =
  | Exclude<ReturnKeys, 'result'>
  | 'items'
  | 'hasMore'
  | 'fetchNext';
/** The query the cursor hook takes: the hook owns the cursor. */
type PageQuery = Omit<CursorQuery<Fields>, 'cursor'>;

it('names the four query statuses as a plain string union', () => {
  expectTypeOf<QueryStatus>().toEqualTypeOf<
    'idle' | 'loading' | 'success' | 'error'
  >();
  // A literal is a status: props, tests and stories write one directly.
  expectTypeOf<'success'>().toExtend<QueryStatus>();
  expectTypeOf<'done'>().not.toExtend<QueryStatus>();
});

it('hands every executor the query and the request options with a signal', () => {
  expectTypeOf<QueryExecutor<string, number>>().parameters.toEqualTypeOf<
    [string, RequestOptions & { readonly signal: AbortSignal }]
  >();
  expectTypeOf<QueryExecutor<string, number>>().returns.toEqualTypeOf<
    Promise<number>
  >();
  expectTypeOf<StreamExecutor<Item, string>>().toEqualTypeOf<
    QueryExecutor<string, ReadableStream<StreamedRow<Item>>, StreamOptions>
  >();
  expectTypeOf<StreamExecutor<Item, string>>().parameters.toEqualTypeOf<
    [string, StreamOptions & { readonly signal: AbortSignal }]
  >();
});

it('holds the members of the shared options and return', () => {
  type Options = QueryHookOptions<string, number>;
  type Return = QueryHookReturn<string, number>;

  expectTypeOf<keyof Options>().toEqualTypeOf<OptionKeys>();
  expectTypeOf<Options['query']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<Options['initialQuery']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<Options['autoExecute']>().toEqualTypeOf<boolean | undefined>();
  expectTypeOf<Options['requestOptions']>().toEqualTypeOf<
    Omit<RequestOptions, 'signal'> | undefined
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
  expectTypeOf<Return['problem']>().toEqualTypeOf<
    WowProblemError | undefined
  >();
  expectTypeOf<Return['execute']>().toEqualTypeOf<() => Promise<void>>();
  expectTypeOf<Return['abort']>().toEqualTypeOf<() => void>();
  expectTypeOf<Return['reset']>().toEqualTypeOf<() => void>();
  expectTypeOf<Return['getQuery']>().toEqualTypeOf<() => string | undefined>();
  expectTypeOf<Return['setQuery']>().toEqualTypeOf<(query: string) => void>();
});

it('specializes the shared types for each request hook', () => {
  expectTypeOf<UseSingleQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterSingleQuery<Fields>, Item | undefined>
  >();
  expectTypeOf<UseSingleQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterSingleQuery<Fields>, Item | undefined>
  >();
  expectTypeOf<UseListQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<FilterListQuery<Fields>, Item[]>
  >();
  expectTypeOf<UseListQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<FilterListQuery<Fields>, Item[]>
  >();
  expectTypeOf<UsePagedQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<PagedQuery<Fields>, PagedResult<Item>>
  >();
  expectTypeOf<UsePagedQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<PagedQuery<Fields>, PagedResult<Item>>
  >();
  expectTypeOf<UseCountQueryOptions<Fields>>().toEqualTypeOf<
    QueryHookOptions<CountQuery<Fields>, CountResult>
  >();
  expectTypeOf<UseCountQueryReturn<Fields>>().toEqualTypeOf<
    QueryHookReturn<CountQuery<Fields>, CountResult>
  >();
  expectTypeOf<UseAggregateQueryOptions<Item, Fields>>().toEqualTypeOf<
    QueryHookOptions<AggregationQuery<Fields, string>, Item[]>
  >();
  expectTypeOf<UseAggregateQueryReturn<Item, Fields>>().toEqualTypeOf<
    QueryHookReturn<AggregationQuery<Fields, string>, Item[]>
  >();
});

it('streams records into items, position and done in place of result', () => {
  type Options = UseStreamQueryOptions<Item, Fields>;
  type Return = UseStreamQueryReturn<Item, Fields>;

  expectTypeOf<keyof Options>().toEqualTypeOf<OptionKeys>();
  expectTypeOf<Options['execute']>().toEqualTypeOf<
    StreamExecutor<Item, FilterListQuery<Fields>>
  >();
  expectTypeOf<Options['requestOptions']>().toEqualTypeOf<
    Omit<StreamOptions, 'signal'> | undefined
  >();
  expectTypeOf<Omit<Options, 'execute'>>().toEqualTypeOf<
    Omit<
      QueryHookOptions<FilterListQuery<Fields>, Item[], StreamOptions>,
      'execute'
    >
  >();

  expectTypeOf<keyof Return>().toEqualTypeOf<StreamReturnKeys>();
  expectTypeOf<Return['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<Return['position']>().toEqualTypeOf<string | undefined>();
  expectTypeOf<Return['done']>().toEqualTypeOf<boolean>();
  expectTypeOf<Omit<Return, 'items' | 'position' | 'done'>>().toEqualTypeOf<
    Omit<QueryHookReturn<FilterListQuery<Fields>, Item[]>, 'result'>
  >();
});

it('reads a cursor page by page into items, hasMore and fetchNext', () => {
  type Options = UseCursorQueryOptions<Item, Fields>;
  type Return = UseCursorQueryReturn<Item, Fields>;

  expectTypeOf<keyof Options>().toEqualTypeOf<OptionKeys>();
  expectTypeOf<Options['execute']>().toEqualTypeOf<
    QueryExecutor<PageQuery & { cursor: string | null }, CursorPage<Item>>
  >();
  expectTypeOf<Omit<Options, 'execute'>>().toEqualTypeOf<
    Omit<QueryHookOptions<PageQuery, CursorPage<Item>>, 'execute'>
  >();

  expectTypeOf<keyof Return>().toEqualTypeOf<CursorReturnKeys>();
  expectTypeOf<Return['items']>().toEqualTypeOf<Item[]>();
  expectTypeOf<Return['hasMore']>().toEqualTypeOf<boolean>();
  expectTypeOf<Return['fetchNext']>().toEqualTypeOf<() => Promise<void>>();
  expectTypeOf<Omit<Return, 'items' | 'hasMore' | 'fetchNext'>>().toEqualTypeOf<
    Omit<QueryHookReturn<PageQuery, CursorPage<Item>>, 'result'>
  >();
});

it('reports an Error, and a Wow service\'s answer as problem', () => {
  expectTypeOf<UseSingleQueryReturn<Item>['error']>().toEqualTypeOf<
    Error | undefined
  >();
  expectTypeOf<UseStreamQueryReturn<Item>['problem']>().toEqualTypeOf<
    WowProblemError | undefined
  >();
  expectTypeOf<UseCursorQueryReturn<Item>['problem']>().toEqualTypeOf<
    WowProblemError | undefined
  >();
  expectTypeOf<NonNullable<UseSingleQueryOptions<Item, Fields>['onError']>>()
    .parameter(0)
    .toEqualTypeOf<Error>();
});
