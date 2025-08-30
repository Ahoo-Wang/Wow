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
  AggregateId,
  CreateTimeCapable,
  Identifier,
  Named,
  OwnerId,
  Version,
} from '../../types';
import { CommandId, CommandStage, RequestId } from '../../command';
import { BodyCapable } from '../../types';
import { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * A list of domain events for the domain event stream
 */
export interface DomainEvent<BODY>
  extends Identifier,
    Named,
    BodyCapable<BODY> {
  bodyType: string;
  revision: string;
}

export interface DomainEventStreamHeaders {
  command_operator?: string;
  command_wait_endpoint?: string;
  command_wait_stage?: CommandStage;
  local_first?: string;
  remote_ip: string;
  user_agent?: string;
  trace_id?: string;

  [key: string]: string | undefined;
}

export interface DomainEventStream
  extends Identifier,
    AggregateId,
    OwnerId,
    CommandId,
    CreateTimeCapable,
    RequestId,
    Version,
    BodyCapable<DomainEvent<any>[]> {
  headers: DomainEventStreamHeaders;
}

/**
 * Provides field names for domain event stream metadata.
 *
 * This class contains static readonly properties that define the field names used in domain event stream metadata.
 * These field names are used to access and manipulate domain event stream data in a consistent manner.
 * The fields include headers, identifiers, command information, versioning, body content, and creation time.
 */
export class DomainEventStreamMetadataFields {
  static readonly HEADERS = 'headers';
  static readonly COMMAND_OPERATOR = `${DomainEventStreamMetadataFields.HEADERS}.command_operator`;
  static readonly AGGREGATE_ID = 'aggregateId';
  static readonly TENANT_ID = 'tenantId';
  static readonly OWNER_ID = 'ownerId';
  static readonly COMMAND_ID = 'commandId';
  static readonly REQUEST_ID = 'requestId';
  static readonly VERSION = 'version';
  static readonly BODY = 'body';
  static readonly BODY_ID = `${DomainEventStreamMetadataFields.BODY}.id`;
  static readonly BODY_NAME = `${DomainEventStreamMetadataFields.BODY}.name`;
  static readonly BODY_TYPE = `${DomainEventStreamMetadataFields.BODY}.bodyType`;
  static readonly BODY_REVISION = `${DomainEventStreamMetadataFields.BODY}.revision`;
  static readonly BODY_BODY = `${DomainEventStreamMetadataFields.BODY}.body`;
  static readonly CREATE_TIME = 'createTime';
}

/**
 * Represents a readable stream of domain event streams.
 *
 * This type defines a ReadableStream that emits JsonServerSentEvent objects containing DomainEventStream data.
 * It is used for streaming domain events in a server-sent event format.
 */
export type ReadableDomainEventStream = ReadableStream<
  JsonServerSentEvent<DomainEventStream>
>;