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

import type { QueryHookOptions, QueryHookReturn } from '../types.js';
import { type Endpoint, endpointIdentity, postQuery } from './endpoint.js';
import { useQueryRunner } from './useQueryRunner.js';

/**
 * The request `useFetcher…` hooks: the runner with the endpoint's executor as
 * `execute`, and the endpoint as part of the request's identity, so a change
 * of `url` or `fetcher` runs the query again.
 */
export function useEndpointRunner<Q extends object, R, E>(
  options: Omit<QueryHookOptions<Q, R, E>, 'execute'> & Endpoint,
): QueryHookReturn<Q, R, E> {
  const { url, fetcher, ...rest } = options;
  return useQueryRunner<Q, R, E>(
    { ...rest, execute: postQuery<Q, R>({ url, fetcher }) },
    endpointIdentity({ url, fetcher }),
  );
}
