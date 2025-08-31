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
import { Fetcher, HttpMethod } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandHeaders,
  CommandHttpClient,
  CommandHttpRequest,
  CommandResult,
  CommandStage,
  ErrorCodes,
} from '@ahoo-wang/fetcher-wow';

const wowFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

const commandHttpClient = new CommandHttpClient(wowFetcher);

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

describe('CommandHttpClient Integration Test', () => {
  const command: CommandHttpRequest = {
    path: 'owner/{ownerId}/cart/add_cart_item',
    method: HttpMethod.POST,
    headers: {
      [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
    },
    urlParams: {
      path: {
        ownerId: 'ownerId',
      },
    },
    body: {
      productId: 'productId',
      quantity: 1,
    },
  };

  it('should send command', async () => {
    const commandResult = await commandHttpClient.send(command);
    expectCommandResultToBeDefined(commandResult);
    expect(commandResult.aggregateId).toBe(command.urlParams?.path?.ownerId);
    expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);
    expect(commandResult.stage).toBe(CommandStage.SNAPSHOT);
  });

  it('should send command and wait stream', async () => {
    const commandResultStream =
      await commandHttpClient.sendAndWaitStream(command);
    expect(commandResultStream).toBeDefined();
    for await (const commandResultEvent of commandResultStream) {
      console.info(`Received : ${JSON.stringify(commandResultEvent)}`);
      expectCommandResultToBeDefined(commandResultEvent.data);
    }
  });
});
