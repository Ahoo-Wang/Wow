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
  Fetcher,
  FetchExchange,
  HttpMethod,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  ClientOptions,
  CommandHttpHeaders,
  CommandClient,
  CommandRequest,
  CommandResult,
  CommandStage,
  ErrorCodes,
} from '@ahoo-wang/fetcher-wow';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

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

function expectCommandResultToBeDefined(commandResult: CommandResult) {
  expect(commandResult.id).toBeDefined();
  expect(commandResult.waitCommandId).toBeDefined();
  expect(commandResult.stage).toBeDefined();
  expect(commandResult.contextName).toBeDefined();
  expect(commandResult.aggregateName).toBeDefined();
  expect(commandResult.tenantId).toBeDefined();
  expect(commandResult.aggregateId).toBeDefined();
  expect(commandResult.aggregateVersion).toBeDefined();
  expect(commandResult.requestId).toBeDefined();
  expect(commandResult.commandId).toBeDefined();
  expect(commandResult.function).toBeDefined();
  expect(commandResult.errorCode).toBeDefined();
  expect(commandResult.errorMsg).toBeDefined();
  expect(commandResult.result).toBeDefined();
  expect(commandResult.signalTime).toBeDefined();
}

interface AddCartItem {
  productId: string;
  quantity: number;
}

describe('CommandHttpClient Integration Test', () => {
  const command: CommandRequest<AddCartItem> = {
    method: HttpMethod.POST,
    headers: {
      [CommandHttpHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
    },
    body: {
      productId: 'productId',
      quantity: 1,
    },
  };

  it('should send command', async () => {
    const commandResult = await commandHttpClient.send(
      'add_cart_item',
      command,
    );
    expectCommandResultToBeDefined(commandResult);
    expect(commandResult.aggregateId).toBe(ownerId);
    expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);
    expect(commandResult.stage).toBe(CommandStage.SNAPSHOT);
  });

  it('should send command and wait stream', async () => {
    const commandResultStream = await commandHttpClient.sendAndWaitStream(
      'add_cart_item',
      command,
    );
    expect(commandResultStream).toBeDefined();
    for await (const commandResultEvent of commandResultStream) {
      console.info(`Received : ${JSON.stringify(commandResultEvent)}`);
      expectCommandResultToBeDefined(commandResultEvent.data);
    }
  });
});
