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

import type { QueryApi } from '../queryApi.js';
import type { MaterializedSnapshot } from './snapshot.js';
import type {
  ListQueryRequest,
  PagedQueryRequest,
  SingleQueryRequest,
} from '../requests.js';
import type { PagedList } from '../../../dsl/queryable.js';
import type { CursorPage, CursorQuery } from '../../../dsl/cursorQuery.js';

/**
 * Interface for snapshot query API operations.
 * Extends the base QueryApi interface for MaterializedSnapshot and adds methods
 * for querying snapshot states directly without the full MaterializedSnapshot wrapper.
 * @template S - The type of the snapshot state
 */
export interface SnapshotQueryApi<
  S,
  FIELDS extends string = string,
> extends QueryApi<MaterializedSnapshot<S>, FIELDS> {
  /** Retrieves the next cursor page of snapshot states. */
  cursorState<T extends Partial<S> = S>(
    query: CursorQuery<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<CursorPage<T>>;

  /**
   * Retrieves a single snapshot state based on the provided query parameters.
   * @param singleQuery - The query parameters for retrieving a single snapshot state
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to a partial snapshot state
   */
  singleState<T extends Partial<S> = S>(
    singleQuery: SingleQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T>;

  /**
   * Retrieves a list of snapshot states based on the provided query parameters.
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to an array of partial snapshot states
   */
  listState<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;

  /**
   * Retrieves a stream of snapshot states based on the provided query parameters.
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to a readable stream of partial snapshot states
   */
  listStateStream<T extends Partial<S> = S>(
    listQuery: ListQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<T>>;

  /**
   * Retrieves a paged list of snapshot states based on the provided query parameters.
   * @param pagedQuery - The query parameters for paging snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to a paged list of partial snapshot states
   */
  pagedState<T extends Partial<S> = S>(
    pagedQuery: PagedQueryRequest<FIELDS>,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<PagedList<T>>;

  /**
   * Retrieves the materialized snapshot of one aggregate: `single` filtered
   * by `filter.aggregateId(id)`.
   * @param id - The aggregate ID
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to the materialized snapshot
   */
  getById(
    id: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<MaterializedSnapshot<S>>;

  /**
   * Retrieves the state of one aggregate: `singleState` filtered by
   * `filter.aggregateId(id)`.
   * @param id - The aggregate ID
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to the snapshot state
   */
  getStateById(
    id: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;

  /**
   * Retrieves the materialized snapshots of several aggregates in one
   * request: `list` filtered by `filter.aggregateIds(ids)`, limited to
   * `ids.length`. An empty `ids` sends nothing and resolves to `[]`.
   * @param ids - The aggregate IDs
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to the materialized snapshots found
   */
  getByIds(
    ids: string[],
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<MaterializedSnapshot<S>[]>;

  /**
   * Retrieves the states of several aggregates in one request: `listState`
   * filtered by `filter.aggregateIds(ids)`, limited to `ids.length`. An empty
   * `ids` sends nothing and resolves to `[]`.
   * @param ids - The aggregate IDs
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns A promise that resolves to the states found
   */
  getStateByIds(
    ids: string[],
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S[]>;
}
