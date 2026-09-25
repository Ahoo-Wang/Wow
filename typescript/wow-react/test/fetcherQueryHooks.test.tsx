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
import { fetcherRegistrar, ExchangeError } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  toWowError,
} from '@ahoo-wang/wow-client';
import { eq, listQuery as legacyListQuery } from '@ahoo-wang/wow-client/legacy';
import {
  useFetcherCountQuery,
  useFetcherListQuery,
  useFetcherListStreamQuery,
  useFetcherPagedQuery,
  useFetcherSingleQuery,
} from '../src';
import {
  BASE_URL,
  deferred,
  fakeServer,
  json,
  wowError,
} from './support/fakeServer';

interface OrderState {
  id: string;
  status: string;
}

const paid: OrderState = { id: 'o1', status: 'PAID' };
const shipped: OrderState = { id: 'o2', status: 'SHIPPED' };
const paidFilter = filter.eq('state.status', 'PAID');

describe('each fetcher query hook', () => {
  it('useFetcherSingleQuery POSTs the query to the url and keeps the item', async () => {
    const server = fakeServer(() => json(paid));
    const { result } = renderHook(() =>
      useFetcherSingleQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/single/state',
        initialQuery: singleQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual(paid));
    expect(result.current.status).toBe('success');
    expect(server.requests).toHaveLength(1);
    const [request] = server.requests;
    expect(request.method).toBe('POST');
    expect(request.url).toBe(`${BASE_URL}order/snapshot/single/state`);
    expect(request.headers.get('Content-Type')).toBe('application/json');
    expect(request.body).toEqual({ filter: paidFilter });
  });

  it('useFetcherListQuery keeps the rows', async () => {
    const server = fakeServer(() => json([paid, shipped]));
    const { result } = renderHook(() =>
      useFetcherListQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/list/state',
        initialQuery: listQuery({ filter: filter.matchAll(), limit: 2 }),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual([paid, shipped]));
    expect(server.requests[0].path).toBe('/order/snapshot/list/state');
  });

  it('useFetcherPagedQuery keeps the page', async () => {
    const page = { total: 1, list: [paid] };
    const server = fakeServer(() => json(page));
    const { result } = renderHook(() =>
      useFetcherPagedQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/paged/state',
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
    });
  });

  it('useFetcherCountQuery keeps the count', async () => {
    const server = fakeServer(() => json(3));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: server.fetcher,
        url: 'order/snapshot/count',
        initialQuery: paidFilter,
      }),
    );
    await waitFor(() => expect(result.current.result).toBe(3));
    expect(server.requests[0].body).toEqual(paidFilter);
  });

  it('still takes a Condition query (compat)', async () => {
    const server = fakeServer(() => json([]));
    const query = legacyListQuery({ condition: eq('state.status', 'PAID') });
    const { result } = renderHook(() =>
      useFetcherListQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/list/state',
        initialQuery: query,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(server.requests[0].body).toEqual(JSON.parse(JSON.stringify(query)));
  });
});

describe('which Fetcher sends the request', () => {
  const NAME = 'wow-react-test';
  afterEach(() => {
    fetcherRegistrar.unregister(NAME);
  });

  it('takes the name of a registered Fetcher', async () => {
    const server = fakeServer(() => json(5));
    fetcherRegistrar.register(NAME, server.fetcher);
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: NAME,
        url: 'order/snapshot/count',
        initialQuery: paidFilter,
      }),
    );
    await waitFor(() => expect(result.current.result).toBe(5));
    expect(server.requests[0].url).toBe(`${BASE_URL}order/snapshot/count`);
  });

  it('uses the default Fetcher when none is given', async () => {
    const server = fakeServer(() => json(6));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        url: `${BASE_URL}order/snapshot/count`,
        initialQuery: paidFilter,
      }),
    );
    await waitFor(() => expect(result.current.result).toBe(6));
    expect(server.requests).toHaveLength(1);
  });

  // The two URL paths resolve the Fetcher at different times: the request
  // hooks while rendering, the stream hook when the request is sent.
  it('throws while rendering a request hook whose Fetcher name is not registered', () => {
    // B2 changes this: the name is resolved when the request is sent, so the
    // hook renders and ends in `error`, as the stream hook does.
    expect(() =>
      renderHook(() =>
        useFetcherCountQuery({
          fetcher: 'not-registered',
          url: 'order/snapshot/count',
          initialQuery: paidFilter,
        }),
      ),
    ).toThrow('Fetcher not-registered not found');
  });

  it('ends the stream hook in error when its Fetcher name is not registered', async () => {
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: 'not-registered',
        url: 'order/snapshot/list/state',
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
  it('sets a failed request as the error, from which toWowError reads the errorCode', async () => {
    const server = fakeServer(() => wowError('NotFound', 'No order.', 404));
    const { result } = renderHook(() =>
      useFetcherSingleQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/single/state',
        initialQuery: singleQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBeInstanceOf(ExchangeError);
    expect(result.current.result).toBeUndefined();
    const error = await toWowError(result.current.error);
    expect(error).toMatchObject({
      errorCode: 'NotFound',
      errorMsg: 'No order.',
      status: 404,
    });
  });

  it('recovers from an error on the next success', async () => {
    const server = fakeServer(() => wowError('IllegalArgument', 'bad'));
    const { result } = renderHook(() =>
      useFetcherCountQuery({
        fetcher: server.fetcher,
        url: 'order/snapshot/count',
        initialQuery: paidFilter,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    server.handle(() => json(2));
    await act(() => result.current.execute());
    expect(result.current.status).toBe('success');
    expect(result.current.error).toBeUndefined();
    expect(result.current.result).toBe(2);
  });

  it('keeps the newer page when an older response arrives late', async () => {
    const first = deferred<Response>();
    const server = fakeServer(() => first.promise);
    const { result } = renderHook(() =>
      useFetcherPagedQuery<OrderState>({
        fetcher: server.fetcher,
        url: 'order/snapshot/paged/state',
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
        url: 'order/snapshot/list/state',
        initialQuery: listQuery({ filter: paidFilter }),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    unmount();
    expect(server.requests[0].signal?.aborted).toBe(true);
  });

  it('settles on one result under StrictMode', async () => {
    const server = fakeServer(() => json(4));
    const { result } = renderHook(
      () =>
        useFetcherCountQuery({
          fetcher: server.fetcher,
          url: 'order/snapshot/count',
          initialQuery: paidFilter,
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.result).toBe(4));
    const live = server.requests.filter(request => !request.signal?.aborted);
    expect(live).toHaveLength(1);
  });
});
