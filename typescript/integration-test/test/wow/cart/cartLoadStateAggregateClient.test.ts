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
import { HttpMethod } from '@ahoo-wang/fetcher';
import '@ahoo-wang/fetcher-eventstream';
import {
  CommandHeaders,
  CommandStage,
  ErrorCodes,
} from '@ahoo-wang/fetcher-wow';
import {
  AddCartItemCommand,
  cartCommandClient,
  CartCommandEndpoints,
  exampleFetcher,
} from '../../../src/wow';
import { cartQueryClientFactory, CartState } from '../../../src/generated';

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

function expectCartState(cartState: Partial<CartState> | undefined) {
  expect(cartState?.id).toBeDefined();
  expect(cartState?.items).toBeDefined();
}

const cartLoadStateAggregateClient =
  cartQueryClientFactory.createOwnerLoadStateAggregateClient({
    contextAlias: '',
    fetcher: exampleFetcher,
  });

describe('cartLoadStateAggregateClient Integration Test', () => {
  it('should load', async () => {
    const cartState = await cartLoadStateAggregateClient.load();
    expectCartState(cartState);
  });
  it('should loadVersioned', async () => {
    const cartState = await cartLoadStateAggregateClient.loadVersioned(
      commandResult.aggregateVersion!,
    );
    expectCartState(cartState);
  });
  it('should loadTimeBased', async () => {
    const cartState = await cartLoadStateAggregateClient.loadTimeBased(
      commandResult.signalTime,
    );
    expectCartState(cartState);
  });
});
