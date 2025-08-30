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

import { describe, expect, it } from 'vitest';
import {
  DomainEvent,
  DomainEventStream,
  DomainEventStreamMetadataFields,
} from '../../../src';

interface TestEventBody {
  message: string;
  count: number;
}

describe('DomainEvent', () => {
  it('should create a domain event with required properties', () => {
    const event: DomainEvent<TestEventBody> = {
      id: 'event-id-1',
      name: 'TestEvent',
      body: {
        message: 'test message',
        count: 42,
      },
      bodyType: 'TestEventBody',
      revision: '1.0',
    };

    expect(event).toBeDefined();
    expect(event.id).toBe('event-id-1');
    expect(event.name).toBe('TestEvent');
    expect(event.body.message).toBe('test message');
    expect(event.body.count).toBe(42);
    expect(event.bodyType).toBe('TestEventBody');
    expect(event.revision).toBe('1.0');
  });
});

describe('DomainEventStream', () => {
  it('should create a domain event stream with required properties', () => {
    const events: DomainEvent<TestEventBody>[] = [
      {
        id: 'event-id-1',
        name: 'TestEvent',
        body: {
          message: 'test message 1',
          count: 1,
        },
        bodyType: 'TestEventBody',
        revision: '1.0',
      },
      {
        id: 'event-id-2',
        name: 'TestEvent',
        body: {
          message: 'test message 2',
          count: 2,
        },
        bodyType: 'TestEventBody',
        revision: '1.0',
      },
    ];

    const stream: DomainEventStream = {
      id: 'stream-id-1',
      contextName: 'context-name-1',
      aggregateName: 'aggregate-name-1',
      aggregateId: 'aggregate-id-1',
      tenantId: 'tenant-id-1',
      ownerId: 'owner-id-1',
      commandId: 'command-id-1',
      createTime: Date.now(),
      requestId: 'request-id-1',
      version: 1,
      body: events,
      headers: {
        remote_ip: '127.0.0.1',
      },
    };

    expect(stream).toBeDefined();
    expect(stream.id).toBe('stream-id-1');
    expect(stream.contextName).toBe('context-name-1');
    expect(stream.aggregateName).toBe('aggregate-name-1');
    expect(stream.aggregateId).toBe('aggregate-id-1');
    expect(stream.tenantId).toBe('tenant-id-1');
    expect(stream.ownerId).toBe('owner-id-1');
    expect(stream.commandId).toBe('command-id-1');
    expect(stream.requestId).toBe('request-id-1');
    expect(stream.version).toBe(1);
    expect(stream.body).toHaveLength(2);
    expect(stream.headers.remote_ip).toBe('127.0.0.1');
  });
});

describe('DomainEventStreamMetadataFields', () => {
  it('should have correct field values', () => {
    expect(DomainEventStreamMetadataFields.HEADERS).toBe('headers');
    expect(DomainEventStreamMetadataFields.COMMAND_OPERATOR).toBe('headers.command_operator');
    expect(DomainEventStreamMetadataFields.AGGREGATE_ID).toBe('aggregateId');
    expect(DomainEventStreamMetadataFields.TENANT_ID).toBe('tenantId');
    expect(DomainEventStreamMetadataFields.OWNER_ID).toBe('ownerId');
    expect(DomainEventStreamMetadataFields.COMMAND_ID).toBe('commandId');
    expect(DomainEventStreamMetadataFields.REQUEST_ID).toBe('requestId');
    expect(DomainEventStreamMetadataFields.VERSION).toBe('version');
    expect(DomainEventStreamMetadataFields.BODY).toBe('body');
    expect(DomainEventStreamMetadataFields.BODY_ID).toBe('body.id');
    expect(DomainEventStreamMetadataFields.BODY_NAME).toBe('body.name');
    expect(DomainEventStreamMetadataFields.BODY_TYPE).toBe('body.bodyType');
    expect(DomainEventStreamMetadataFields.BODY_REVISION).toBe('body.revision');
    expect(DomainEventStreamMetadataFields.BODY_BODY).toBe('body.body');
    expect(DomainEventStreamMetadataFields.CREATE_TIME).toBe('createTime');
  });
});