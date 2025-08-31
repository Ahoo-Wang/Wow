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


import { Fetcher, FetchExchange, HttpMethod, URL_RESOLVE_INTERCEPTOR_ORDER } from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  all,
  CommandHttpHeaders,
  CommandClient,
  CommandRequest,
  CommandStage, ErrorCodes, ListQuery, PagedQuery, EventStreamQueryClient, DomainEventStream, ClientOptions,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';

const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});
const ownerId = idGenerator.generateId();
wowFetcher.interceptors.request.use({
  name: 'AppendOwnerId',
  order: URL_RESOLVE_INTERCEPTOR_ORDER - 1,
  intercept(exchange: FetchExchange) {
    exchange.request.urlParams = {
      path: {
        ...exchange.request.urlParams?.path,
        ownerId,
      },
      query: exchange.request.urlParams?.query,
    };
  },
});
const aggregateBasePath = 'owner/{ownerId}/cart';
const cartClientOptions: ClientOptions = {
  fetcher: wowFetcher,
  basePath: aggregateBasePath,
};

const commandHttpClient = new CommandClient(cartClientOptions);
const cartQueryClient = new EventStreamQueryClient({
  fetcher: wowFetcher,
  basePath: aggregateBasePath,
});
const command: CommandRequest = {
  method: HttpMethod.POST,
  headers: {
    [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'productId',
    quantity: 1,
  },
};
const commandResult = await commandHttpClient.send('add_cart_item', command);
expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);

function expectDomainEventStreamToBeDefined(domainEventStream: Partial<DomainEventStream>) {
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

describe('EventStreamQueryClient Integration Test', () => {

  it('should count', async () => {
    const count = await cartQueryClient.count(all());
    expect(count).greaterThanOrEqual(1);
  });

  it('should list', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const list = await cartQueryClient.list(listQuery);
    for (const domainEventStream of list) {
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });

  it('should list stream', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const listStream = await cartQueryClient.listStream(listQuery);
    for await (const event of listStream) {
      const domainEventStream = event.data;
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });


  it('should paged', async () => {
    const pagedQuery: PagedQuery = {
      condition: all(),
    };
    const paged = await cartQueryClient.paged(pagedQuery);
    expect(paged.total).greaterThanOrEqual(1);
    expect(paged.list.length).greaterThanOrEqual(1);
    for (const domainEventStream of paged.list) {
      expectDomainEventStreamToBeDefined(domainEventStream);
    }
  });

});