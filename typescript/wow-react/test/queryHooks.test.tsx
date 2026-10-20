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
import { QueryModelClient, WowProblemError } from '@ahoo-wang/wow-client';
import {
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
} from '@ahoo-wang/wow-client/dsl';
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
  problem,
  type FakeServer,
} from './support/fakeServer';

interface OrderState {
  id: string;
  status: string;
}

const paid: OrderState = { id: 'o1', status: 'PAID' };
const shipped: OrderState = { id: 'o2', status: 'SHIPPED' };
const paidFilter = filter.eq('state.status', 'PAID');
const paidCount = { filter: paidFilter };

/**
 * A query as the route receives it: JSON drops the `undefined` fields, and
 * `kind` names the operation.
 */
function wire(value: unknown, kind: string): unknown {
  return { ...JSON.parse(JSON.stringify(value)), kind };
}

function orderClient(server: FakeServer) {
  return new QueryModelClient<OrderState>('/shop/order/snapshot/query', {
    fetcher: server.fetcher,
  });
}

describe('each query hook with a query client', () => {
  it('useSingleQuery sends the single query and keeps the item', async () => {
    const server = fakeServer(() => json(paid));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useSingleQuery<OrderState>({
        initialQuery: singleQuery({ filter: paidFilter }),
        execute: (query, options) => client.single(query, options),
      }),
    );
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result).toEqual(paid);
    expect(result.current.error).toBeUndefined();
    expect(server.requests).toHaveLength(1);
    expect(server.requests[0]).toMatchObject({
      method: 'POST',
      path: '/shop/order/snapshot/query',
      body: wire(singleQuery({ filter: paidFilter }), 'single'),
    });
  });

  it('useListQuery sends the list query and keeps the rows', async () => {
    const server = fakeServer(() => json([paid, shipped]));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useListQuery<OrderState>({
        initialQuery: listQuery({ filter: filter.matchAll(), limit: 2 }),
        execute: (query, options) => client.list(query, options),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual([paid, shipped]));
    expect(server.requests[0]).toMatchObject({
      path: '/shop/order/snapshot/query',
      body: { limit: 2, kind: 'list' },
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
        execute: (q, options) => client.paged(q, options),
      }),
    );
    await waitFor(() => expect(result.current.result).toEqual(page));
    expect(server.requests[0]).toMatchObject({
      path: '/shop/order/snapshot/query',
      body: wire(query, 'paged'),
    });
  });

  it('useCountQuery sends the count and keeps its answer', async () => {
    const server = fakeServer(() => json({ count: 7, exact: true }));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidCount,
        execute: (query, options) => client.count(query, options),
      }),
    );
    await waitFor(() =>
      expect(result.current.result).toEqual({ count: 7, exact: true }),
    );
    expect(server.requests[0]).toMatchObject({
      path: '/shop/order/snapshot/query',
      body: wire(paidCount, 'count'),
    });
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
        execute: q => client.single(q),
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
        execute: q => client.paged(q),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    const next = pagedQuery({ pagination: { index: 2, size: 10 } });
    act(() => result.current.setQuery(next));
    await waitFor(() =>
      expect(result.current.result?.list[0]).toEqual({
        ...next,
        kind: 'paged',
      }),
    );
    expect(result.current.getQuery()).toEqual(next);
    expect(server.requests).toHaveLength(2);
  });

  it('runs again when the query option changes', async () => {
    const server = fakeServer(request => json(request.body));
    const { result, rerender } = renderHook(
      ({ id }: { id: string }) =>
        useSingleQuery<unknown>({
          query: singleQuery({ filter: filter.id(id) }),
          execute: q => orderClient(server).single(q),
        }),
      { initialProps: { id: 'a' } },
    );
    await waitFor(() =>
      expect(result.current.result).toEqual({
        ...singleQuery({ filter: filter.id('a') }),
        kind: 'single',
      }),
    );
    rerender({ id: 'b' });
    await waitFor(() =>
      expect(result.current.result).toEqual({
        ...singleQuery({ filter: filter.id('b') }),
        kind: 'single',
      }),
    );
    expect(server.requests).toHaveLength(2);
  });

  it('sets the problem of a failed request as the error', async () => {
    const server = fakeServer(() =>
      problem('wow.query.invalid', 'Invalid filter request body.'),
    );
    const client = orderClient(server);
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidCount,
        execute: q => client.count(q),
        onError,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.loading).toBe(false);
    expect(result.current.result).toBeUndefined();
    expect(result.current.error).toBeInstanceOf(WowProblemError);
    expect(onError).toHaveBeenCalledWith(result.current.error);
    const error = result.current.error as WowProblemError;
    expect(error.code).toBe('wow.query.invalid');
    expect(error.status).toBe(400);
  });

  it('clears the error and the result on reset()', async () => {
    const server = fakeServer(() =>
      problem('wow.aggregate.not-found', 'gone', 404),
    );
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useSingleQuery<OrderState>({
        initialQuery: singleQuery({ filter: paidFilter }),
        execute: q => client.single(q),
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
        execute: (q, options) => client.list(q, options),
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
        // No signal handed on: the first request cannot be cancelled.
        execute: q => client.list(q),
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
        execute: (q, options) => client.paged(q, options),
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
        initialQuery: paidCount,
        execute: (q, options) => client.count(q, options),
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
          execute: (q, options) => client.single(q, options),
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.result).toEqual(paid);
    const live = server.requests.filter(request => !request.signal?.aborted);
    expect(live).toHaveLength(1);
  });

  it('passes the requestOptions option to the client', async () => {
    const server = fakeServer(() => json({ count: 1, exact: true }));
    const client = orderClient(server);
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: paidCount,
        requestOptions: { headers: { 'X-Tenant': 't1' } },
        execute: (q, options) => client.count(q, options),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(server.requests[0].headers.get('X-Tenant')).toBe('t1');
  });
});
