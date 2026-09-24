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
 * The `use*Query` hooks with a query client as `execute` — the way an
 * application uses them with wow-client or generated clients — against a fake
 * `fetch`. Nothing of the hooks, fetcher-react or the client is mocked.
 */

import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { FetcherError } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  SnapshotQueryClient,
  toWowError,
} from '@ahoo-wang/wow-client';
import {
  eq,
  pagedQuery as legacyPagedQuery,
} from '@ahoo-wang/wow-client/legacy';
import {
  useCountQuery,
  useListQuery,
  usePagedQuery,
  useSingleQuery,
} from '../src';
import {
  deferred,
  fakeServer,
  json,
  wowError,
  type FakeServer,
} from './support/fakeServer';

interface OrderState {
  id: string;
  status: string;
}

const paid: OrderState = { id: 'o1', status: 'PAID' };
const shipped: OrderState = { id: 'o2', status: 'SHIPPED' };
const paidFilter = filter.eq('state.status', 'PAID');

/** A value as the server receives it: JSON drops the `undefined` fields. */
function wire(value: unknown): unknown {
  return JSON.parse(JSON.stringify(value));
}

function orderClient(server: FakeServer) {
  return new SnapshotQueryClient<OrderState>({
    fetcher: server.fetcher,
    basePath: 'order',
  });
}

