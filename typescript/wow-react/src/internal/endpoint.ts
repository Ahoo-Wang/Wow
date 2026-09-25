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

/*
 * The Wow query endpoint protocol, in one place: a query is POSTed as the
 * JSON body to its endpoint; the answer is JSON, or, for a list stream, an
 * event stream the server sends only when asked with
 * `Accept: text/event-stream`. The `useFetcher…` hooks are the matching
 * `use…Query` hook with one of these executors as `execute`.
 */

import type { Fetcher } from '@ahoo-wang/fetcher';
import { getFetcher, JsonResultExtractor } from '@ahoo-wang/fetcher';
import { QUERY_STREAM_ENDPOINT } from '@ahoo-wang/wow-client';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import type { ListStreamExecutor, QueryExecutor } from '../types.js';

/**
 * Where a `useFetcher…` hook sends its query: the endpoint, resolved against
 * the Fetcher's `baseURL`, and the Fetcher or the name of a registered one,
 * the default Fetcher when omitted.
 */
export interface Endpoint {
  url: string;
  fetcher?: string | Fetcher;
}

/**
 * Runs a query by POSTing it to the endpoint and reading the JSON answer.
 * The Fetcher is resolved when the request is sent, so a name that is not
 * registered fails that request, and the hook reports it as its `error`.
 */
export function postQuery<Q extends object, R>({
  url,
  fetcher,
}: Endpoint): QueryExecutor<Q, R> {
  return (query, attributes, abortController) =>
    getFetcher(fetcher).post<R>(
      url,
      { body: query, abortController },
      { attributes, resultExtractor: JsonResultExtractor },
    );
}

/**
 * Opens the event stream of a list query by POSTing it to the endpoint with
 * wow-client's `QUERY_STREAM_ENDPOINT`: `Accept: text/event-stream`, and an
 * extractor that ends the stream with a `WowError` at the server's error
 * event.
 */
export function postQueryStream<R, Q extends object>({
  url,
  fetcher,
}: Endpoint): ListStreamExecutor<R, Q> {
  return (query, attributes, abortController) =>
    getFetcher(fetcher).post<ReadableStream<JsonServerSentEvent<R>>>(
      url,
      {
        body: query,
        // A copy: the Fetcher may add headers to the request it sends.
        headers: { ...QUERY_STREAM_ENDPOINT.headers },
        abortController,
      },
      { attributes, resultExtractor: QUERY_STREAM_ENDPOINT.resultExtractor },
    );
}
