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
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import type { Condition } from './condition';
import type { ClientOptions } from '../types';
import { combineURLs, ContentTypeValues, HttpMethod } from '@ahoo-wang/fetcher';
import {
  type ResultExtractor,
  ResultExtractors,
} from '@ahoo-wang/fetcher-decorator';

/**
 * Interface for generic query API operations.
 * Provides methods for querying resources in different ways including single item retrieval,
 * list retrieval, streaming lists, paged retrieval, and counting.
 *
 * @see {@link EventStreamQueryApi}
 * @see {@link SnapshotQueryApi}
 * @template R - The type of resource being queried
 */
export interface QueryApi<R> {
  /**
   * Retrieves a single resource based on the provided query parameters.
   * @param singleQuery - The query parameters for retrieving a single resource
   * @returns A promise that resolves to a partial resource
   */
  single<T extends Partial<R> = Partial<R>>(
    singleQuery: SingleQuery,
  ): Promise<T>;

  /**
   * Retrieves a list of resources based on the provided query parameters.
   * @param listQuery - The query parameters for listing resources
   * @returns A promise that resolves to an array of partial resources
   */
  list<T extends Partial<R> = Partial<R>>(listQuery: ListQuery): Promise<T[]>;

  /**
   * Retrieves a stream of resources based on the provided query parameters.
   * @param listQuery - The query parameters for listing resources
   * @returns A promise that resolves to a readable stream of JSON server-sent events containing partial resources
   */
  listStream<T extends Partial<R> = Partial<R>>(
    listQuery: ListQuery,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;

  /**
   * Retrieves a paged list of resources based on the provided query parameters.
   * @param pagedQuery - The query parameters for paging resources
   * @returns A promise that resolves to a paged list of partial resources
   */
  paged<T extends Partial<R> = Partial<R>>(
    pagedQuery: PagedQuery,
  ): Promise<PagedList<T>>;

  /**
   * Counts the number of resources that match the given condition.
   * @param condition - The condition to filter resources
   * @returns A promise that resolves to the count of matching resources
   */
  count(condition: Condition): Promise<number>;
}

/**
 * Base client for performing query operations.
 * Provides a generic query method that handles the common logic for sending requests
 * and processing responses for different types of queries.
 *
 * @see {@link EventStreamQueryClient}
 * @see {@link SnapshotQueryClient}
 */
export class QueryClient {
  /**
   * Creates a new QueryClient instance.
   * @param options - The client configuration options including fetcher and base path
   */
  constructor(protected readonly options: ClientOptions) {
  }

  /**
   * Performs a generic query operation by sending a request to the specified path.
   * @template R The return type of the query
   * @param path - The endpoint path to query
   * @param query - The query parameters to send
   * @param accept - The content type to accept from the server, defaults to application/json
   * @param extractor - Function to extract the result from the response, defaults to JSON extractor
   * @returns A promise that resolves to the query result
   */
  protected async query<R>(
    path: string,
    query: Condition | ListQuery | PagedQuery | SingleQuery,
    accept: string = ContentTypeValues.APPLICATION_JSON,
    extractor: ResultExtractor = ResultExtractors.Json,
  ): Promise<R> {
    const url = combineURLs(this.options.basePath, path);
    const request = {
      url: url,
      method: HttpMethod.POST,
      headers: {
        Accept: accept,
      },
      body: query,
    };
    const exchange = await this.options.fetcher.request(request);
    return extractor(exchange);
  }
}
