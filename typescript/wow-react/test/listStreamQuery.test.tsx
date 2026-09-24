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
 * The list-stream hooks through a real Fetcher and wow-client's event-stream
 * extractor, against a fake `fetch` that answers the way a Wow server does:
 * rows as unnamed events, a failure midway as one last event named by its
 * error code, and JSON unless the request accepts `text/event-stream`.
 */

import { StrictMode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ExchangeError } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  SnapshotQueryClient,
  WowError,
} from '@ahoo-wang/wow-client';
import { eq, listQuery as legacyListQuery } from '@ahoo-wang/wow-client/legacy';
import { useFetcherListStreamQuery, useListStreamQuery } from '../src';
import {
  deferred,
  fakeServer,
  json,
  liveStream,
  sse,
  wowError,
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
const URL = 'order/snapshot/list/state';

/** Answers like Wow: an event stream only to a request that accepts one. */
function wowList(stream: () => Response): Handler {
  return request =>
    request.headers.get('Accept')?.includes('text/event-stream')
      ? stream()
      : json([o1, o2]);
}

/** Lets the stream reader and the hook's batched updates run. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

describe('useFetcherListStreamQuery', () => {
  it('asks for an event stream and collects every row', async () => {
    const server = fakeServer(
      wowList(() => sse({ data: o1 }, { data: o2 }, { data: o3 })),
    );
    const onSuccess = vi.fn();
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
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
    expect(request.path).toBe(`/${URL}`);
    expect(request.headers.get('Accept')).toBe('text/event-stream');
    expect(request.body).toEqual(JSON.parse(JSON.stringify(paidQuery)));
  });

  it('shows rows as they arrive and stays loading until the stream ends', async () => {
    const stream = liveStream();
    const server = fakeServer(() => stream.response);
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
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
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([]);
  });

  it('ends with a WowError when the server sends an error event, keeping the rows before it', async () => {
    const server = fakeServer(() =>
      sse(
        { data: o1 },
        {
          event: 'IllegalArgument',
          data: {
            errorCode: 'IllegalArgument',
            errorMsg: 'Invalid filter request body.',
          },
        },
      ),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
        initialQuery: paidQuery,
        onError,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    const { error } = result.current;
    expect(error).toBeInstanceOf(WowError);
    expect(error).toMatchObject({
      errorCode: 'IllegalArgument',
      errorMsg: 'Invalid filter request body.',
    });
    expect(onError).toHaveBeenCalledWith(error);
    expect(result.current.items).toEqual([o1]);
    expect(result.current.done).toBe(false);
    expect(result.current.loading).toBe(false);
  });

  it('sets a failed request as the error', async () => {
    const server = fakeServer(() => wowError('Unauthorized', 'No token.', 401));
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
        initialQuery: paidQuery,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBeInstanceOf(ExchangeError);
    expect(result.current.items).toEqual([]);
  });

  it('still takes a Condition query (compat)', async () => {
    const server = fakeServer(wowList(() => sse({ data: o1 })));
    const query = legacyListQuery({ condition: eq('state.status', 'PAID') });
    const { result } = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
        initialQuery: query,
      }),
    );
    await waitFor(() => expect(result.current.items).toEqual([o1]));
    expect(server.requests[0].body).toEqual(JSON.parse(JSON.stringify(query)));
  });
});

describe('useListStreamQuery with a query client', () => {
  it('reads the stream the client opens', async () => {
    const server = fakeServer(wowList(() => sse({ data: o1 }, { data: o2 })));
    const client = new SnapshotQueryClient<OrderState>({
      fetcher: server.fetcher,
      basePath: 'order',
    });
    const { result } = renderHook(() =>
      useListStreamQuery<OrderState>({
        initialQuery: paidQuery,
        execute: (query, attributes, abortController) =>
          client.listStateStream(query, attributes, abortController),
      }),
    );
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual([o1, o2]);
    expect(server.requests[0].path).toBe('/order/snapshot/list/state');
    expect(server.requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('waits for execute() when autoExecute is false, and runs again on execute()', async () => {
    const server = fakeServer(() => sse({ data: o1 }));
    const client = new SnapshotQueryClient<OrderState>({
      fetcher: server.fetcher,
      basePath: 'order',
    });
    const { result } = renderHook(() =>
      useListStreamQuery<OrderState>({
        initialQuery: paidQuery,
        autoExecute: false,
        execute: (query, attributes, abortController) =>
          client.listStateStream(query, attributes, abortController),
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

describe('the stream lifecycle', () => {
  function streamingHook(streams: LiveStream[]) {
    let next = 0;
    const server = fakeServer(() => streams[next++].response);
    const hook = renderHook(() =>
      useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
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
    const client = new SnapshotQueryClient<OrderState>({
      fetcher: server.fetcher,
      basePath: 'order',
    });
    const { result } = renderHook(() =>
      useListStreamQuery<OrderState>({
        initialQuery: paidQuery,
        // No abortController handed on: the older request cannot be cancelled.
        execute: query => client.listStateStream(query),
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
        useFetcherListStreamQuery<OrderState>({
          fetcher: server.fetcher,
          url: URL,
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

  it('renders once per network chunk, not once per row', async () => {
    const rows = Array.from({ length: 500 }, (_, i) => ({
      id: `o${i}`,
      status: 'PAID',
    }));
    const server = fakeServer(() => sse(...rows.map(data => ({ data }))));
    let renders = 0;
    const { result } = renderHook(() => {
      renders++;
      return useFetcherListStreamQuery<OrderState>({
        fetcher: server.fetcher,
        url: URL,
        initialQuery: paidQuery,
      });
    });
    await waitFor(() => expect(result.current.done).toBe(true));
    expect(result.current.items).toEqual(rows);
    expect(renders).toBeLessThan(10);
  });
});
