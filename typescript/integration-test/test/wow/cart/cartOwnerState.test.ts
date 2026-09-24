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

import { beforeAll, describe, expect, it } from 'vitest';
import { HttpMethod } from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  type CommandResult,
  commandHeaders,
  CommandStage,
  ErrorCodes,
  filter,
  listQuery,
  singleQuery,
  toWowError,
  waitStrategy,
} from '@ahoo-wang/wow-client';
import {
  type AddCartItemCommand,
  cartCommandClient,
  CartCommandEndpoints,
  currentUserId,
  exampleFetcher,
} from '../../../src/wow';
import { cartQueryClientFactory } from '../../../src/generated';

/*
 * The owner-scoped routes (`/owner/{ownerId}/cart/...`): `exampleFetcher`
 * fills `{ownerId}` with `currentUserId`, whose cart this file builds.
 */

const productOne = `${idGenerator.generateId()}P1`;
const productTwo = `${idGenerator.generateId()}P2`;

function addCartItem(
  productId: string,
  quantity: number,
): Promise<CommandResult> {
  const command: AddCartItemCommand = {
    path: CartCommandEndpoints.addCartItem,
    method: HttpMethod.POST,
    headers: {
      ...commandHeaders({ requestId: idGenerator.generateId() }),
      ...waitStrategy({ stage: CommandStage.SNAPSHOT }),
    },
    body: { productId, quantity },
  };
  return cartCommandClient.send(command);
}

let first: CommandResult;
let second: CommandResult;

beforeAll(async () => {
  first = await addCartItem(productOne, 1);
  expect(first.errorCode).toBe(ErrorCodes.SUCCEEDED);
  expect(first.aggregateVersion).toBe(1);
  second = await addCartItem(productTwo, 2);
  expect(second.errorCode).toBe(ErrorCodes.SUCCEEDED);
  expect(second.aggregateVersion).toBe(2);
});

const ownerStateClient =
  cartQueryClientFactory.createLoadOwnerStateAggregateClient({
    contextAlias: '',
    fetcher: exampleFetcher,
  });

const ownerSnapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  contextAlias: '',
  fetcher: exampleFetcher,
});

describe('LoadOwnerStateAggregateClient', () => {
  it('should load the latest state', async () => {
    const state = await ownerStateClient.load();
    expect(state.id).toBe(currentUserId);
    expect(state.size).toBe(2);
    expect(state.items).toEqual([
      { productId: productOne, quantity: 1 },
      { productId: productTwo, quantity: 2 },
    ]);
  });

  it('should load a version', async () => {
    const state = await ownerStateClient.loadVersioned(1);
    expect(state.id).toBe(currentUserId);
    expect(state.items).toEqual([{ productId: productOne, quantity: 1 }]);
  });

  it('should load the state at a time', async () => {
    const atFirst = await ownerStateClient.loadTimeBased(first.signalTime);
    expect(atFirst.items).toEqual([{ productId: productOne, quantity: 1 }]);
    const atSecond = await ownerStateClient.loadTimeBased(second.signalTime);
    expect(atSecond.size).toBe(2);
  });

  it('should refuse a version the aggregate has not reached', async () => {
    const error = await ownerStateClient.loadVersioned(99).then(
      () => undefined,
      (reason: unknown) => reason,
    );
    const wowError = await toWowError(error);
    expect(wowError?.errorCode).toBe(ErrorCodes.ILLEGAL_STATE);
    expect(wowError?.status).toBe(400);
  });
});

describe('owner-scoped snapshot query through filter.*', () => {
  it('should see only the owner cart', async () => {
    expect(await ownerSnapshotClient.count(filter.matchAll())).toBe(1);
    const state = await ownerSnapshotClient.singleState(singleQuery());
    expect(state.id).toBe(currentUserId);
    expect(state.size).toBe(2);
  });

  it('should narrow the owner cart with a filter', async () => {
    const list = await ownerSnapshotClient.list(
      listQuery({ filter: filter.eq('state.size', 3) }),
    );
    expect(list).toEqual([]);
    const snapshot = await ownerSnapshotClient.getById(currentUserId);
    expect(snapshot.ownerId).toBe(currentUserId);
    expect(snapshot.version).toBe(2);
  });
});
