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

import { describe, it, expect } from 'vitest';
import {
  ExchangeError,
  Fetcher,
  FetchExchange,
  HttpMethod,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  all, ClientOptions,
  CommandHeaders,
  CommandHttpClient, CommandHttpRequest, CommandStage, ErrorCodes, id, Identifier, ListQuery,
  MaterializedSnapshot, PagedQuery, SingleQuery,
  SnapshotQueryClient,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

interface CartItem {
  productId: string;
  quantity: number;
}

interface CartState extends Identifier {
  items: CartItem[];
}

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
const commandHttpClient = new CommandHttpClient(cartClientOptions);
const cartQueryClient = new SnapshotQueryClient<CartState>({
  fetcher: wowFetcher,
  basePath: aggregateBasePath,
});
const command: CommandHttpRequest = {
  method: HttpMethod.POST,
  headers: {
    [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
  },
  body: {
    productId: 'productId',
    quantity: 1,
  },
};
const commandResult = await commandHttpClient.send('add_cart_item', command);
expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);

function expectCartState(cartState: Partial<CartState> | undefined) {
  expect(cartState?.id).toBeDefined();
  expect(cartState?.items).toBeDefined();
}

function expectSnapshotToBeDefined(snapshot: Partial<MaterializedSnapshot<CartState>>) {
  expect(snapshot.tenantId).toBeDefined();
  expect(snapshot.aggregateId).toBeDefined();
  expect(snapshot.contextName).toBeDefined();
  expect(snapshot.aggregateName).toBeDefined();
  expect(snapshot.deleted).toBeDefined();
  expect(snapshot.eventId).toBeDefined();
  expect(snapshot.eventTime).toBeDefined();
  expect(snapshot.firstEventTime).toBeDefined();
  expect(snapshot.firstOperator).toBeDefined();
  expect(snapshot.operator).toBeDefined();
  expect(snapshot.ownerId).toBeDefined();
  expect(snapshot.snapshotTime).toBeDefined();
  expect(snapshot.state).toBeDefined();
  expect(snapshot.version).toBeDefined();
  expect(snapshot.operator).toBeDefined();
  expect(snapshot.ownerId).toBe(ownerId);
  expectCartState(snapshot.state);
}

describe('SnapshotQueryClient Integration Test', () => {

  it('should count', async () => {
    const count = await cartQueryClient.count(all());
    expect(count).greaterThanOrEqual(1);
  });

  it('should list', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const list = await cartQueryClient.list(listQuery);
    for (const snapshot of list) {
      expectSnapshotToBeDefined(snapshot);
    }
  });

  it('should list stream', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const listStream = await cartQueryClient.listStream(listQuery);
    for await (const event of listStream) {
      const snapshot = event.data;
      expectSnapshotToBeDefined(snapshot);
    }
  });
  it('should list state', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const list = await cartQueryClient.listState(listQuery);
    for (const state of list) {
      expectCartState(state);
    }
  });

  it('should list state stream', async () => {
    const listQuery: ListQuery = {
      condition: all(),
    };
    const listStream = await cartQueryClient.listStateStream(listQuery);
    for await (const event of listStream) {
      const state = event.data;
      expectCartState(state);
    }
  });

  it('should paged', async () => {
    const pagedQuery: PagedQuery = {
      condition: all(),
    };
    const paged = await cartQueryClient.paged(pagedQuery);
    expect(paged.total).greaterThanOrEqual(1);
    expect(paged.list.length).greaterThanOrEqual(1);
    for (const snapshot of paged.list) {
      expectSnapshotToBeDefined(snapshot);
    }
  });

  it('should paged state', async () => {
    const pagedQuery: PagedQuery = {
      condition: all(),
    };
    const pagedState = await cartQueryClient.pagedState(pagedQuery);
    for await (const cartState of pagedState.list) {
      expectCartState(cartState);
    }
  });

  it('should single', async () => {
    const singleQuery: SingleQuery = {
      condition: all(),
    };
    const single = await cartQueryClient.single(singleQuery);
    expectSnapshotToBeDefined(single);
  });

  it('should single not found', async () => {
    const singleQuery: SingleQuery = {
      condition: id(idGenerator.generateId()),
    };
    await expect(cartQueryClient.single(singleQuery)).rejects.toThrow(ExchangeError);
  });

  it('should single state', async () => {
    const singleQuery: SingleQuery = {
      condition: all(),
    };
    const singleState = await cartQueryClient.singleState(singleQuery);
    expectCartState(singleState);
  });
});