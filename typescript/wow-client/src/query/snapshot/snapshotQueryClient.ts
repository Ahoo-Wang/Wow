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
  SnapshotQueryApi,
  SnapshotQueryEndpointPaths,
} from './snapshotQueryApi';
import { Condition } from '../condition';
import { ListQuery, PagedList, PagedQuery, SingleQuery } from '../queryable';
import { MaterializedSnapshot } from './snapshot';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { ResultExtractors } from '@ahoo-wang/fetcher-decorator';
import '@ahoo-wang/fetcher-eventstream';
import { ClientOptions } from '../../types';
import { QueryClient } from '../queryApi';

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
   * @returns A promise that resolves to the count of matching snapshots
   *
   * @example
   * ```typescript
   * const count = await snapshotQueryClient.count(all());
   * console.log('Total snapshots:', count);
   * ```
   */
  async count(condition: Condition): Promise<number> {
    return this.query(SnapshotQueryEndpointPaths.COUNT, condition);
  }

  /**
   * Retrieves a list of materialized snapshots based on the provided query parameters.
   * 
   * @param listQuery - The query parameters for listing snapshots
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
  list(listQuery: ListQuery): Promise<Partial<MaterializedSnapshot<S>>[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST, listQuery);
  }

  /**
   * Retrieves a stream of materialized snapshots based on the provided query parameters.
   * 
   * @param listQuery - The query parameters for listing snapshots
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
  listStream(
    listQuery: ListQuery,
  ): Promise<
    ReadableStream<JsonServerSentEvent<Partial<MaterializedSnapshot<S>>>>
  > {
    return this.query(
      SnapshotQueryEndpointPaths.LIST,
      listQuery,
      ContentTypeValues.TEXT_EVENT_STREAM,
      ResultExtractors.JsonEventStream,
    );
  }

  /**
   * Retrieves a list of snapshot states based on the provided query parameters.
   * 
   * @param listQuery - The query parameters for listing snapshot states
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
  listState(listQuery: ListQuery): Promise<Partial<S>[]> {
    return this.query(SnapshotQueryEndpointPaths.LIST_STATE, listQuery);
  }

  /**
   * Retrieves a stream of snapshot states based on the provided query parameters.
   * 
   * @param listQuery - The query parameters for listing snapshot states
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
  listStateStream(
    listQuery: ListQuery,
  ): Promise<ReadableStream<JsonServerSentEvent<Partial<S>>>> {
    return this.query(
      SnapshotQueryEndpointPaths.LIST_STATE,
      listQuery,
      ContentTypeValues.TEXT_EVENT_STREAM,
      ResultExtractors.JsonEventStream,
    );
  }

  /**
   * Retrieves a paged list of materialized snapshots based on the provided query parameters.
   * 
   * @param pagedQuery - The query parameters for paging snapshots
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
  paged(
    pagedQuery: PagedQuery,
  ): Promise<PagedList<Partial<MaterializedSnapshot<S>>>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED, pagedQuery);
  }

  /**
   * Retrieves a paged list of snapshot states based on the provided query parameters.
   * 
   * @param pagedQuery - The query parameters for paging snapshot states
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
  pagedState(pagedQuery: PagedQuery): Promise<PagedList<Partial<S>>> {
    return this.query(SnapshotQueryEndpointPaths.PAGED_STATE, pagedQuery);
  }

  /**
   * Retrieves a single materialized snapshot based on the provided query parameters.
   * 
   * @param singleQuery - The query parameters for retrieving a single snapshot
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
  single(singleQuery: SingleQuery): Promise<Partial<MaterializedSnapshot<S>>> {
    return this.query(SnapshotQueryEndpointPaths.SINGLE, singleQuery);
  }

  /**
   * Retrieves a single snapshot state based on the provided query parameters.
   * 
   * @param singleQuery - The query parameters for retrieving a single snapshot state
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
  singleState(singleQuery: SingleQuery): Promise<Partial<S>> {
    return this.query(SnapshotQueryEndpointPaths.SINGLE_STATE, singleQuery);
  }
}