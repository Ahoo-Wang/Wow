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

import { EventStreamQueryApi, EventStreamQueryEndpointPaths } from './eventStreamQueryApi';
import { Condition } from '../condition';
import { ListQuery, PagedList, PagedQuery } from '../queryable';
import { DomainEventStream } from './domainEventStream';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { QueryClient } from '../queryApi';
import { ClientOptions } from '../../types';
import { ContentTypeValues } from '@ahoo-wang/fetcher';
import { ResultExtractors } from '@ahoo-wang/fetcher-decorator';

/**
 * Client for querying event streams through HTTP endpoints.
 * Extends QueryClient and implements EventStreamQueryApi to provide methods
 * for querying domain event streams with different query patterns.
 * This client supports counting, listing, streaming, and paging operations on event streams.
 */
export class EventStreamQueryClient extends QueryClient implements EventStreamQueryApi {
  /**
   * Creates a new EventStreamQueryClient instance.
   * @param options - The client configuration options including fetcher and base path
   */
  constructor(options: ClientOptions) {
    super(options);
  }

  /**
   * Counts the number of domain event streams that match the given condition.
   * @param condition - The condition to filter event streams
   * @returns A promise that resolves to the count of matching event streams
   */
  count(condition: Condition): Promise<number> {
    return this.query(EventStreamQueryEndpointPaths.COUNT, condition);
  }

  /**
   * Retrieves a list of domain event streams based on the provided query parameters.
   * @param listQuery - The query parameters for listing event streams
   * @returns A promise that resolves to an array of partial domain event streams
   */
  list(listQuery: ListQuery): Promise<Partial<DomainEventStream>[]> {
    return this.query(EventStreamQueryEndpointPaths.LIST, listQuery);
  }

  /**
   * Retrieves a stream of domain event streams based on the provided query parameters.
   * Sets the Accept header to text/event-stream to indicate that the response should be streamed.
   * @param listQuery - The query parameters for listing event streams
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial domain event streams
   */
  listStream(listQuery: ListQuery): Promise<ReadableStream<JsonServerSentEvent<Partial<DomainEventStream>>>> {
    return this.query(EventStreamQueryEndpointPaths.LIST, listQuery, ContentTypeValues.TEXT_EVENT_STREAM, ResultExtractors.JsonEventStream);
  }

  /**
   * Retrieves a paged list of domain event streams based on the provided query parameters.
   * @param pagedQuery - The query parameters for paging event streams
   * @returns A promise that resolves to a paged list of partial domain event streams
   */
  paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<DomainEventStream>>> {
    return this.query(EventStreamQueryEndpointPaths.PAGED, pagedQuery);
  }
}