describe('each query hook with a query client', () => {
  it('useSingleQuery sends the single query and keeps the item', async () => {
    const server = fakeServer(() => json(paid));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useSingleQuery<OrderState>({
        initialQuery: singleQuery({ filter: paidFilter }),
        execute: (query, attributes, abortController) =>
          client.singleState(query, attributes, abortController),
      }),
    );
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result).toEqual(paid);
    expect(result.current.error).toBeUndefined();
    expect(server.requests).toHaveLength(1);
    expect(server.requests[0]).toMatchObject({
      method: 'POST',
      path: '/order/snapshot/single/state',
      body: wire(singleQuery({ filter: paidFilter })),
    });
  });

  it('useListQuery sends the list query and keeps the rows', async () => {
    const server = fakeServer(() => json([paid, shipped]));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useListQuery<OrderState>({
        initialQuery: listQuery({ filter: filter.matchAll(), limit: 2 }),
        execute: (query, attributes, abortController) =>
          client.listState(query, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual([paid, shipped]));
    expect(server.requests[0]).toMatchObject({
      path: '/order/snapshot/list/state',
      body: { limit: 2 },
    });
  });

  it('usePagedQuery sends the paged query and keeps the page', async () => {
    const page = { total: 3, list: [paid] };
    const server = fakeServer(() => json(page));
    const client = orderClient(server);
    const query = pagedQuery({
      filter: paidFilter,
      pagination: { index: 2, size: 1 },
    });
    const { result } = renderHook(() =>
      usePagedQuery<OrderState>({
        initialQuery: query,
        execute: (q, attributes, abortController) =>
          client.pagedState(q, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual(page));
    expect(server.requests[0]).toMatchObject({
      path: '/order/snapshot/paged/state',
      body: wire(query),
    });
  });

  it('useCountQuery sends the filter and keeps the count', async () => {
    const server = fakeServer(() => json(7));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidFilter,
        execute: (query, attributes, abortController) =>
          client.count(query, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.result).toBe(7));
    expect(server.requests[0]).toMatchObject({
      path: '/order/snapshot/count',
      body: wire(paidFilter),
    });
  });

  it('still takes a Condition query (compat)', async () => {
    const server = fakeServer(() => json({ total: 0, list: [] }));
    const client = orderClient(server);
    const query = legacyPagedQuery({ condition: eq('state.status', 'PAID') });
    const { result } = renderHook(() =>
      usePagedQuery<OrderState>({
        initialQuery: query,
        execute: q => client.pagedState(q),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(server.requests[0].body).toEqual(query);
  });
});

describe('query state', () => {
  it('waits for execute() when autoExecute is false', async () => {
    const server = fakeServer(() => json(paid));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useSingleQuery<OrderState>({
        initialQuery: singleQuery({ filter: paidFilter }),
        autoExecute: false,
        execute: q => client.singleState(q),
      }),
    );
    expect(result.current.status).toBe('idle');
    await act(() => Promise.resolve());
    expect(server.requests).toHaveLength(0);
    await act(() => result.current.execute());
    expect(result.current.result).toEqual(paid);
    expect(server.requests).toHaveLength(1);
  });

  it('runs again with the query setQuery() sets', async () => {
    const server = fakeServer(request =>
      json({ total: 1, list: [request.body] }),
    );
    const client = orderClient(server);
    const { result } = renderHook(() =>
      usePagedQuery<unknown>({
        initialQuery: pagedQuery({ pagination: { index: 1, size: 10 } }),
        execute: q => client.pagedState<never>(q),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    const next = pagedQuery({ pagination: { index: 2, size: 10 } });
    act(() => result.current.setQuery(next));
    await waitFor(() => expect(result.current.result?.list[0]).toEqual(next));
    expect(result.current.getQuery()).toEqual(next);
    expect(server.requests).toHaveLength(2);
  });

  it('runs again when the query option changes', async () => {
    const server = fakeServer(request => json(request.body));
    const { result, rerender } = renderHook(
      ({ id }: { id: string }) =>
        useSingleQuery<unknown>({
          query: singleQuery({ filter: filter.id(id) }),
          execute: q => orderClient(server).singleState<never>(q),
        }),
      { initialProps: { id: 'a' } },
    );
    await waitFor(() =>
      expect(result.current.result).toEqual(
        singleQuery({ filter: filter.id('a') }),
      ),
    );
    rerender({ id: 'b' });
    await waitFor(() =>
      expect(result.current.result).toEqual(
        singleQuery({ filter: filter.id('b') }),
      ),
    );
    expect(server.requests).toHaveLength(2);
  });

  it('sets a failed request as the error, from which toWowError reads the errorCode', async () => {
    const server = fakeServer(() =>
      wowError('IllegalArgument', 'Invalid filter request body.'),
    );
    const client = orderClient(server);
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidFilter,
        execute: q => client.count(q),
        onError,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.loading).toBe(false);
    expect(result.current.result).toBeUndefined();
    expect(result.current.error).toBeInstanceOf(FetcherError);
    expect(onError).toHaveBeenCalledWith(result.current.error);
    const error = await toWowError(result.current.error);
    expect(error?.errorCode).toBe('IllegalArgument');
    expect(error?.status).toBe(400);
  });

  it('clears the error and the result on reset()', async () => {
    const server = fakeServer(() => wowError('NotFound', 'gone', 404));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useSingleQuery<OrderState>({
        initialQuery: singleQuery({ filter: paidFilter }),
        execute: q => client.singleState(q),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => result.current.reset());
    expect(result.current.status).toBe('idle');
    expect(result.current.error).toBeUndefined();
  });
});

describe('races, aborts and StrictMode', () => {
  it('keeps the newer result when an older response arrives late', async () => {
    const first = deferred<Response>();
    const server = fakeServer(() => first.promise);
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useListQuery<OrderState>({
        initialQuery: listQuery({ filter: filter.id('first') }),
        execute: (q, attributes, abortController) =>
          client.listState(q, attributes, abortController),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    server.handle(() => json([shipped]));
    act(() =>
      result.current.setQuery(listQuery({ filter: filter.id('second') })),
    );
    await waitFor(() => expect(result.current.result).toEqual([shipped]));
    expect(server.requests[0].signal?.aborted).toBe(true);
    first.resolve(json([paid]));
    await act(() => new Promise(resolve => setTimeout(resolve, 10)));
    expect(result.current.result).toEqual([shipped]);
    expect(result.current.status).toBe('success');
  });

  it('keeps the newer result even when execute ignores the abort signal', async () => {
    const first = deferred<Response>();
    const server = fakeServer(() => first.promise);
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useListQuery<OrderState>({
        initialQuery: listQuery({ filter: filter.id('first') }),
        // No abortController handed on: the first request cannot be cancelled.
        execute: q => client.listState(q),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    server.handle(() => json([shipped]));
    act(() =>
      result.current.setQuery(listQuery({ filter: filter.id('second') })),
    );
    await waitFor(() => expect(result.current.result).toEqual([shipped]));
    first.resolve(json([paid]));
    await act(() => new Promise(resolve => setTimeout(resolve, 10)));
    expect(result.current.result).toEqual([shipped]);
  });

  it('aborts the request in flight on unmount', async () => {
    const pending = deferred<Response>();
    const server = fakeServer(() => pending.promise);
    const client = orderClient(server);
    const onSuccess = vi.fn();
    const { unmount } = renderHook(() =>
      usePagedQuery<OrderState>({
        initialQuery: pagedQuery({ filter: paidFilter }),
        execute: (q, attributes, abortController) =>
          client.pagedState(q, attributes, abortController),
        onSuccess,
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    unmount();
    expect(server.requests[0].signal?.aborted).toBe(true);
    pending.resolve(json({ total: 1, list: [paid] }));
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('aborts on abort() and goes back to idle', async () => {
    const server = fakeServer(() => deferred<Response>().promise);
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidFilter,
        execute: (q, attributes, abortController) =>
          client.count(q, attributes, abortController),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    await act(async () => result.current.abort());
    expect(server.requests[0].signal?.aborted).toBe(true);
    expect(result.current.status).toBe('idle');
    expect(result.current.error).toBeUndefined();
  });

  it('settles on one result under StrictMode', async () => {
    const server = fakeServer(() => json(paid));
    const client = orderClient(server);
    const { result } = renderHook(
      () =>
        useSingleQuery<OrderState>({
          initialQuery: singleQuery({ filter: paidFilter }),
          execute: (q, attributes, abortController) =>
            client.singleState(q, attributes, abortController),
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result).toEqual(paid);
    const live = server.requests.filter(request => !request.signal?.aborted);
    expect(live).toHaveLength(1);
  });

  it('passes the attributes option to the client', async () => {
    const server = fakeServer(() => json(1));
    const seen: unknown[] = [];
    server.fetcher.interceptors.request.use({
      name: 'ReadAttributes',
      order: 0,
      intercept(exchange) {
        seen.push(exchange.attributes.get('tenant'));
      },
    });
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidFilter,
        attributes: { tenant: 't1' },
        execute: (q, attributes, abortController) =>
          client.count(q, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.result).toBe(1));
    expect(seen).toEqual(['t1']);
  });
});
