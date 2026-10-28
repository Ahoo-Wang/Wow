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

import type { AggregationQuery } from '@ahoo-wang/wow-client/dsl';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link useAggregateQuery}: an aggregation and an `execute` that
 * resolves to its rows, as a query client's `aggregate` does.
 *
 * @template ROW - One row of the result: the groups and metrics by alias
 * @template FIELDS - The field names the aggregation may use
 * @template Q - The aggregation type: `AggregationQuery` by default
 */
export interface UseAggregateQueryOptions<
  ROW,
  FIELDS extends string = string,
  Q extends AggregationQuery<FIELDS, string> = AggregationQuery<FIELDS, string>,
> extends QueryHookOptions<Q, ROW[]> {}

/**
 * What {@link useAggregateQuery} returns: the rows as `result`.
 *
 * @template ROW - One row of the result
 * @template FIELDS - The field names the aggregation may use
 * @template Q - The aggregation type: `AggregationQuery` by default
 */
export interface UseAggregateQueryReturn<
  ROW,
  FIELDS extends string = string,
  Q extends AggregationQuery<FIELDS, string> = AggregationQuery<FIELDS, string>,
> extends QueryHookReturn<Q, ROW[]> {}

/**
 * Runs an aggregation through your own `execute` function and keeps its rows
 * as state: typically a query client's `aggregate`, one row per group.
 *
 * `execute` receives the aggregation and the request options of the run, its
 * `signal` included; hand them on so that a newer query, `abort()`
 * or an unmount cancels the request. The aggregation runs on mount and
 * whenever `query` or `setQuery()` changes it; set `autoExecute: false` to
 * run it only through `execute()`.
 *
 * Returns `result` (the rows, or `undefined` before the first success),
 * `loading`, `error`, `problem`, `status`, `execute`, `abort`, `reset`,
 * `getQuery` and `setQuery`.
 *
 * @template ROW - One row of the result: the groups and metrics by alias
 * @template FIELDS - The field names the aggregation may use. With a client
 *   whose fields are narrower than `string`, as a generated client's are,
 *   pass them here: TypeScript does not infer them from `execute` once
 *   `ROW` is given
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
 * import { aggregation } from '@ahoo-wang/wow-client/dsl';
 * import { useAggregateQuery } from '@ahoo-wang/wow-react';
 *
 * type StatusCount = { status: string; orders: number };
 *
 * function OrdersByStatus({ client }: { client: SnapshotQueryClient<OrderState, OrderFields> }) {
 *   const { result, error } = useAggregateQuery<StatusCount, OrderFields>({
 *     query: {
 *       groupBy: [aggregation.terms('state.status', 'status')],
 *       metrics: [aggregation.count('orders')],
 *     },
 *     execute: (query, options) =>
 *       client.aggregate<StatusCount>(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return <ul>{result?.map(row => <li key={row.status}>{row.status}: {row.orders}</li>)}</ul>;
 * }
 * ```
 */
export function useAggregateQuery<ROW, FIELDS extends string = string>(
  options: UseAggregateQueryOptions<ROW, FIELDS, AggregationQuery<FIELDS, string>>,
): UseAggregateQueryReturn<ROW, FIELDS, AggregationQuery<FIELDS, string>>;
/**
 * {@link useAggregateQuery} typed by an aggregation `Q` of your own, a
 * subtype of `AggregationQuery`.
 */
export function useAggregateQuery<
  ROW,
  FIELDS extends string = string,
  Q extends AggregationQuery<FIELDS, string> = AggregationQuery<FIELDS, string>,
>(
  options: UseAggregateQueryOptions<ROW, FIELDS, Q>,
): UseAggregateQueryReturn<ROW, FIELDS, Q>;
export function useAggregateQuery<
  ROW,
  FIELDS extends string,
  Q extends AggregationQuery<FIELDS, string>,
>(
  options: UseAggregateQueryOptions<ROW, FIELDS, Q>,
): UseAggregateQueryReturn<ROW, FIELDS, Q> {
  return useQueryRunner<Q, ROW[]>(options);
}
