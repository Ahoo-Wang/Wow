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
 * The list-stream hook through a real Fetcher and wow-client's event-stream
 * extractor, against a fake `fetch` that answers the way a Wow server does:
 * rows as unnamed events, a failure midway as one last event named by its
 * error code, and JSON unless the request accepts `text/event-stream`.
 */

import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryModelClient, WowProblemError } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '../src';
import { PUBLISH_INTERVAL_MS } from '../src/internal/readStreamRows';
import {
  deferred,
  errorEvent,
  fakeServer,
  json,
  liveStream,
  sse,
  problem,
  type FakeServer,
  type Handler,
  type LiveStream,
} from './support/fakeServer';

interface OrderState {
  id: string;
  status: string;
}

const o1: OrderState = { id: 'o1', status: 'PAID' };
const o2: OrderState = { id: 'o2', status: 'PAID' };
const o3: OrderState = { id: 'o3', status: 'PAID' };
const paidQuery = listQuery({ filter: filter.eq('state.status', 'PAID') });
const URL = '/shop/order/snapshot/query';

/** Answers like Wow: an event stream only to a request that accepts one. */
function wowList(stream: () => Response): Handler {
  return request =>
    request.headers.get('Accept')?.includes('text/event-stream')
      ? stream()
      : json([o1, o2]);
}

/** A query client's `streamRows` on the fake server, as `execute`. */
function rowsOf<R>(server: FakeServer) {
  const client = new QueryModelClient<R>(URL, { fetcher: server.fetcher });
  return (query: Parameters<typeof client.streamRows>[0], options: object) =>
    client.streamRows(query, options);
}

