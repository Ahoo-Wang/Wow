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
  type EventStreamQueryApi,
  EventStreamQueryEndpointPaths,
} from './eventStreamQueryApi';
import type { Condition } from '../condition';
import type { ListQuery, PagedList, PagedQuery } from '../queryable';
import type { DomainEventStream } from './domainEventStream';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { QueryClient } from '../queryApi';
import type { ClientOptions } from '../../types';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { ResultExtractors } from '@ahoo-wang/fetcher-decorator';

/**
 * Client for querying event streams through HTTP endpoints.
 * Extends QueryClient and implements EventStreamQueryApi to provide methods
 * for querying domain event streams with different query patterns.
 * This client supports counting, listing, streaming, and paging operations on event streams.
 *
 * @example
 * ```typescript
 * // Create client options configuration
 * const clientOptions: ClientOptions = {
 *   fetcher: new Fetcher({ baseURL: 'http://localhost:8080/' }),
 *   basePath: 'owner/{ownerId}/cart'
 * };
 *
 * // Create an event stream query client instance
 * const eventStreamQueryClient = new EventStreamQueryClient(clientOptions);
 *
 * // Count event streams
 * const count = await eventStreamQueryClient.count(all());
 *
 * // List event streams
 * const listQuery: ListQuery = {
 *   condition: all()
 * };
 * const list = await eventStreamQueryClient.list(listQuery);
 *
 * // List event streams as stream
 * const listStream = await eventStreamQueryClient.listStream(listQuery);
 * for await (const event of listStream) {
 *   const domainEventStream = event.data;
 *   console.log('Received:', domainEventStream);
 * }
 *
 * // Paged event streams
 * const pagedQuery: PagedQuery = {
 *   condition: all()
 * };
 * const paged = await eventStreamQueryClient.paged(pagedQuery);
 * ```
 */
export class EventStreamQueryClient
  extends QueryClient
  implements EventStreamQueryApi {
  /**
   * Creates a new EventStreamQueryClient instance.
   * @param options - The client configuration options including fetcher and base path
   */
  constructor(options: ClientOptions) {
    super(options);
  }

  /**
   * Counts the number of domain event streams that match the given condition.
   *
   * @param condition - The condition to filter event streams
   * @returns A promise that resolves to the count of matching event streams
   *
   * @example
   * ```typescript
   * const count = await eventStreamQueryClient.count(all());
   * console.log('Total event streams:', count);
   * ```
   */
  count(condition: Condition): Promise<number> {
    return this.query(EventStreamQueryEndpointPaths.COUNT, condition);
  }

  /**
   * Retrieves a list of domain event streams based on the provided query parameters.
   *
   * @param listQuery - The query parameters for listing event streams
   * @returns A promise that resolves to an array of partial domain event streams
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const list = await eventStreamQueryClient.list(listQuery);
   * for (const domainEventStream of list) {
   *   console.log('Event stream:', domainEventStream);
   * }
   * ```
   */
  list<T extends Partial<DomainEventStream> = Partial<DomainEventStream>>(
    listQuery: ListQuery,
  ): Promise<T[]> {
    return this.query(EventStreamQueryEndpointPaths.LIST, listQuery);
  }

  /**
   * Retrieves a stream of domain event streams based on the provided query parameters.
   * Sets the Accept header to text/event-stream to indicate that the response should be streamed.
   *
   * @param listQuery - The query parameters for listing event streams
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial domain event streams
   *
   * @example
   * ```typescript
   * const listQuery: ListQuery = {
   *   condition: all()
   * };
   * const listStream = await eventStreamQueryClient.listStream(listQuery);
   * for await (const event of listStream) {
   *   const domainEventStream = event.data;
   *   console.log('Received event stream:', domainEventStream);
   * }
   * ```
   */
  listStream<T extends Partial<DomainEventStream> = Partial<DomainEventStream>>(
    listQuery: ListQuery,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>> {
    return this.query(
      EventStreamQueryEndpointPaths.LIST,
      listQuery,
      ContentTypeValues.TEXT_EVENT_STREAM,
      ResultExtractors.JsonEventStream,
    );
  }

  /**
   * Retrieves a paged list of domain event streams based on the provided query parameters.
   *
   * @param pagedQuery - The query parameters for paging event streams
   * @returns A promise that resolves to a paged list of partial domain event streams
   *
   * @example
   * ```typescript
   * const pagedQuery: PagedQuery = {
   *   condition: all(),
   *   limit: 10,
   *   offset: 0
   * };
   * const paged = await eventStreamQueryClient.paged(pagedQuery);
   * console.log('Total:', paged.total);
   * for (const domainEventStream of paged.list) {
   *   console.log('Event stream:', domainEventStream);
   * }
   * ```
   */
  paged<T extends Partial<DomainEventStream> = Partial<DomainEventStream>>(
    pagedQuery: PagedQuery,
  ): Promise<PagedList<T>> {
    return this.query(EventStreamQueryEndpointPaths.PAGED, pagedQuery);
  }
}
