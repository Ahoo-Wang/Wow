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

import type { FilterListQuery } from '@ahoo-wang/wow-client/dsl';
import {
  type Endpoint,
  endpointIdentity,
  postQueryStream,
} from '../internal/endpoint.js';
import { useStream } from '../internal/useStream.js';
import {
  type UseStreamQueryOptions,
  type UseStreamQueryReturn,
} from './useStreamQuery.js';

/**
 * Options of {@link useFetcherStreamQuery}: those of `useStreamQuery`,
 * with the endpoint and the Fetcher in place of `execute`.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default: a failed request
 *   rejects with a `WowProblemError`, and so does an error event in the
 *   stream
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseFetcherStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
>
  extends Omit<UseStreamQueryOptions<R, FIELDS, E, Q>, 'execute'>, Endpoint {}

/**
 * What {@link useFetcherStreamQuery} returns; see
 * {@link UseStreamQueryReturn}.
 */
export interface UseFetcherStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
> extends UseStreamQueryReturn<R, FIELDS, E, Q> {}

/**
 * Streams the rows of a list query from a Wow query route and keeps them as
 * state; see `useStreamQuery` for the state it returns.
 *
 * It POSTs the query to `url` through `fetcher` (the default Fetcher when
 * omitted) with `Accept: text/event-stream`, the header a Wow service needs
 * to answer with an event stream rather than JSON. An error event in the
 * stream ends it with a `WowProblemError` in `error`.
 *
 * A change of `url`, or of `fetcher`, runs the query again. A Fetcher is
 * compared by its name, or by its `baseURL` when it has none, so one created
 * inline in render does not run it on every render.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type
 *
 * @example
 * ```tsx
 * import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
 * import type { SnapshotRecord } from '@ahoo-wang/wow-client';
 * import { useFetcherStreamQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders() {
 *   const { items, done, loading, error } = useFetcherStreamQuery<SnapshotRecord<OrderState>>({
 *     url: '/shop/order/snapshot/query',
 *     initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return (
 *     <>
 *       <ul>{items.map(order => <li key={order.aggregateId}>{order.state.status}</li>)}</ul>
 *       {loading ? <p>Loading…</p> : done && <p>{items.length} orders</p>}
 *     </>
 *   );
 * }
 * ```
 */
export function useFetcherStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
/**
 * {@link useFetcherStreamQuery} typed by a query `Q` of your own, a subtype of
 * `FilterListQuery`, such as one whose literal projection narrows the result.
 */
export function useFetcherStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherStreamQueryReturn<R, FIELDS, E, Q>;
export function useFetcherStreamQuery<
  R,
  FIELDS extends string,
  E,
  Q extends FilterListQuery<FIELDS>,
>(
  options: UseFetcherStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherStreamQueryReturn<R, FIELDS, E, Q> {
  const { url, fetcher, ...rest } = options;
  return useStream<R, FIELDS, E, Q>(
    { ...rest, execute: postQueryStream<R, Q>({ url, fetcher }) },
    endpointIdentity({ url, fetcher }),
  );
}
