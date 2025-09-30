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

import { combineURLs, PartialBy } from '@ahoo-wang/fetcher';
import { ApiMetadata } from '@ahoo-wang/fetcher-decorator';
import { AggregateNameCapable, AliasBoundedContext } from '../types';
import { ResourceAttributionPathSpec } from '../types';
import { SnapshotQueryClient } from './snapshot';
import { EventStreamQueryClient } from './event';

/**
 * Configuration options for query clients.
 *
 * This interface extends ApiMetadata (without basePath), AliasBoundedContext, and AggregateNameCapable
 * to provide a complete configuration for query clients. It includes optional context alias and
 * resource attribution path specifications.
 */
export interface QueryClientOptions
  extends PartialBy<ApiMetadata, 'basePath'>,
    Partial<AliasBoundedContext>,
    Partial<AggregateNameCapable> {
  contextAlias?: string;
  resourceAttribution?: ResourceAttributionPathSpec;
}

/**
 * Creates API metadata for query clients by combining various path components.
 *
 * This function constructs a base path by combining the resource attribution path with the aggregate name,
 * and optionally prepending a context alias if provided.
 *
 * @param options - The query client options containing resource attribution, aggregate name, and optional context alias
 * @returns ApiMetadata object with the constructed base path
 */
export function createQueryApiMetadata(
  options: QueryClientOptions,
): ApiMetadata {
  let basePath = combineURLs(
    options.resourceAttribution ?? '',
    options.aggregateName ?? '',
  );
  if (options.contextAlias) {
    basePath = combineURLs(options.contextAlias, basePath);
  }
  return { ...options, basePath };
}

export class QueryClientFactory<
  S,
  FIELDS extends string = string,
  DomainEventBody = any,
> {
  /**
   * Creates a new QueryClientFactory instance with the specified default options.
   *
   * @param defaultOptions - The default options to be used for all query clients created by this factory
   *
   * @example
   * ```typescript
   * import { QueryClientFactory, ResourceAttributionPathSpec } from '@ahoo-wang/fetcher-wow';
   *
   * const factory = new QueryClientFactory({
   *   contextAlias: 'example',
   *   aggregateName: 'cart',
   *   resourceAttribution: ResourceAttributionPathSpec.OWNER,
   * });
   * ```
   */
  constructor(private readonly defaultOptions: QueryClientOptions) {
  }

  /**
   * Creates a snapshot query client for querying aggregate snapshots.
   *
   * This method merges the provided options with the factory's default options,
   * then creates API metadata and instantiates a SnapshotQueryClient.
   *
   * @param options - The query client options used to configure the snapshot query client
   * @returns A new instance of SnapshotQueryClient
   *
   * @example
   * ```typescript
   * const snapshotClient = factory.createSnapshotQueryClient({
   *   aggregateName: 'cart',
   * });
   *
   * const cartState = await snapshotClient.singleState({ condition: all() });
   * ```
   */
  createSnapshotQueryClient(
    options: QueryClientOptions,
  ): SnapshotQueryClient<S, FIELDS> {
    const apiMetadata = createQueryApiMetadata({
      ...this.defaultOptions,
      ...options,
    });
    return new SnapshotQueryClient(apiMetadata);
  }

  /**
   * Creates an event stream query client for querying domain event streams.
   *
   * This method merges the provided options with the factory's default options,
   * then creates API metadata and instantiates an EventStreamQueryClient.
   *
   * @param options - The query client options used to configure the event stream query client
   * @returns A new instance of EventStreamQueryClient
   *
   * @example
   * ```typescript
   * const eventClient = factory.createEventStreamQueryClient({
   *   aggregateName: 'cart',
   * });
   *
   * const events = await eventClient.list({ condition: all() });
   * ```
   */
  createEventStreamQueryClient<FIELDS extends string = string>(
    options: QueryClientOptions,
  ): EventStreamQueryClient<DomainEventBody, FIELDS> {
    const apiMetadata = createQueryApiMetadata({
      ...this.defaultOptions,
      ...options,
    });
    return new EventStreamQueryClient(apiMetadata);
  }
}