/** Lets the stream reader and the hook's batched updates run. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

describe('useStreamQuery over a query client', () => {
  it('asks for an event stream and collects every row', async () => {
    const server = fakeServer(
      wowList(() => sse({ data: o1 }, { data: o2 }, { data: o3 })),
    );
    const onSuccess = vi.fn();
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
        onSuccess,
      }),
    );
    expect(result.current.loading).toBe(true);
    expect(result.current.items).toEqual([]);
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o1, o2, o3]);
    expect(result.current.status).toBe('success');
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeUndefined();
    expect(onSuccess).toHaveBeenCalledWith([o1, o2, o3]);
    const [request] = server.requests;
    expect(request.method).toBe('POST');
    expect(request.path).toBe(URL);
    expect(request.headers.get('Accept')).toBe('text/event-stream');
    expect(request.body).toEqual({
      ...JSON.parse(JSON.stringify(paidQuery)),
      kind: 'list',
    });
  });

  it('shows rows as they arrive and stays loading until the stream ends', async () => {
    const stream = liveStream();
    const server = fakeServer(() => stream.response);
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: o1 });
    await waitFor(() => expect(result.current.items).toEqual([o1]));
    expect(result.current.loading).toBe(true);
    expect(result.current.done).toBe(false);
    stream.send({ data: o2 }, { data: o3 });
    await waitFor(() => expect(result.current.items).toEqual([o1, o2, o3]));
    expect(result.current.loading).toBe(true);
    stream.close();
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.loading).toBe(false);
  });

  it('ends with no rows and done when the stream is empty', async () => {
    const server = fakeServer(() => sse());
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([]);
  });

  it('ends with a WowProblemError when the server sends an error event, keeping the rows before it', async () => {
    const server = fakeServer(() =>
      sse(
        { data: o1 },
        errorEvent('wow.query.invalid', 'Invalid filter request body.'),
      ),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
        onError,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    const { error } = result.current;
    expect(error).toBeInstanceOf(WowProblemError);
    expect(error).toMatchObject({ code: 'wow.query.invalid' });
    expect(onError).toHaveBeenCalledWith(error);
    expect(result.current.items).toEqual([o1]);
    expect(result.current.done).toBe(false);
    expect(result.current.loading).toBe(false);
  });

  it('sets a failed request as the error', async () => {
    const server = fakeServer(() =>
      problem('wow.auth.unauthenticated', 'No token.', 401),
    );
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBeInstanceOf(WowProblemError);
    expect(result.current.items).toEqual([]);
  });
});

describe('useStreamQuery with a query client', () => {
  it('reads the stream the client opens', async () => {
    const server = fakeServer(wowList(() => sse({ data: o1 }, { data: o2 })));
    const client = new QueryModelClient<OrderState>(
      '/shop/order/snapshot/query',
      { fetcher: server.fetcher },
    );
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        initialQuery: paidQuery,
        execute: (query, options) => client.streamRows(query, options),
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o1, o2]);
    expect(server.requests[0].path).toBe('/shop/order/snapshot/query');
    expect(server.requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('waits for execute() when autoExecute is false, and runs again on execute()', async () => {
    const server = fakeServer(() => sse({ data: o1 }));
    const client = new QueryModelClient<OrderState>(
      '/shop/order/snapshot/query',
      { fetcher: server.fetcher },
    );
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        initialQuery: paidQuery,
        autoExecute: false,
        execute: (query, options) => client.streamRows(query, options),
      }),
    );
    await settle();
    expect(server.requests).toHaveLength(0);
    expect(result.current.status).toBe('idle');
    await act(() => result.current.execute());
    expect(result.current.items).toEqual([o1]);
    server.handle(() => sse({ data: o2 }));
    await act(() => result.current.execute());
    expect(result.current.items).toEqual([o2]);
    expect(result.current.done).toBe(true);
  });
});

describe('where the stream stopped', () => {
  it('keeps the position of the last row read, and clears it on reset()', async () => {
    const server = fakeServer(() => sse({ data: o1 }, { data: o2 }));
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      }),
    );
    expect(result.current.position).toBeUndefined();
    await waitFor(() => expect(result.current.done).toBe(true));
    // The events' ids, 0 and 1, are the rows' positions.
    expect(result.current.position).toBe('1');
    act(() => result.current.reset());
    expect(result.current.position).toBeUndefined();
    expect(result.current.items).toEqual([]);
  });

  it('keeps the position of the rows read before a failure', async () => {
    const server = fakeServer(() =>
      sse({ data: o1 }, errorEvent('wow.query.failed', 'boom')),
    );
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
        requestOptions: { resume: false },
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.items).toEqual([o1]);
    expect(result.current.position).toBe('0');
  });

  it('keeps no position for rows that have none, as a list no cursor orders', async () => {
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: async () =>
          new ReadableStream({
            start(controller) {
              controller.enqueue({ row: o1, position: 'p1' });
              controller.enqueue({ row: o2 });
              controller.close();
            },
          }),
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o1, o2]);
    // The last row that had a position, not the last row.
    expect(result.current.position).toBe('p1');
  });

  it('resumes after a saved position given as requestOptions.resume', async () => {
    const server = fakeServer(() => sse({ data: o3 }));
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
        requestOptions: { resume: { lastEventId: '7' } },
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(server.requests[0].headers.get('Last-Event-ID')).toBe('7');
    expect(result.current.items).toEqual([o3]);
  });
});

describe('the stream lifecycle', () => {
  function streamingHook(streams: LiveStream[]) {
    let next = 0;
    const server = fakeServer(() => streams[next++].response);
    const hook = renderHook(() =>
      useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      }),
    );
    return { server, ...hook };
  }

  it('cancels the older stream when a newer query starts, and never shows its rows', async () => {
    const older = liveStream();
    const newer = liveStream();
    const { server, result } = streamingHook([older, newer]);
    await waitFor(() => expect(server.requests).toHaveLength(1));
    older.send({ data: o1 });
    await waitFor(() => expect(result.current.items).toEqual([o1]));

    act(() => result.current.setQuery(listQuery({ filter: filter.id('o3') })));
    await waitFor(() => expect(server.requests).toHaveLength(2));
    expect(server.requests[0].signal?.aborted).toBe(true);
    await waitFor(() => expect(older.cancelled).toBe(true));
    expect(result.current.items).toEqual([]);

    newer.send({ data: o3 });
    newer.close();
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o3]);
  });

  it('ignores an older stream that opens after a newer query started', async () => {
    const olderResponse = deferred<Response>();
    const older = liveStream();
    let calls = 0;
    const server = fakeServer(() =>
      calls++ === 0 ? olderResponse.promise : sse({ data: o3 }),
    );
    const client = new QueryModelClient<OrderState>(
      '/shop/order/snapshot/query',
      { fetcher: server.fetcher },
    );
    const { result } = renderHook(() =>
      useStreamQuery<OrderState>({
        initialQuery: paidQuery,
        // No signal handed on: the older request cannot be cancelled.
        execute: query => client.streamRows(query),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    act(() => result.current.setQuery(listQuery({ filter: filter.id('o3') })));
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o3]);

    olderResponse.resolve(older.response);
    older.send({ data: o1 });
    await settle();
    expect(older.cancelled).toBe(true);
    expect(result.current.items).toEqual([o3]);
    expect(result.current.done).toBe(true);
  });

  it('stops on abort(), keeping the rows received', async () => {
    const stream = liveStream();
    const { server, result } = streamingHook([stream]);
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: o1 });
    await waitFor(() => expect(result.current.items).toEqual([o1]));
    await act(async () => result.current.abort());
    await waitFor(() => expect(stream.cancelled).toBe(true));
    expect(result.current.status).toBe('idle');
    expect(result.current.loading).toBe(false);
    expect(result.current.done).toBe(false);
    expect(result.current.items).toEqual([o1]);
  });

  it('stops and empties the rows on reset()', async () => {
    const stream = liveStream();
    const { server, result } = streamingHook([stream]);
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: o1 });
    await waitFor(() => expect(result.current.items).toEqual([o1]));
    act(() => result.current.reset());
    await waitFor(() => expect(stream.cancelled).toBe(true));
    expect(result.current.items).toEqual([]);
    expect(result.current.status).toBe('idle');
  });

  it('cancels the stream on unmount', async () => {
    const stream = liveStream();
    const { server, result, unmount } = streamingHook([stream]);
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: o1 });
    await waitFor(() => expect(result.current.items).toEqual([o1]));
    unmount();
    await waitFor(() => expect(stream.cancelled).toBe(true));
    expect(server.requests[0].signal?.aborted).toBe(true);
  });

  it('reads one stream to its end under StrictMode, with no locked stream', async () => {
    const streams: LiveStream[] = [];
    const server = fakeServer(() => {
      const stream = liveStream();
      streams.push(stream);
      return stream.response;
    });
    const onError = vi.fn();
    const { result } = renderHook(
      () =>
        useStreamQuery<OrderState>({
          execute: rowsOf<OrderState>(server),
          initialQuery: paidQuery,
          onError,
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(streams.length).toBeGreaterThan(0));
    const live = streams[streams.length - 1];
    live.send({ data: o1 }, { data: o2 });
    live.close();
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o1, o2]);
    expect(onError).not.toHaveBeenCalled();
    expect(streams.slice(0, -1).every(stream => stream.cancelled)).toBe(true);
    const open = server.requests.filter(request => !request.signal?.aborted);
    expect(open).toHaveLength(1);
  });

  it('renders a bounded number of times, not once per row', async () => {
    const rows = Array.from({ length: 500 }, (_, i) => ({
      id: `o${i}`,
      status: 'PAID',
    }));
    const server = fakeServer(() => sse(...rows.map(data => ({ data }))));
    let renders = 0;
    const { result } = renderHook(() => {
      renders++;
      return useStreamQuery<OrderState>({
        execute: rowsOf<OrderState>(server),
        initialQuery: paidQuery,
      });
    });
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual(rows);
    expect(renders).toBeLessThan(10);
  });

  it('reads 100,000 rows in 1,000 chunks with renders bounded by time, not by chunks', async () => {
    const ROWS = 100_000;
    const CHUNKS = 1_000;
    const stream = liveStream();
    const server = fakeServer(() => stream.response);
    let renders = 0;
    const { result } = renderHook(() => {
      renders++;
      return useStreamQuery<{ i: number }>({
        execute: rowsOf<{ i: number }>(server),
        initialQuery: paidQuery,
      });
    });
    await waitFor(() => expect(server.requests).toHaveLength(1));
    const started = Date.now();
    const perChunk = ROWS / CHUNKS;
    for (let chunk = 0; chunk < CHUNKS; chunk++) {
      stream.send(
        ...Array.from({ length: perChunk }, (_, i) => ({
          data: { i: chunk * perChunk + i },
        })),
      );
      // One chunk a macrotask, as a network delivers them.
      await act(() => new Promise(resolve => setTimeout(resolve, 0)));
    }
    stream.close();
    await waitFor(() => expect(result.current.done).toBe(true), {
      timeout: 30_000,
    });
    const elapsed = Date.now() - started;
    expect(result.current.items).toHaveLength(ROWS);
    expect(result.current.items[ROWS - 1]).toEqual({ i: ROWS - 1 });
    // At most one publish per 16 ms, plus the first and the last and a few
    // renders of state. Bounded by the time the stream took, not by its
    // chunks, so a slow machine loosens the bound as much as it slows the
    // stream; publishing once per chunk renders several times more.
    expect(PUBLISH_INTERVAL_MS).toBeGreaterThanOrEqual(16);
    expect(renders).toBeLessThanOrEqual(Math.ceil(elapsed / 16) + 10);
  }, 60_000);
});
