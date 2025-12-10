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


import { FetcherError } from '@ahoo-wang/fetcher';
import { UseQueryReturn } from './useQuery';
import { useFetcherQuery, UseFetcherQueryOptions } from './useFetcherQuery';
import { Condition } from '@ahoo-wang/fetcher-wow';

export interface UseFetcherCountQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<Condition<FIELDS>, R, E> {}

export interface UseFetcherCountQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<Condition<FIELDS>, R, E> {}

export function useFetcherCountQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<R, FIELDS, E>,
): UseFetcherCountQueryReturn<R, FIELDS, E> {
  return useFetcherQuery<Condition<FIELDS>, R, E>(options);
}
