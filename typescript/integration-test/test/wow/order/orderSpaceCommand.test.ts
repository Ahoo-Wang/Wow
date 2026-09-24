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
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  CommandHeaders,
  CommandStage,
  ErrorCodes,
  filter,
  SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import { exampleFetcher } from '../../../src/wow';
import {
  type CreateOrderCommand,
  OrderCommandClient,
  type WowExampleOrderState,
} from '../../../src/generated';

// The order aggregate is spaced (`@AggregateRoute(spaced = true)`), so the
// server reads the command's space from the `Wow-Space-Id` header.
const createOrder: CreateOrderCommand = {
  items: [{ productId: 'product-1', price: 10, quantity: 2 }],
  address: {
    country: 'China',
    province: 'Shanghai',
    city: 'Shanghai',
    district: 'Pudong',
    detail: 'Road 1',
  },
  fromCart: false,
};

const orderCommandClient = new OrderCommandClient({ fetcher: exampleFetcher });
// exampleFetcher fills `{ownerId}` with the current user, who owns the order.
const orderSnapshotQueryClient = new SnapshotQueryClient<WowExampleOrderState>({
  fetcher: exampleFetcher,
  basePath: 'owner/{ownerId}/sales-order',
});

describe('Space header Integration Test', () => {
  it('should send a command into the space named by CommandHeaders.SPACE_ID', async () => {
    const spaceId = `space-${idGenerator.generateId()}`;
    const commandResult = await orderCommandClient.createOrder({
      urlParams: { path: { tenantId: idGenerator.generateId() } },
      headers: {
        [CommandHeaders.SPACE_ID]: spaceId,
        [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
      },
      body: createOrder,
    });
    expect(commandResult.errorCode).toBe(ErrorCodes.SUCCEEDED);

    const snapshot = await orderSnapshotQueryClient.getById(
      commandResult.aggregateId,
    );
    expect(snapshot.spaceId).toBe(spaceId);
    expect(
      await orderSnapshotQueryClient.count(
        filter.and([
          filter.aggregateId(commandResult.aggregateId),
          filter.spaceId(spaceId),
        ]),
      ),
    ).toBe(1);
  });
});
