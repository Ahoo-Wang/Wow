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

/**
 * The `useFetcher*Query` hooks through a real Fetcher against a fake `fetch`.
 */

import { StrictMode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { fetcherRegistrar } from '@ahoo-wang/fetcher';
import { WowProblemError } from '@ahoo-wang/wow-client';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
} from '@ahoo-wang/wow-client/dsl';
import {
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
} from '../src';
import {
  BASE_URL,
  deferred,
  fakeServer,
  json,
  problem,
} from './support/fakeServer';

interface OrderState {
  id: string;
  status: string;
}

const paid: OrderState = { id: 'o1', status: 'PAID' };
const shipped: OrderState = { id: 'o2', status: 'SHIPPED' };
const paidFilter = filter.eq('state.status', 'PAID');
const paidCount = { filter: paidFilter };

/** The answer of a count of `count` matches. */
function counted(count: number): Response {
  return json({ count, exact: true });
}

describe('each fetcher query hook', () => {
  it('useFetcherSingleQuery POSTs the query to the route and keeps the item', async () => {
    const server = fakeServer(() => json(paid));
    const { result } = renderHook(() =>
      useFetcherSingleQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: singleQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual(paid));
    expect(result.current.status).toBe('success');
    expect(server.requests).toHaveLength(1);
    const [request] = server.requests;
    expect(request.method).toBe('POST');
    expect(request.url).toBe(`${BASE_URL}shop/order/snapshot/query`);
    expect(request.headers.get('Content-Type')).toBe('application/json');
    expect(request.body).toEqual({ filter: paidFilter, kind: 'single' });
  });

  it('useFetcherListQuery keeps the rows', async () => {
    const server = fakeServer(() => json([paid, shipped]));
    const { result } = renderHook(() =>
      useFetcherListQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: listQuery({ filter: filter.matchAll(), limit: 2 }),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual([paid, shipped]));
    expect(server.requests[0].path).toBe('/shop/order/snapshot/query');
  });

  it('useFetcherPagedQuery keeps the page', async () => {
    const page = { total: 1, list: [paid] };
    const server = fakeServer(() => json(page));
    const { result } = renderHook(() =>
      useFetcherPagedQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: pagedQuery({
          filter: paidFilter,
          pagination: { index: 1, size: 20 },
        }),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual(page));
    expect(server.requests[0].body).toEqual({
      filter: paidFilter,
      pagination: { index: 1, size: 20 },
      kind: 'paged',
    });
  });

  it('useFetcherCountQuery keeps the count', async () => {
    const server = fakeServer(() => counted(3));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: paidCount,
      }),
    );
    await waitFor(() => expect(result.current.result?.count).toBe(3));
    expect(server.requests[0].body).toEqual({ ...paidCount, kind: 'count' });
  });
});

describe('which Fetcher sends the request', () => {
  const NAME = 'wow-react-test';
  afterEach(() => {
    fetcherRegistrar.unregister(NAME);
  });

  it('takes the name of a registered Fetcher', async () => {
    const server = fakeServer(() => counted(5));
    fetcherRegistrar.register(NAME, server.fetcher);
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: NAME,
        url: '/shop/order/snapshot/query',
        initialQuery: paidCount,
      }),
    );
    await waitFor(() => expect(result.current.result?.count).toBe(5));
    expect(server.requests[0].url).toBe(`${BASE_URL}shop/order/snapshot/query`);
  });

  it('uses the default Fetcher when none is given', async () => {
    const server = fakeServer(() => counted(6));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        url: `${BASE_URL}shop/order/snapshot/query`,
        initialQuery: paidCount,
      }),
    );
    await waitFor(() => expect(result.current.result?.count).toBe(6));
    expect(server.requests).toHaveLength(1);
  });

  // Every URL hook resolves the Fetcher when it sends the request, so a name
  // that is not registered fails that request instead of the render.
  it('ends a request hook in error when its Fetcher name is not registered', async () => {
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: 'not-registered',
        url: '/shop/order/snapshot/query',
        initialQuery: paidCount,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error?.message).toBe(
      'Fetcher not-registered not found',
    );
    expect(result.current.result).toBeUndefined();
  });

  it('ends the stream hook in error when its Fetcher name is not registered', async () => {
    const { result } = renderHook(() =>
      useFetcherStreamQuery<OrderState>({
        fetcher: 'not-registered',
        url: '/shop/order/snapshot/query',
        initialQuery: listQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error?.message).toBe(
      'Fetcher not-registered not found',
    );
    expect(result.current.items).toEqual([]);
  });
});

describe('errors, races, aborts and StrictMode', () => {
  it('sets the problem of a failed request as the error', async () => {
    const server = fakeServer(() =>
      problem('wow.query.dataset-not-found', 'No dataset.', 404),
    );
    const { result } = renderHook(() =>
      useFetcherSingleQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: singleQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBeInstanceOf(WowProblemError);
    expect(result.current.result).toBeUndefined();
    expect(result.current.error).toMatchObject({
      code: 'wow.query.dataset-not-found',
      status: 404,
    });
  });

  it('answers a single query that matches nothing with no result, not an error', async () => {
    const server = fakeServer(() =>
      problem('wow.query.not-found', 'No order.', 404),
    );
    const { result } = renderHook(() =>
      useFetcherSingleQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: singleQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result).toBeUndefined();
    expect(result.current.error).toBeUndefined();
  });

  it('recovers from an error on the next success', async () => {
    const server = fakeServer(() => problem('wow.query.invalid', 'bad'));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: paidCount,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    server.handle(() => counted(2));
    await act(() => result.current.execute());
    expect(result.current.status).toBe('success');
    expect(result.current.error).toBeUndefined();
    expect(result.current.result?.count).toBe(2);
  });

  it('keeps the newer page when an older response arrives late', async () => {
    const first = deferred<Response>();
    const server = fakeServer(() => first.promise);
    const { result } = renderHook(() =>
      useFetcherPagedQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: pagedQuery({ pagination: { index: 1, size: 1 } }),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    server.handle(() => json({ total: 2, list: [shipped] }));
    act(() =>
      result.current.setQuery(
        pagedQuery({ pagination: { index: 2, size: 1 } }),
      ),
    );
    await waitFor(() =>
      expect(result.current.result).toEqual({ total: 2, list: [shipped] }),
    );
    expect(server.requests[0].signal?.aborted).toBe(true);
    first.resolve(json({ total: 2, list: [paid] }));
    await act(() => new Promise(resolve => setTimeout(resolve, 10)));
    expect(result.current.result).toEqual({ total: 2, list: [shipped] });
  });

  it('aborts the request in flight on unmount', async () => {
    const server = fakeServer(() => deferred<Response>().promise);
    const { unmount } = renderHook(() =>
      useFetcherListQuery<OrderState>({
        fetcher: server.fetcher,
        url: '/shop/order/snapshot/query',
        initialQuery: listQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    unmount();
    expect(server.requests[0].signal?.aborted).toBe(true);
  });

  it('settles on one result under StrictMode', async () => {
    const server = fakeServer(() => counted(4));
    const { result } = renderHook(
      () =>
        useFetcherCountQuery({
          fetcher: server.fetcher,
          url: '/shop/order/snapshot/query',
          initialQuery: paidCount,
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.result?.count).toBe(4));
    const live = server.requests.filter(request => !request.signal?.aborted);
    expect(live).toHaveLength(1);
  });
});
