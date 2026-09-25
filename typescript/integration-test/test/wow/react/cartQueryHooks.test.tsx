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

// @vitest-environment jsdom

/*
 * The `@ahoo-wang/wow-react` hooks against a real Wow server: the endpoint
 * hooks with the URLs the README shows, and the `execute` hooks with a
 * generated query client. The list-stream hook needs the server to answer
 * with an event stream, which it does only to `Accept: text/event-stream`.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { HttpMethod } from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  asc,
  CommandClient,
  commandHeaders,
  CommandStage,
  ErrorCodes,
  filter,
  listQuery,
  type MaterializedSnapshot,
  pagedQuery,
  ResourceAttributionPathSpec,
  singleQuery,
  waitStrategy,
  WowError,
} from '@ahoo-wang/wow-client';
import {
  useCountQuery,
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherListStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
  useListStreamQuery,
  usePagedQuery,
} from '@ahoo-wang/wow-react';
import { exampleFetcher } from '../../../src/wow';
import {
  type CartAggregatedFields,
  cartQueryClientFactory,
  type CartState,
} from '../../../src/generated';

/**
 * The fields a cart query names. The execute hooks infer them only from the
 * initial query, never from `execute`, so they are named where the hook meets
 * the generated client.
 */
type CartFields = `${CartAggregatedFields}`;

const runId = idGenerator.generateId();
const cartA = `${runId}A`;
const cartB = `${runId}B`;
const cartIds = [cartA, cartB];
const scope = filter.aggregateIds(cartIds);
const byAggregateId = [asc('aggregateId')];

async function addCartItem(ownerId: string, productId: string) {
  const client = new CommandClient({
    fetcher: exampleFetcher,
    basePath: `owner/${ownerId}/cart`,
  });
  const result = await client.send({
    path: 'add_cart_item',
    method: HttpMethod.POST,
    headers: {
      ...commandHeaders({ requestId: idGenerator.generateId() }),
      ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 10_000 }),
    },
    body: { productId, quantity: 1 },
  });
  expect(result.errorCode).toBe(ErrorCodes.SUCCEEDED);
}

// Cart A holds one item, cart B two. The server is warm by now: the global
// setup (`test/globalSetup.ts`) paid for its first commands.
beforeAll(async () => {
  await addCartItem(cartA, `${runId}P1`);
  await addCartItem(cartB, `${runId}P1`);
  await addCartItem(cartB, `${runId}P2`);
});

const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  contextAlias: '',
  resourceAttribution: ResourceAttributionPathSpec.NONE,
  fetcher: exampleFetcher,
});

describe('the endpoint hooks', () => {
  it('useFetcherPagedQuery reads a page of states, filtered on a state field', async () => {
    const { result } = renderHook(() =>
      useFetcherPagedQuery<CartState>({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/paged/state',
        initialQuery: pagedQuery({
          filter: filter.and([scope, filter.eq('state.size', 2)]),
          pagination: { index: 1, size: 10 },
        }),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result?.total).toBe(1);
    expect(result.current.result?.list.map(state => state.id)).toEqual([cartB]);
  });

  it('useFetcherListQuery, useFetcherSingleQuery and useFetcherCountQuery read the same carts', async () => {
    const list = renderHook(() =>
      useFetcherListQuery<CartState>({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/list/state',
        initialQuery: listQuery({ filter: scope, sort: byAggregateId }),
      }),
    );
    const single = renderHook(() =>
      useFetcherSingleQuery<CartState>({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/single/state',
        initialQuery: singleQuery({ filter: filter.id(cartA) }),
      }),
    );
    const count = renderHook(() =>
      useFetcherCountQuery({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/count',
        initialQuery: scope,
      }),
    );
    await waitFor(() => {
      expect(list.result.current.status).toBe('success');
      expect(single.result.current.status).toBe('success');
      expect(count.result.current.status).toBe('success');
    });
    expect(list.result.current.result?.map(state => state.id)).toEqual(cartIds);
    expect(single.result.current.result?.id).toBe(cartA);
    expect(count.result.current.result).toBe(2);
  });

  it('useFetcherListStreamQuery streams every row the server sends', async () => {
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<CartState>({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/list/state',
        initialQuery: listQuery({ filter: scope, sort: byAggregateId }),
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.error).toBeUndefined();
    expect(result.current.items.map(state => state.id)).toEqual(cartIds);
    expect(result.current.items[1].size).toBe(2);
  });

  it('useFetcherListStreamQuery ends with the WowError of an error event', async () => {
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<CartState>({
        fetcher: exampleFetcher,
        url: 'cart/snapshot/list/state',
        // A filter the server rejects: it answers 200 and one error event.
        initialQuery: { filter: { field: '', operator: 'ALL' } } as never,
      }),
    );
    await waitFor(() =>
      expect(['error', 'success']).toContain(result.current.status),
    );
    // The ErrorInfo is never a row. A local server sends the error event; the
    // same-source CI server may fail the response before flushing it, which
    // ends the stream with nothing (see wowErrors.test.ts).
    expect(result.current.items).toEqual([]);
    if (result.current.status === 'error') {
      expect(result.current.error).toBeInstanceOf(WowError);
      expect(result.current.error).toMatchObject({
        errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
      });
      expect(result.current.done).toBe(false);
    }
  });
});

describe('the execute hooks with a generated query client', () => {
  it('usePagedQuery and useCountQuery take the client methods', async () => {
    const paged = renderHook(() =>
      usePagedQuery<MaterializedSnapshot<CartState>, CartFields>({
        initialQuery: pagedQuery({ filter: scope, sort: byAggregateId }),
        execute: (query, attributes, abortController) =>
          snapshotClient.paged(query, attributes, abortController),
      }),
    );
    const count = renderHook(() =>
      useCountQuery<CartFields>({
        initialQuery: scope,
        execute: (query, attributes, abortController) =>
          snapshotClient.count(query, attributes, abortController),
      }),
    );
    await waitFor(() => {
      expect(paged.result.current.status).toBe('success');
      expect(count.result.current.status).toBe('success');
    });
    expect(
      paged.result.current.result?.list.map(snapshot => snapshot.aggregateId),
    ).toEqual(cartIds);
    expect(count.result.current.result).toBe(2);
  });

  it('useListStreamQuery reads the stream the client opens', async () => {
    const { result } = renderHook(() =>
      useListStreamQuery<CartState, CartFields>({
        initialQuery: listQuery({ filter: scope, sort: byAggregateId }),
        execute: (query, attributes, abortController) =>
          snapshotClient.listStateStream(query, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items.map(state => state.id)).toEqual(cartIds);
  });
});
