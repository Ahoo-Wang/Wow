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

/**
 * Loads the state of an aggregate by its ID: the current state, or the state
 * at a version or a point in time. `LoadStateAggregateClient`
 * implements it; code written against the interface can take a test double.
 * @template S - The type of the aggregate state
 */
export interface LoadStateAggregateApi<S> {
  /**
   * Loads the current state of the aggregate.
   * @param id - The aggregate ID
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   */
  load(
    id: string,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;

  /**
   * Loads the state of the aggregate at `version`.
   * @param id - The aggregate ID
   * @param version - The aggregate version
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   */
  loadVersioned(
    id: string,
    version: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;

  /**
   * Loads the state of the aggregate as of `createTime`.
   * @param id - The aggregate ID
   * @param createTime - The point in time, in epoch milliseconds
   * @param attributes - Optional shared attributes that can be accessed by interceptors
   *                     throughout the request lifecycle. These attributes allow passing
   *                     custom data between different interceptors.
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   */
  loadTimeBased(
    id: string,
    createTime: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<S>;
}
