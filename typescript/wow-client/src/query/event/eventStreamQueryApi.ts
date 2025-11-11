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

import type { DomainEventStream } from './domainEventStream';
import type { QueryApi } from '../queryApi';

/**
 * Interface for event stream query API operations.
 * Extends the base QueryApi interface but omits the 'single' method,
 * as event stream queries typically work with collections of events rather than single events.
 * @template DomainEventStream - The type of domain event stream this API works with
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface EventStreamQueryApi<
  DomainEventBody = any,
  FIELDS extends string = string,
> extends Omit<
    QueryApi<DomainEventStream<DomainEventBody>, FIELDS>,
    'single'
  > {}

/**
 * Provides endpoint paths for event stream query operations.
 *
 * This class contains static readonly properties that define the endpoint paths used for various event stream query operations.
 * These paths are used when making API calls to retrieve event stream data in different formats such as counts, lists, and paged results.
 * The paths are constructed based on a base resource name and extended with specific operation identifiers.
 */
export class EventStreamQueryEndpointPaths {
  static readonly EVENT_STREAM_RESOURCE_NAME = 'event';
  static readonly COUNT = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/count`;
  static readonly LIST = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/list`;
  static readonly PAGED = `${EventStreamQueryEndpointPaths.EVENT_STREAM_RESOURCE_NAME}/paged`;
}
