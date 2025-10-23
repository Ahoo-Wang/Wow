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

import { HttpMethod } from '@ahoo-wang/fetcher';
import {
  aggregateId,
  CommandHeaders,
  CommandStage,
  DomainEventStream,
  ErrorCodes,
  ListQuery,
  PagedQuery,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import { AddCartItemCommand, cartCommandClient, CartCommandEndpoints, exampleFetcher, } from '../../../src/wow';
import { cartQueryClientFactory } from '../../../src/generated';

const command: AddCartItemCommand = {
  path: CartCommandEndpoints.addCartItem,
  method: HttpMethod.POST,
  headers: {
    [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'productId',
    quantity: 1,
  },
};
const commandResult = await cartCommandClient.send(command);
expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);

const cartEventStreamQueryClient =
  cartQueryClientFactory.createEventStreamQueryClient({
    contextAlias: '',
    fetcher: exampleFetcher,
  });

function expectDomainEventStreamToBeDefined(
  domainEventStream: Partial<DomainEventStream>,
) {
  expect(domainEventStream.contextName).toBeDefined();
  expect(domainEventStream.aggregateName).toBeDefined();
  expect(domainEventStream.header).toBeDefined();
  expect(domainEventStream.id).toBeDefined();
  expect(domainEventStream.aggregateId).toBeDefined();
  expect(domainEventStream.tenantId).toBeDefined();
  expect(domainEventStream.ownerId).toBeDefined();
  expect(domainEventStream.commandId).toBeDefined();
  expect(domainEventStream.requestId).toBeDefined();
  expect(domainEventStream.version).toBeDefined();
  expect(domainEventStream.body).toBeDefined();
  expect(domainEventStream.createTime).toBeDefined;
  for (const domainEvent of domainEventStream.body!) {
    expect(domainEvent.id).toBeDefined();
    expect(domainEvent.name).toBeDefined();
    expect(domainEvent.revision).toBeDefined();
    expect(domainEvent.bodyType).toBeDefined();
    expect(domainEvent.body).toBeDefined();
  }
}

describe('cartEventStreamQueryClient Integration Test', () => {
  it('should count', async () => {
    const count = await cartEventStreamQueryClient.count(
      aggregateId(commandResult.aggregateId),
    );
    expect(count).greaterThanOrEqual(1);
  });

  it('should list', async () => {
    const listQuery: ListQuery = {
      condition: aggregateId(commandResult.aggregateId),
    };
    const list = await cartEventStreamQueryClient.list(listQuery);
    for (const domainEventStream of list) {
      expect(domainEventStream.aggregateId).toEqual(commandResult.aggregateId);
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });

  it('should list stream', async () => {
    const listQuery: ListQuery = {
      condition: aggregateId(commandResult.aggregateId),
    };
    const listStream = await cartEventStreamQueryClient.listStream(listQuery);
    for await (const event of listStream) {
      const domainEventStream = event.data;
      expect(domainEventStream.aggregateId).toEqual(commandResult.aggregateId);
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });

  it('should paged', async () => {
    const pagedQuery: PagedQuery = {
      condition: aggregateId(commandResult.aggregateId),
    };
    const paged = await cartEventStreamQueryClient.paged(pagedQuery);
    expect(paged.total).greaterThanOrEqual(1);
    expect(paged.list.length).greaterThanOrEqual(1);
    for (const domainEventStream of paged.list) {
      expect(domainEventStream.aggregateId).toEqual(commandResult.aggregateId);
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });
});
