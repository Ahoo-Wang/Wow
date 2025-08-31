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

import { ListQuery, PagedList, PagedQuery, SingleQuery } from './queryable';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { Condition } from './condition';
import { ClientOptions } from '../types';
import { combineURLs, ContentTypeValues, HttpMethod } from '@ahoo-wang/fetcher';
import { ResultExtractor, ResultExtractors } from '@ahoo-wang/fetcher-decorator';

export interface QueryApi<R> {
  single(singleQuery: SingleQuery): Promise<Partial<R>>;

  list(listQuery: ListQuery): Promise<Partial<R>[]>;

  listStream(
    listQuery: ListQuery,
  ): Promise<ReadableStream<JsonServerSentEvent<Partial<R>>>>;

  paged(pagedQuery: PagedQuery): Promise<PagedList<Partial<R>>>;

  count(condition: Condition): Promise<number>;
}

export class QueryClient {
  constructor(protected readonly options: ClientOptions) {
  }

  /**
   * Performs a generic query operation by sending a request to the specified path.
   * @template R The return type of the query
   * @param path - The endpoint path to query
   * @param query - The query parameters to send
   * @param accept - The content type to accept from the server
   * @param extractor - Function to extract the result from the response, defaults to JSON extractor
   * @returns A promise that resolves to the query result
   */
  protected async query<R>(path: string,
                           query: Condition | ListQuery | PagedQuery | SingleQuery,
                           accept: string = ContentTypeValues.APPLICATION_JSON,
                           extractor: ResultExtractor = ResultExtractors.Json): Promise<R> {
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