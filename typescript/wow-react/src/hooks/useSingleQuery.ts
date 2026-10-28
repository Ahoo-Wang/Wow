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

import type { FilterSingleQuery } from '@ahoo-wang/wow-client/dsl';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link useSingleQuery}: a single query and an `execute` that
 * resolves to one item, or `undefined` when nothing matches, as a query
 * client's `single` does.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template Q - The query type: `FilterSingleQuery` by default
 */
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  Q extends FilterSingleQuery<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookOptions<Q, R | undefined> {}

/**
 * What {@link useSingleQuery} returns: the item as `result`, `undefined`
 * before the first run and when nothing matched (tell them apart by
 * `status`).
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template Q - The query type: `FilterSingleQuery` by default
 */
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  Q extends FilterSingleQuery<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R | undefined> {}

/**
 * Runs a single query through your own `execute` function and keeps the
 * result as state: typically a query client's `single` or `singleState`.
 *
 * `execute` receives the query and the request options of the run, its
 * `signal` included; hand them on so that a newer query, `abort()`
 * or an unmount cancels the request. The query runs on mount and whenever
 * `query` or `setQuery()` changes it; set `autoExecute: false` to run it only
 * through `execute()`.
 *
 * Returns `result` (the item, or `undefined` before the first success),
 * `loading`, `error`, `problem`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use. With a client whose
 *   fields are narrower than `string`, as a generated client's are, pass
 *   them here: TypeScript does not infer them from `execute` once `R` is given
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient, SnapshotRecord } from '@ahoo-wang/wow-client';
 * import { filter, singleQuery } from '@ahoo-wang/wow-client/dsl';
 * import { useSingleQuery } from '@ahoo-wang/wow-react';
 *
 * function OrderStatus({ client, id }: { client: SnapshotQueryClient<OrderState, OrderFields>; id: string }) {
 *   const { result, loading, error } = useSingleQuery<SnapshotRecord<OrderState>, OrderFields>({
 *     query: singleQuery({ filter: filter.id(id) }),
 *     execute: (query, options) =>
 *       client.single(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading) return <p>Loading…</p>;
 *   if (!result) return <p>No such order.</p>;
 *   return <p>{result.state.status}</p>;
 * }
 * ```
 */
export function useSingleQuery<R, FIELDS extends string = string>(
  options: UseSingleQueryOptions<R, FIELDS, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, FilterSingleQuery<FIELDS>>;
/**
 * {@link useSingleQuery} typed by a query `Q` of your own, a subtype of
 * `FilterSingleQuery`, such as one whose literal projection narrows the result.
 */
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  Q extends FilterSingleQuery<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, Q>,
): UseSingleQueryReturn<R, FIELDS, Q>;
export function useSingleQuery<
  R,
  FIELDS extends string,
  Q extends FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, Q>,
): UseSingleQueryReturn<R, FIELDS, Q> {
  return useQueryRunner<Q, R | undefined>(options);
}
