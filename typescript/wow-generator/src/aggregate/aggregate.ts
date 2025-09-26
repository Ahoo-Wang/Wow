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

import {
  HTTPMethod,
  Operation,
  Parameter,
  Tag,
} from '@ahoo-wang/fetcher-openapi';
import { AliasAggregate, Named } from '@ahoo-wang/fetcher-wow';
import { KeySchema } from '../utils';

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
