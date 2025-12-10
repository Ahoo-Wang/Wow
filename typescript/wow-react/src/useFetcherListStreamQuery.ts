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
import { JsonEventStreamResultExtractor, JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<ListQuery<FIELDS>, ReadableStream<JsonServerSentEvent<R>>, E> {
}

export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<ListQuery<FIELDS>, ReadableStream<JsonServerSentEvent<R>>, E> {
}

export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E> {
  options.resultExtractor = JsonEventStreamResultExtractor;
  return useFetcherQuery<ListQuery<FIELDS>, ReadableStream<JsonServerSentEvent<R>>, E>(options);
}
