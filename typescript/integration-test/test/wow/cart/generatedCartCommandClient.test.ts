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
import { AddCartItemCommand, currentUserId, exampleFetcher } from '../../../src/wow';
import { CommandHeaders, CommandStage, ErrorCodes } from '@ahoo-wang/fetcher-wow';
import { CartCommandClient, CartStreamCommandClient } from '../../../src/generated';
import { expectCommandResultToBeDefined } from './cartCommandClient.test';

describe('CartCommandClient Integration Test', () => {
  const addCartItemCommand: AddCartItemCommand = {
    headers: {
      [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
    },
    body: {
      productId: 'productId',
      quantity: 1,
    },
  };

  it('should send command', async () => {
    const cartCommandClient = new CartCommandClient({ fetcher: exampleFetcher });
    const commandResult = await cartCommandClient.addCartItem(addCartItemCommand);
    expectCommandResultToBeDefined(commandResult);
    expect(commandResult.aggregateId).toBe(currentUserId);
    expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);
    expect(commandResult.stage).toBe(CommandStage.SNAPSHOT);
  });

  it('should send command and wait stream', async () => {
    const cartCommandClient = new CartStreamCommandClient({ fetcher: exampleFetcher });
    const commandResultStream =
      await cartCommandClient.addCartItem(addCartItemCommand);
    expect(commandResultStream).toBeDefined();
    for await (const commandResultEvent of commandResultStream) {
      console.info(`Received : ${JSON.stringify(commandResultEvent)}`);
      expectCommandResultToBeDefined(commandResultEvent.data);
    }
  });
});