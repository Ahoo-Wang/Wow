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

import {
  type SnapshotQueryApi,
  SnapshotQueryEndpointPaths,
} from './snapshotQueryApi';
import type { Condition } from '../condition';
import type {
  ListQuery,
  PagedList,
  PagedQuery,
  SingleQuery,
} from '../queryable';
import type { MaterializedSnapshot } from './snapshot';
import {
  JsonEventStreamResultExtractor,
  JsonServerSentEvent,
} from '@ahoo-wang/fetcher-eventstream';
import { ContentTypeValues, mergeRequestOptions } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import type { ClientOptions } from '../../types';
import {
  JSON_EVENT_STREAM_QUERY_REQUEST_OPTIONS,
  QueryClient,
} from '../queryApi';

/**
 * A client for querying snapshot data through HTTP endpoints.
 * Provides methods for various query operations such as counting, listing, paging, and retrieving single snapshots.
 *
 * @example
 * ```typescript
 * // Define the state interface
 * interface CartItem {
 *   productId: string;
 *   quantity: number;
 * }
 *
 * interface CartState {
 *   items: CartItem[];
 * }
 *
 * // Create client options configuration
 * const clientOptions: ClientOptions = {
 *   fetcher: new Fetcher({ baseURL: 'http://localhost:8080/' }),
 *   basePath: 'owner/{ownerId}/cart'
 * };
 *
 * // Create a snapshot query client instance
 * const snapshotQueryClient = new SnapshotQueryClient<CartState>(clientOptions);
 *
 * // Count snapshots
 * const count = await snapshotQueryClient.count(all());
 *
 * // List snapshots
 * const listQuery: ListQuery = {
 *   condition: all()
 * };
 * const list = await snapshotQueryClient.list(listQuery);
 *
 * // List snapshots as stream
 * const listStream = await snapshotQueryClient.listStream(listQuery);
 * for await (const event of listStream) {
 *   const snapshot = event.data;
 *   console.log('Received:', snapshot);
 * }
 *
 * // List snapshot states
 * const stateList = await snapshotQueryClient.listState(listQuery);
 *
 * // List snapshot states as stream
 * const stateListStream = await snapshotQueryClient.listStateStream(listQuery);
 * for await (const event of stateListStream) {
 *   const state = event.data;
 *   console.log('Received state:', state);
 * }
 *
 * // Paged snapshots
 * const pagedQuery: PagedQuery = {
 *   condition: all(),
 *   limit: 10,
 *   offset: 0
 * };
 * const paged = await snapshotQueryClient.paged(pagedQuery);
 *
 * // Paged snapshot states
 * const pagedState = await snapshotQueryClient.pagedState(pagedQuery);
 *
 * // Single snapshot
 * const singleQuery: SingleQuery = {
 *   condition: all()
 * };
 * const single = await snapshotQueryClient.single(singleQuery);
 *
 * // Single snapshot state
 * const singleState = await snapshotQueryClient.singleState(singleQuery);
 * ```
 *
 * @template S The type of the snapshot state
 */
