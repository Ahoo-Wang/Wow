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

import type { DomainEventStream } from './domainEventStream.js';
import type { QueryApi } from '../queryApi.js';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Interface for event stream query API operations.
 * Extends the base QueryApi interface but omits the 'single' method,
 * as event stream queries typically work with collections of events rather than single events,
 * and adds `load`/`loadStream`, which replay one aggregate's versions.
 * @template DomainEventBody - The type of the domain event bodies
 */
export interface EventStreamQueryApi<
  DomainEventBody = unknown,
  FIELDS extends string = string,
> extends Omit<QueryApi<DomainEventStream<DomainEventBody>, FIELDS>, 'single'> {
  /**
   * Loads one aggregate's event streams from `headVersion` to `tailVersion`,
   * both inclusive, in version order.
   * `GET {id}/event/{headVersion}/{tailVersion}`.
   *
   * @param id - The aggregate id
   * @param headVersion - The first version, from 1
   * @param tailVersion - The last version
   * @param attributes - Optional shared attributes for the interceptors
   * @param abort - Cancels the request: an `AbortController`, or an `AbortSignal` such as `AbortSignal.timeout(ms)` or the `signal` a data-fetching library passes.
   * @returns The event streams of versions `headVersion` to `tailVersion`
   */
  load<
    T extends Partial<DomainEventStream<DomainEventBody>> =
      DomainEventStream<DomainEventBody>,
  >(
    id: string,
    headVersion: number,
    tailVersion: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<T[]>;

  /**
   * `load` as a server-sent event stream, one event stream per event. The
   * stream errors with a `WowError` if the server fails midway.
   */
  loadStream<
    T extends Partial<DomainEventStream<DomainEventBody>> =
      DomainEventStream<DomainEventBody>,
  >(
    id: string,
    headVersion: number,
    tailVersion: number,
    attributes?: Record<string, unknown>,
    abort?: AbortController | AbortSignal,
  ): Promise<ReadableStream<JsonServerSentEvent<T>>>;
}
