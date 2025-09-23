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

import type { QueryApi } from '../queryApi';
import type { MaterializedSnapshot } from './snapshot';
import type {
  ListQuery,
  PagedList,
  PagedQuery,
  SingleQuery,
} from '../queryable';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Interface for snapshot query API operations.
 * Extends the base QueryApi interface for MaterializedSnapshot and adds methods
 * for querying snapshot states directly without the full MaterializedSnapshot wrapper.
 * @template S - The type of the snapshot state
 */
export interface SnapshotQueryApi<S, FIELDS extends string = string> extends QueryApi<MaterializedSnapshot<S>, FIELDS> {
  /**
   * Retrieves a single snapshot state based on the provided query parameters.
   * @param singleQuery - The query parameters for retrieving a single snapshot state
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a partial snapshot state
   */
  singleState<T extends Partial<S> = Partial<S>>(
    singleQuery: SingleQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T>;

  /**
   * Retrieves a list of snapshot states based on the provided query parameters.
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to an array of partial snapshot states
   */
  listState<T extends Partial<S> = Partial<S>>(
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T[]>;

  /**
   * Retrieves a stream of snapshot states based on the provided query parameters.
   * @param listQuery - The query parameters for listing snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial snapshot states
   */
  listStateStream<T extends Partial<S> = Partial<S>>(
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;

  /**
   * Retrieves a paged list of snapshot states based on the provided query parameters.
   * @param pagedQuery - The query parameters for paging snapshot states
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a paged list of partial snapshot states
   */
  pagedState<T extends Partial<S> = Partial<S>>(
    pagedQuery: PagedQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<PagedList<T>>;
}

/**
 * Provides endpoint paths for snapshot query operations.
 *
 * This class contains static readonly properties that define the endpoint paths used for various snapshot query operations.
 * These paths are used when making API calls to retrieve snapshot data in different formats such as counts, lists, paged results, and single items.
 * The paths are constructed based on a base resource name and extended with specific operation identifiers.
 */
export class SnapshotQueryEndpointPaths {
  static readonly SNAPSHOT_RESOURCE_NAME = 'snapshot';
  static readonly COUNT = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/count`;
  static readonly LIST = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/list`;
  static readonly LIST_STATE = `${SnapshotQueryEndpointPaths.LIST}/state`;
  static readonly PAGED = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/paged`;
  static readonly PAGED_STATE = `${SnapshotQueryEndpointPaths.PAGED}/state`;
  static readonly SINGLE = `${SnapshotQueryEndpointPaths.SNAPSHOT_RESOURCE_NAME}/single`;
  static readonly SINGLE_STATE = `${SnapshotQueryEndpointPaths.SINGLE}/state`;
}
