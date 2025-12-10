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

import { ListQuery } from '@ahoo-wang/fetcher-wow';
import { FetcherError } from '@ahoo-wang/fetcher';
import { UseQueryReturn } from './useQuery';
import { useFetcherQuery, UseFetcherQueryOptions } from './useFetcherQuery';
import {
  JsonEventStreamResultExtractor,
  JsonServerSentEvent,
} from '@ahoo-wang/fetcher-eventstream';

/**
 * Options for configuring the useFetcherListStreamQuery hook.
 *
 * This interface extends UseFetcherQueryOptions and is specifically tailored for list stream queries
 * that use a ListQuery to filter results and return a ReadableStream of JSON server-sent events.
 *
 * @template R - The type of the resource or entity contained in each event in the stream.
 * @template FIELDS - A string union type representing the fields that can be used in the list query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<
  ListQuery<FIELDS>,
  ReadableStream<JsonServerSentEvent<R>>,
  E
> {}

/**
 * Return type for the useFetcherListStreamQuery hook.
 *
 * This interface extends UseQueryReturn and provides the structure for the hook's return value,
 * including data (a ReadableStream of JSON server-sent events), loading state, error state, and other query-related properties.
 *
 * @template R - The type of the resource or entity contained in each event in the stream.
 * @template FIELDS - A string union type representing the fields that can be used in the list query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<
  ListQuery<FIELDS>,
  ReadableStream<JsonServerSentEvent<R>>,
  E
> {}

/**
 * A React hook for performing list stream queries using the Fetcher library with server-sent events.
 *
 * This hook is designed for scenarios where you need to retrieve a stream of data that matches a list query condition.
 * It returns a ReadableStream of JSON server-sent events, allowing for real-time data streaming.
 * The hook automatically configures the JsonEventStreamResultExtractor for proper stream handling.
 *
 * @template R - The type of the resource or entity contained in each event in the stream.
 * @template FIELDS - A string union type representing the fields that can be used in the list query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 *
 * @param options - Configuration options for the list stream query, including the list query, fetcher instance, and other query settings.
 * @returns An object containing the query result (a ReadableStream of JSON server-sent events), loading state, error state, and utility functions.
 *
 * @throws {E} Throws an error of type E if the query fails, which could be due to network issues, invalid queries, or server errors.
 *
 * @example
 * ```typescript
 * import { useFetcherListStreamQuery } from '@ahoo-wang/fetcher-react';
 * import { ListQuery } from '@ahoo-wang/fetcher-wow';
 * import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
 *
 * interface User {
 *   id: number;
 *   name: string;
 * }
 *
 * function UserStreamComponent() {
 *   const { data: stream, loading, error } = useFetcherListStreamQuery<User>({
 *     fetcher: myFetcher,
 *     query: { fields: ['id', 'name'], limit: 10 } as ListQuery<'id' | 'name'>,
 *     autoExecute: true,
 *   });
 *
 *   if (loading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   // Handle the stream
 *   useEffect(() => {
 *     if (stream) {
 *       const reader = stream.getReader();
 *       const readStream = async () => {
 *         try {
 *           while (true) {
 *             const { done, value } = await reader.read();
 *             if (done) break;
 *             console.log('Received event:', value);
 *             // Process the JsonServerSentEvent<User>
 *           }
 *         } catch (err) {
 *           console.error('Stream error:', err);
 *         }
 *       };
 *       readStream();
 *     }
 *   }, [stream]);
 *
 *   return <div>Streaming users...</div>;
 * }
 * ```
 */
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E> {
  const streamOptions = {
    ...options,
    resultExtractor: JsonEventStreamResultExtractor,
  };
  return useFetcherQuery<
    ListQuery<FIELDS>,
    ReadableStream<JsonServerSentEvent<R>>,
    E
  >(streamOptions);
}
