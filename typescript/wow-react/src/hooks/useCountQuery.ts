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

import type { CountQuery, CountResult } from '@ahoo-wang/wow-client';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link useCountQuery}: a filter and an `execute` that resolves to
 * how many rows it matches.
 *
 * @template FIELDS - The field names the filter may use
 * @template E - The error type, `Error` by default
 * @template Q - The filter type: `FilterExpression` by default
 */
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = Error,
  Q extends CountQuery<FIELDS> = CountQuery<FIELDS>,
> extends QueryHookOptions<Q, CountResult, E> {}

/**
 * What {@link useCountQuery} returns: the count as `result`.
 *
 * @template FIELDS - The field names the filter may use
 * @template E - The error type, `Error` by default
 * @template Q - The filter type: `FilterExpression` by default
 */
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends CountQuery<FIELDS> = CountQuery<FIELDS>,
> extends QueryHookReturn<Q, CountResult, E> {}

/**
 * Counts what a filter matches through your own `execute` function and keeps
 * the count as state: typically a query client's `count`.
 *
 * `execute` receives the filter and the request options of the run, its
 * `signal` included; hand them on so that a newer query, `abort()`
 * or an unmount cancels the request. The count runs on mount and whenever
 * `query` or `setQuery()` changes the filter; set `autoExecute: false` to run
 * it only through `execute()`.
 *
 * Returns `result` (the count, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`.
 *
 * @template FIELDS - The field names the filter may use. With a client whose
 *   fields are narrower than `string`, as a generated client's are, pass
 *   them here; inferred from the first filter, they would admit only its
 *   fields in `setQuery()`
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
 * import { filter } from '@ahoo-wang/wow-client/dsl';
 * import { useCountQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidCount({ client }: { client: SnapshotQueryClient<OrderState, OrderFields> }) {
 *   const { result, error } = useCountQuery<OrderFields>({
 *     initialQuery: { filter: filter.eq('state.status', 'PAID') },
 *     execute: (query, options) =>
 *       client.count(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return <p>{result?.count ?? '…'} paid orders</p>;
 * }
 * ```
 */
export function useCountQuery<FIELDS extends string = string, E = Error>(
  options: UseCountQueryOptions<FIELDS, E, CountQuery<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, CountQuery<FIELDS>>;
export function useCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends CountQuery<FIELDS> = CountQuery<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
export function useCountQuery<
  FIELDS extends string,
  E,
  Q extends CountQuery<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q> {
  return useQueryRunner<Q, CountResult, E>(options);
}
