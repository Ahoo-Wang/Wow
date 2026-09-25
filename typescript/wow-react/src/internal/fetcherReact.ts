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
 * The one place the hooks meet fetcher-react. The public types are the
 * package's own (`../types.ts`); fetcher-react still runs the requests, and
 * these two functions translate between the two at the boundary. Nothing of
 * fetcher-react's types leaves this file.
 */

import type { Fetcher } from '@ahoo-wang/fetcher';
import type { UseQueryOptions } from '@ahoo-wang/fetcher-react/core';
import { useQuery } from '@ahoo-wang/fetcher-react/core';
import { useFetcherQuery } from '@ahoo-wang/fetcher-react/fetcher';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/** The options of a hook that POSTs its query to a Wow endpoint. */
export type EndpointQueryOptions<Q, R, E> = Omit<
  QueryHookOptions<Q, R, E>,
  'execute'
> & {
  url: string;
  fetcher?: string | Fetcher;
};

/** Runs `options.execute` through fetcher-react's `useQuery`. */
export function useDelegatedQuery<Q, R, E>(
  options: QueryHookOptions<Q, R, E>,
): QueryHookReturn<Q, R, E> {
  const state = useQuery<Q, R, E>({
    ...options,
    // useQuery always hands `execute` a controller; only its type says it may
    // not, so the executor, which requires one, is safe to pass as it is.
    execute: options.execute as UseQueryOptions<Q, R, E>['execute'],
  });
  return state;
}

/** POSTs the query to `options.url` through fetcher-react's `useFetcherQuery`. */
export function useDelegatedEndpointQuery<Q, R, E>(
  options: EndpointQueryOptions<Q, R, E>,
): QueryHookReturn<Q, R, E> {
  return useFetcherQuery<Q, R, E>(options);
}
