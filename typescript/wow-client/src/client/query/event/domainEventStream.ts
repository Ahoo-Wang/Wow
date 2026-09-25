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
  AggregateId,
  CreateTimeCapable,
  DeletedCapable,
  FirstEventTimeCapable,
  FirstOperatorCapable,
  Identifier,
  Named,
  OwnerId,
  SpaceIdCapable,
  StateCapable,
  Version,
  BodyCapable,
  CommandId,
  CommandStage,
  RequestId,
} from '../../../model/index.js';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Represents a domain event with a specific body type.
 * Extends Identifier, Named, and BodyCapable interfaces to provide identification,
 * naming, and body capabilities for the domain event.
 * @template BODY - The type of the event content for this domain event
 */
export interface DomainEvent<BODY>
  extends Identifier, Named, BodyCapable<BODY> {
  /**
   * The type of the event content.
   */
  bodyType: string;
  /**
   * The revision of the domain event.
   */
  revision: string;
}

/**
 * Represents the header information for a domain event stream.
 * Contains metadata about the event stream such as command information,
 * network details, and tracing information.
 */
export interface DomainEventStreamHeader {
  /**
   * The operator that executed the command.
   */
  command_operator?: string;
  /**
   * The endpoint to wait for command completion.
   */
  command_wait_endpoint?: string;
  /**
   * The stage to wait for in command execution.
   */
  command_wait_stage?: CommandStage;

  local_first?: string;
  /**
   * The IP address of the remote client.
   */
  remote_ip?: string;
  /**
   * The user agent of the client.
   */
  user_agent?: string;
  /**
   * The trace identifier for distributed tracing.
   */
  trace_id?: string;

  /**
   * Index signature for additional custom header properties.
   * Allows for any additional string key-value pairs to be included as header properties.
   */
  [key: string]: string | undefined;
}

/**
 * Represents a stream of domain events.
 * Combines multiple interfaces to provide a complete domain event stream,
 * including identification, aggregation, ownership, command information,
 * versioning, and the actual event data.
 */
export interface DomainEventStream<DomainEventBody = unknown>
  extends
    Identifier,
    AggregateId,
    OwnerId,
    SpaceIdCapable,
    CommandId,
    CreateTimeCapable,
    RequestId,
    Version,
    BodyCapable<DomainEvent<DomainEventBody>[]> {
  /**
   * The header information for the domain event stream.
   */
  header: DomainEventStreamHeader;
}

export interface StateEvent<DomainEventBody = unknown, S = unknown>
  extends
    DomainEventStream<DomainEventBody>,
    StateCapable<S>,
    FirstOperatorCapable,
    FirstEventTimeCapable,
    DeletedCapable {}

/**
 * The field names of a domain event stream, for filters, sorts and
 * projections over event streams (`body.name`, `header.command_operator`, …).
 *
 * A frozen name table: read `DomainEventStreamMetadataFields.BODY_NAME`; each
 * value is a string literal type.
 */
export const DomainEventStreamMetadataFields = Object.freeze({
  HEADER: 'header',
  COMMAND_OPERATOR: 'header.command_operator',
  AGGREGATE_ID: 'aggregateId',
  TENANT_ID: 'tenantId',
  OWNER_ID: 'ownerId',
  SPACE_ID: 'spaceId',
  COMMAND_ID: 'commandId',
  REQUEST_ID: 'requestId',
  VERSION: 'version',
  BODY: 'body',
  BODY_ID: 'body.id',
  BODY_NAME: 'body.name',
  BODY_TYPE: 'body.bodyType',
  BODY_REVISION: 'body.revision',
  BODY_BODY: 'body.body',
  CREATE_TIME: 'createTime',
} as const);

/**
 * Represents a readable stream of domain event streams.
 *
 * This type defines a ReadableStream that emits JsonServerSentEvent objects containing DomainEventStream data.
 * It is used for streaming domain events in a server-sent event format.
 */
export type ReadableDomainEventStream = ReadableStream<
  JsonServerSentEvent<DomainEventStream>
>;
