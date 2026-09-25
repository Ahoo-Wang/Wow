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

import type {
  HTTPMethod,
  Operation,
  Parameter,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import type { AliasAggregate, Named } from '@ahoo-wang/wow-client';
import type { KeySchema } from '../openapi/components';

export interface CommandDefinition extends Named {
  /**
   * The name of the command
   */
  name: string;
  /**
   * The HTTP method for the command
   */
  method: HTTPMethod;
  /**
   * The endpoint path for the command
   */
  path: string;
  /**
   * The path parameters for the command
   */
  pathParameters: Parameter[];
  summary?: string;
  description?: string;
  /**
   * The schema for the command body
   */
  schema: KeySchema;
  operation: Operation;
}

export interface EventDefinition extends Named {
  /**
   * The name of the event
   */
  name: string;
  /**
   * The title of the event
   */
  title: string;

  /**
   * The schema for the event body
   */
  schema: KeySchema;
}

export interface TagAliasAggregate extends AliasAggregate {
  tag: Tag;
}

/**
 * Complete definition of an aggregate including its commands, events, and state schemas.
 */
export interface AggregateDefinition {
  /** The aggregate metadata with tag and alias information */
  aggregate: TagAliasAggregate;
  /**
   * The aggregate's route segment, which is its name unless the aggregate
   * sets a resource name: `sales-order` for the aggregate `order`.
   */
  resourceName: string;
  /**
   * The schema for the aggregate root state
   */
  state: KeySchema;
  /**
   * The fields schema for aggregate queries
   */
  fields: KeySchema;
  /**
   * Map of command names to command definitions
   */
  commands: Map<string, CommandDefinition>;
  /**
   * Map of event names to event definitions
   */
  events: Map<string, EventDefinition>;
}

/**
 * Map of context aliases to sets of aggregate definitions
 */
export type BoundedContextAggregates = Map<string, Set<AggregateDefinition>>;

/**
 * The doc comment the Wow metadata lends a schema: a command body takes its
 * operation's summary and description where it has none of its own, an event
 * body the title of its domain event. It holds every field the metadata
 * sets, in the order it sets them, so a model's doc reads the schema with
 * these over it the way it read the document the old resolver changed in
 * place: a field the schema lacks comes last, and an `undefined` one is left
 * out. The document itself is never changed.
 */
export type SchemaDocOverride = Readonly<
  Partial<Record<'title' | 'description', string | undefined>>
>;

/** What the Wow metadata of a document says, read without changing it. */
export interface WowModel {
  /** The bounded context the document names (`info.x-wow-context-alias`). */
  readonly contextAlias?: string;
  /** The aggregates that have state and query fields, by context alias. */
  readonly contexts: BoundedContextAggregates;
  /**
   * The tags of every aggregate that exposes a Wow route, resolved or not.
   * Their operations belong to command and query clients, not to API clients.
   */
  readonly aggregateTags: ReadonlySet<string>;
  /** The doc comments the metadata lends schemas, by component key. */
  readonly schemaDocOverrides: ReadonlyMap<string, SchemaDocOverride>;
  /** Aggregates and commands skipped for missing metadata, a warning each. */
  readonly warnings: readonly string[];
}

/**
 * Tells whether the document comes from a Wow service: it names its bounded
 * context, or it has aggregates.
 */
export function isWowDocument(
  wow: Pick<WowModel, 'contextAlias' | 'aggregateTags'>,
): boolean {
  return wow.contextAlias !== undefined || wow.aggregateTags.size > 0;
}

/**
 * A schema as a model's doc reads it: the schema with the doc comment the
 * Wow metadata lends it over it.
 *
 * @param schema - The schema, left unchanged
 * @param override - What the metadata lends it, if anything
 */
export function withDocOverride<T extends object>(
  schema: T,
  override: SchemaDocOverride | undefined,
): T {
  return override ? { ...schema, ...override } : schema;
}