export class SnapshotQueryClient<S>
  extends QueryClient
  implements SnapshotQueryApi<S> {
  /**
   * Creates a new SnapshotQueryClient instance.
   * @param options - The configuration options for the client
   */
  constructor(options: ClientOptions) {
    super(options);
  }

  /**
   * Counts the number of snapshots that match the given condition.
   *
   * @param condition - The condition to match snapshots against
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to the count of matching snapshots
   *
   * @example
   * ```typescript
   * const count = await snapshotQueryClient.count(all());
   * console.log('Total snapshots:', count);
   * ```
   */
  async count(
    condition: Condition,
    attributes?: Record<string, any>,
  ): Promise<number> {
    return this.query(SnapshotQueryEndpointPaths.COUNT, condition, {
      attributes,
    });
  }

  /**
   * Retrieves a list of materialized snapshots based on the provided query parameters.
   *
   * @param listQuery - The query parameters for listing snapshots
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to an array of partial materialized snapshots
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const list = await snapshotQueryClient.list(listQuery);
   * for (const snapshot of list) {
   *   console.log('Snapshot:', snapshot);
   * }
   * ```
   */
  list<
    T extends Partial<MaterializedSnapshot<S>> = Partial<
      MaterializedSnapshot<S>
    >,
  >(listQuery: ListQuery, attributes?: Record<string, any>): Promise<T[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST, listQuery, {
      attributes,
    });
  }

  /**
   * Retrieves a stream of materialized snapshots based on the provided query parameters.
   *
   * @param listQuery - The query parameters for listing snapshots
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial materialized snapshots
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const listStream = await snapshotQueryClient.listStream(listQuery);
   * for await (const event of listStream) {
   *   const snapshot = event.data;
   *   console.log('Received snapshot:', snapshot);
   * }
   * ```
   */
  listStream<
    T extends Partial<MaterializedSnapshot<S>> = Partial<
      MaterializedSnapshot<S>
    >,
  >(
    listQuery: ListQuery,
    attributes?: Record<string, any>,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>> {
    return this.query(
      SnapshotQueryEndpointPaths.LIST,
      listQuery,
      mergeRequestOptions(JSON_EVENT_STREAM_QUERY_REQUEST_OPTIONS, {
        resultExtractor: JsonEventStreamResultExtractor,
        attributes,
      }),
      ContentTypeValues.TEXT_EVENT_STREAM,
    );
  }

  /**
   * Retrieves a list of snapshot states based on the provided query parameters.
   *
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to an array of partial snapshot states
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const list = await snapshotQueryClient.listState(listQuery);
   * for (const state of list) {
   *   console.log('State:', state);
   * }
   * ```
   */
  listState<T extends Partial<S> = Partial<S>>(
    listQuery: ListQuery,
    attributes?: Record<string, any>,
  ): Promise<T[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, listQuery, {
      attributes,
    });
  }

  /**
   * Retrieves a stream of snapshot states based on the provided query parameters.
   *
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial snapshot states
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const listStream = await snapshotQueryClient.listStateStream(listQuery);
   * for await (const event of listStream) {
   *   const state = event.data;
   *   console.log('Received state:', state);
   * }
   * ```
   */
  listStateStream<T extends Partial<S> = Partial<S>>(
    listQuery: ListQuery,
    attributes?: Record<string, any>,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>> {
    return this.query(
      SnapshotQueryEndpointPaths.LIST_STATE,
      listQuery,
      mergeRequestOptions(JSON_EVENT_STREAM_QUERY_REQUEST_OPTIONS, {
        resultExtractor: JsonEventStreamResultExtractor,
        attributes,
      }),
      ContentTypeValues.TEXT_EVENT_STREAM,
    );
  }

  /**
   * Retrieves a paged list of materialized snapshots based on the provided query parameters.
   *
   * @param pagedQuery - The query parameters for paging snapshots
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a paged list of partial materialized snapshots
   *
   * @example
   * ```typescript
   * const pagedQuery: PagedQuery = {
   *   condition: all(),
   *   limit: 10,
   *   offset: 0
   * };
   * const paged = await snapshotQueryClient.paged(pagedQuery);
   * console.log('Total:', paged.total);
   * for (const snapshot of paged.list) {
   *   console.log('Snapshot:', snapshot);
   * }
   * ```
   */
  paged<
    T extends Partial<MaterializedSnapshot<S>> = Partial<
      MaterializedSnapshot<S>
    >,
  >(
    pagedQuery: PagedQuery,
    attributes?: Record<string, any>,
  ): Promise<PagedList<T>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED, pagedQuery, {
      attributes,
    });
  }

  /**
   * Retrieves a paged list of snapshot states based on the provided query parameters.
   *
   * @param pagedQuery - The query parameters for paging snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a paged list of partial snapshot states
   *
   * @example
   * ```typescript
   * const pagedQuery: PagedQuery = {
   *   condition: all(),
   *   limit: 10,
   *   offset: 0
   * };
   * const pagedState = await snapshotQueryClient.pagedState(pagedQuery);
   * for (const state of pagedState.list) {
   *   console.log('State:', state);
   * }
   * ```
   */
  pagedState<T extends Partial<S> = Partial<S>>(
    pagedQuery: PagedQuery,
    attributes?: Record<string, any>,
  ): Promise<PagedList<T>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED_STATE, pagedQuery, {
      attributes,
    });
  }

  /**
   * Retrieves a single materialized snapshot based on the provided query parameters.
   *
   * @param singleQuery - The query parameters for retrieving a single snapshot
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a partial materialized snapshot
   *
   * @example
   * ```typescript
   * const singleQuery: SingleQuery = {
   *   condition: all()
   * };
   * const single = await snapshotQueryClient.single(singleQuery);
   * console.log('Snapshot:', single);
   * ```
   */
  single<
    T extends Partial<MaterializedSnapshot<S>> = Partial<
      MaterializedSnapshot<S>
    >,
  >(singleQuery: SingleQuery, attributes?: Record<string, any>): Promise<T> {
    return this.query(SnapshotQueryEndpointPaths.SINGLE, singleQuery, {
      attributes,
    });
  }

  /**
   * Retrieves a single snapshot state based on the provided query parameters.
   *
   * @param singleQuery - The query parameters for retrieving a single snapshot state
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a partial snapshot state
   *
   * @example
   * ```typescript
   * const singleQuery: SingleQuery = {
   *   condition: all()
   * };
   * const singleState = await snapshotQueryClient.singleState(singleQuery);
   * console.log('State:', singleState);
   * ```
   */
  singleState<T extends Partial<S> = Partial<S>>(
    singleQuery: SingleQuery,
    attributes?: Record<string, any>,
  ): Promise<T> {
    return this.query(SnapshotQueryEndpointPaths.SINGLE_STATE, singleQuery, {
      attributes,
    });
  }
}
