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

import type { FilterListQuery } from '@ahoo-wang/wow-client';
import { QueryEventStreamResultExtractor } from '@ahoo-wang/wow-client';
// compat(wow<9): the hook also takes the Condition-based queries of `@ahoo-wang/wow-client/legacy`, which Wow < 8.11 needs; drop that overload in v10.
import type { ListQuery, ListQueryRequest } from '@ahoo-wang/wow-client/legacy';
import type { Fetcher } from '@ahoo-wang/fetcher';
import { ContentTypeValues, getFetcher } from '@ahoo-wang/fetcher';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import {
  useListStreamQuery,
  type UseListStreamQueryOptions,
  type UseListStreamQueryReturn,
} from '../useListStreamQuery.js';

/**
 * Options of {@link useFetcherListStreamQuery}: those of `useListStreamQuery`,
 * with the endpoint and the Fetcher in place of `execute`.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default: a failed request
 *   rejects with a `FetcherError`, an error event in the stream with a
 *   `WowError`
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<UseListStreamQueryOptions<R, FIELDS, E, Q>, 'execute'> {
  /**
   * The list endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/list/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}

/**
 * What {@link useFetcherListStreamQuery} returns; see
 * {@link UseListStreamQueryReturn}.
 */
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseListStreamQueryReturn<R, FIELDS, E, Q> {}

/**
 * Streams the rows of a list query from a Wow list endpoint and keeps them as
 * state; see `useListStreamQuery` for the state it returns.
 *
 * It POSTs the query to `url` through `fetcher` (the default Fetcher when
 * omitted) with `Accept: text/event-stream`, the header a Wow server needs to
 * answer with an event stream rather than JSON. An error event in the stream
 * ends it with a `WowError` in `error`.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type
 *
 * @example
 * ```tsx
 * import { filter, listQuery } from '@ahoo-wang/wow-client';
 * import { useFetcherListStreamQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders() {
 *   const { items, done, loading, error } = useFetcherListStreamQuery<OrderState>({
 *     url: 'order/snapshot/list/state',
 *     initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return (
 *     <>
 *       <ul>{items.map(order => <li key={order.id}>{order.id}</li>)}</ul>
 *       {loading ? <p>Loading…</p> : done && <p>{items.length} orders</p>}
 *     </>
 *   );
 * }
 * ```
 */
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<
    R,
    FIELDS,
    E,
    FilterListQuery<FIELDS>
  >,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q>;
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string,
  E,
  Q extends ListQueryRequest<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q> {
  const { url, fetcher } = options;
  return useListStreamQuery<R, FIELDS, E, Q>({
    ...options,
    execute: (query, attributes, abortController) =>
      getFetcher(fetcher).post<ReadableStream<JsonServerSentEvent<R>>>(
        url,
        {
          body: query,
          headers: { Accept: ContentTypeValues.TEXT_EVENT_STREAM },
          abortController,
        },
        { attributes, resultExtractor: QueryEventStreamResultExtractor },
      ),
  });
}
