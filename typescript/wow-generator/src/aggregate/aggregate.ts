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
import { KeySchema } from '@/utils';

export interface CommandDefinition extends Named {
  /**
   * command name
   */
  name: string;
  /**
   * command http method
   */
  method: HTTPMethod;
  /**
   * command endpoint path
   */
  path: string;
  /**
   * command path parameters
   */
  pathParameters: Parameter[];
  summary?: string;
  description?: string;
  /**
   * command body schema
   */
  schema: KeySchema;
  operation: Operation;
}

export interface EventDefinition extends Named {
  /**
   * event name
   */
  name: string;
  /**
   * event title
   */
  title: string;

  /**
   * event body schema
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
   * State Aggregate Root Schema
   */
  state: KeySchema;
  /**
   * state aggregate fields for query
   */
  fields: KeySchema;
  /**
   * command name -> command definition
   */
  commands: Map<string, CommandDefinition>;
  /**
   * event name -> event schema
   */
  events: Map<string, EventDefinition>;
}

/**
 * context alias -> Set<AggregateDefinition>
 */
export type BoundedContextAggregates = Map<string, Set<AggregateDefinition>>;
