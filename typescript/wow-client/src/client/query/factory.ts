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

import type { PartialBy } from '@ahoo-wang/fetcher';
import { combineURLs } from '@ahoo-wang/fetcher';
import type { ApiMetadata } from '@ahoo-wang/fetcher-decorator';
import type {
  AggregateNameCapable,
  AliasBoundedContext,
} from '../../model/index.js';
import type { ResourceAttributionPathSpec } from '../routing.js';
import { SnapshotQueryClient } from './snapshot/index.js';
import { EventStreamQueryClient } from './event/index.js';
import { LoadStateAggregateClient } from './state/index.js';
import { LoadOwnerStateAggregateClient } from './state/index.js';

/**
 * Configuration options for query clients: the client's `ApiMetadata`
 * (`fetcher`, `headers`, `urlParams`, …, with `basePath` optional), plus
 * three keys only {@link QueryClientFactory} reads to build the base path:
 * `contextAlias`, `resourceAttribution` and `aggregateName`.
 *
 * The base path is `basePath` when one is given, and then the three keys
 * are not used. Otherwise it is
 * `{contextAlias}{resourceAttribution}/{aggregateName}`, each part left out
 * when absent. The three keys are not passed on to the client.
 */
export interface QueryClientOptions
  extends
    PartialBy<ApiMetadata, 'basePath'>,
    Partial<AliasBoundedContext>,
    Partial<AggregateNameCapable> {
  contextAlias?: string;
  resourceAttribution?: ResourceAttributionPathSpec;
}

/**
 * The `ApiMetadata` a factory gives a client: `options` over the factory's
 * `defaults`, the base path resolved, and the keys only the factory reads
 * left out.
 */
function queryApiMetadata(
  defaults: QueryClientOptions,
  options?: QueryClientOptions,
): ApiMetadata {
  const { contextAlias, resourceAttribution, aggregateName, ...apiMetadata } = {
    ...defaults,
    ...options,
  };
  return {
    ...apiMetadata,
    basePath:
      options?.basePath ??
      defaults.basePath ??
      routePath(contextAlias, resourceAttribution, aggregateName),
  };
}

/** `{contextAlias}{resourceAttribution}/{aggregateName}`. */
function routePath(
  contextAlias: string | undefined,
  resourceAttribution: ResourceAttributionPathSpec | undefined,
  aggregateName: string | undefined,
): string {
  const path = combineURLs(resourceAttribution ?? '', aggregateName ?? '');
  return contextAlias ? combineURLs(contextAlias, path) : path;
}

export class QueryClientFactory<
  S,
  FIELDS extends string = string,
  DomainEventBody = unknown,
> {
  /**
   * Creates a new QueryClientFactory instance with the specified default options.
   *
   * @param defaultOptions - The default options to be used for all query clients created by this factory
   *
   * @example
   * ```typescript
   * import { QueryClientFactory, ResourceAttributionPathSpec } from '@ahoo-wang/wow-client';
   *
   * const factory = new QueryClientFactory({
   *   contextAlias: 'example',
   *   aggregateName: 'cart',
   *   resourceAttribution: ResourceAttributionPathSpec.OWNER,
   * });
   * ```
   */
  constructor(private readonly defaultOptions: QueryClientOptions) {}

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
   * const cartState = await snapshotClient.singleState(singleQuery());
   * ```
   */
  createSnapshotQueryClient(
    options?: QueryClientOptions,
  ): SnapshotQueryClient<S, FIELDS> {
    return new SnapshotQueryClient(
      queryApiMetadata(this.defaultOptions, options),
    );
  }

  /**
   * Creates a client for loading aggregate state by ID.
   *
   * This method merges the provided options with the factory's default options,
   * then creates API metadata and instantiates a LoadStateAggregateClient.
   * The client supports loading current state, versioned state, and time-based state.
   *
   * @param options - The query client options used to configure the state aggregate client
   * @returns A new instance of LoadStateAggregateClient
   *
   * @example
   * ```typescript
   * const stateClient = factory.createLoadStateAggregateClient({
   *   aggregateName: 'cart',
   * });
   *
   * // Load current state
   * const currentState = await stateClient.load('cart-123');
   *
   * // Load specific version
   * const versionedState = await stateClient.loadVersioned('cart-123', 5);
   *
   * // Load state at specific time
   * const timeBasedState = await stateClient.loadTimeBased('cart-123', Date.now());
   * ```
   */
  createLoadStateAggregateClient(
    options?: QueryClientOptions,
  ): LoadStateAggregateClient<S> {
    return new LoadStateAggregateClient(
      queryApiMetadata(this.defaultOptions, options),
    );
  }

  /**
   * Creates a client for loading owner-specific aggregate state.
   *
   * This method merges the provided options with the factory's default options,
   * then creates API metadata and instantiates a LoadOwnerStateAggregateClient.
   * Unlike the standard state client, this client loads state for the current owner
   * without requiring an explicit ID parameter.
   *
   * @param options - The query client options used to configure the owner state aggregate client
   * @returns A new instance of LoadOwnerStateAggregateClient
   *
   * @example
   * ```typescript
   * const ownerStateClient = factory.createLoadOwnerStateAggregateClient({
   *   aggregateName: 'cart',
   *   resourceAttribution: ResourceAttributionPathSpec.OWNER,
   * });
   *
   * // Load current owner's state
   * const currentState = await ownerStateClient.load();
   *
   * // Load specific version of owner's state
   * const versionedState = await ownerStateClient.loadVersioned(5);
   *
   * // Load owner's state at specific time
   * const timeBasedState = await ownerStateClient.loadTimeBased(Date.now());
   * ```
   */
  createLoadOwnerStateAggregateClient(
    options?: QueryClientOptions,
  ): LoadOwnerStateAggregateClient<S> {
    return new LoadOwnerStateAggregateClient(
      queryApiMetadata(this.defaultOptions, options),
    );
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
   * const events = await eventClient.list(listQuery());
   * ```
   */
  createEventStreamQueryClient<EVENT_FIELDS extends string = string>(
    options?: QueryClientOptions,
  ): EventStreamQueryClient<DomainEventBody, EVENT_FIELDS> {
    return new EventStreamQueryClient(
      queryApiMetadata(this.defaultOptions, options),
    );
  }
}
