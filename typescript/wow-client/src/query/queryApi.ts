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

import type {
  ListQuery,
  PagedList,
  PagedQuery,
  SingleQuery,
} from './queryable';
import {
  JsonServerSentEvent,
} from '@ahoo-wang/fetcher-eventstream';
import type { Condition } from './condition';

/**
 * Interface for generic query API operations.
 * Provides methods for querying resources in different ways including single item retrieval,
 * list retrieval, streaming lists, paged retrieval, and counting.
 *
 * @see {@link EventStreamQueryApi}
 * @see {@link SnapshotQueryApi}
 * @template R - The type of resource being queried
 */
export interface QueryApi<R, FIELDS extends string = string> {
  /**
   * Retrieves a single resource based on the provided query parameters.
   * @param singleQuery - The query parameters for retrieving a single resource
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a partial resource
   */
  single<T extends Partial<R> = Partial<R>>(
    singleQuery: SingleQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T>;

  /**
   * Retrieves a list of resources based on the provided query parameters.
   * @param listQuery - The query parameters for listing resources
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to an array of partial resources
   */
  list<T extends Partial<R> = Partial<R>>(
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<T[]>;

  /**
   * Retrieves a stream of resources based on the provided query parameters.
   * @param listQuery - The query parameters for listing resources
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial resources
   */
  listStream<T extends Partial<R> = Partial<R>>(
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;

  /**
   * Retrieves a paged list of resources based on the provided query parameters.
   * @param pagedQuery - The query parameters for paging resources
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to a paged list of partial resources
   */
  paged<T extends Partial<R> = Partial<R>>(
    pagedQuery: PagedQuery<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<PagedList<T>>;

  /**
   * Counts the number of resources that match the given condition.
   * @param condition - The condition to filter resources
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @returns A promise that resolves to the count of matching resources
   */
  count(
    condition: Condition<FIELDS>,
    attributes?: Record<string, any>,
  ): Promise<number>;
}
