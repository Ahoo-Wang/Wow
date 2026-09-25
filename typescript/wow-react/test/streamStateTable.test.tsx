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
 * Characterization of the list-stream hooks: one table of event × state, run
 * against both implementations — a query client's `listStateStream` as
 * `execute` (`useListStreamQuery`) and a URL (`useFetcherListStreamQuery`).
 * It pins the behaviour of today, so that the refactor of
 * `docs/design/refactor-2026-09.md` can claim "unchanged" cell by cell.
 *
 * The stream family already has the semantics section 3.3 of the plan
 * targets for these events; no cell here is marked for B3.
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ExchangeError } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  SnapshotQueryClient,
  WowError,
} from '@ahoo-wang/wow-client';
import { useFetcherListStreamQuery, useListStreamQuery } from '../src';
import {
  deferred,
  fakeServer,
  liveStream,
  wowError,
  type FakeServer,
  type LiveStream,
} from './support/fakeServer';

interface Row {
  id: string;
}

const o1: Row = { id: 'o1' };
const o2: Row = { id: 'o2' };
const o3: Row = { id: 'o3' };
const paidQuery = listQuery({ filter: filter.eq('state.status', 'PAID') });
const shippedQuery = listQuery({
  filter: filter.eq('state.status', 'SHIPPED'),
});

/** What a stream hook shows at one moment. */
interface Shown {
  status: string;
  loading: boolean;
  done: boolean;
  items: Row[];
  error: unknown;
}

interface Hook extends Shown {
  execute(): Promise<void>;
  abort(): void;
  reset(): void;
  setQuery(query: typeof paidQuery): void;
}

/**
 * A server that opens a live stream for every request, or answers it with
 * the response `refuse` returns; the test writes to each stream.
 */
function streamingServer() {
  const streams: LiveStream[] = [];
  let refuse: (() => Response) | undefined;
  const server = fakeServer(() => {
    if (refuse) return refuse();
    const stream = liveStream();
    streams.push(stream);
    return stream.response;
  });
  return {
    server,
    streams,
    /** Answers later requests with `response` instead of a stream. */
    refuseWith(response: () => Response) {
      refuse = response;
    },
  };
}

const families: {
  name: string;
  render(server: FakeServer): { result: { current: Hook }; unmount(): void };
}[] = [
  {
    name: 'useListStreamQuery (query client as execute)',
    render(server) {
      const client = new SnapshotQueryClient<Row>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(() =>
        useListStreamQuery<Row>({
          initialQuery: paidQuery,
          execute: (query, attributes, abortController) =>
            client.listStateStream(query, attributes, abortController),
        }),
      );
    },
  },
  {
    name: 'useFetcherListStreamQuery (url)',
    render(server) {
      return renderHook(() =>
        useFetcherListStreamQuery<Row>({
          fetcher: server.fetcher,
          url: 'order/snapshot/list/state',
          initialQuery: paidQuery,
        }),
      );
    },
  },
];

function shown(hook: Hook): Shown {
  const { status, loading, done, items, error } = hook;
  return { status, loading, done, items, error };
}

/** Lets the stream reader and the hook's batched updates run. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

const idle = (items: Row[]): Shown => ({
  status: 'idle',
  loading: false,
  done: false,
  items,
  error: undefined,
});
const loading = (items: Row[]): Shown => ({
  status: 'loading',
  loading: true,
  done: false,
  items,
  error: undefined,
});
const done = (items: Row[]): Shown => ({
  status: 'success',
  loading: false,
  done: true,
  items,
  error: undefined,
});
const failed = (items: Row[], error: unknown): Shown => ({
  status: 'error',
  loading: false,
  done: false,
  items,
  error,
});

const errorEvent = {
  event: 'IllegalArgument',
  data: { errorCode: 'IllegalArgument', errorMsg: 'bad' },
};

describe.each(families)('stream hook state table: $name', family => {
  /** Mounts the hook with its first stream open and `rows` received. */
  async function streaming(...rows: Row[]) {
    const held = streamingServer();
    const hook = family.render(held.server);
    await waitFor(() => expect(held.streams).toHaveLength(1));
    if (rows.length > 0) {
      held.streams[0].send(...rows.map(data => ({ data })));
      await waitFor(() => expect(hook.result.current.items).toEqual(rows));
    }
    return { ...held, ...hook };
  }

  /** Mounts the hook with its first stream ended after `rows`. */
  async function ended(...rows: Row[]) {
    const hook = await streaming(...rows);
    hook.streams[0].close();
    await waitFor(() => expect(hook.result.current.done).toBe(true));
    return hook;
  }

  it('start: loading, no rows', async () => {
    const { result } = await streaming();
    expect(shown(result.current)).toEqual(loading([]));
  });

  it('rows arrive: still loading, the rows so far', async () => {
    const { result } = await streaming(o1);
    expect(shown(result.current)).toEqual(loading([o1]));
  });

  it('start again: loading, the rows emptied', async () => {
    const { result, streams } = await ended(o1);
    act(() => void result.current.execute());
    await waitFor(() => expect(streams).toHaveLength(2));
    await waitFor(() => expect(result.current.items).toEqual([]));
    expect(shown(result.current)).toEqual(loading([]));
  });

  it('start a new query mid-stream: cancels the older stream, the rows emptied', async () => {
    const { result, streams, server } = await streaming(o1);
    act(() => result.current.setQuery(shippedQuery));
    await waitFor(() => expect(streams).toHaveLength(2));
    expect(server.requests[0].signal?.aborted).toBe(true);
    await waitFor(() => expect(streams[0].cancelled).toBe(true));
    await waitFor(() => expect(result.current.items).toEqual([]));
    expect(shown(result.current)).toEqual(loading([]));
  });

  it('start after an error: loading, the error cleared, the rows emptied', async () => {
    const { result, streams } = await streaming(o1);
    streams[0].send(errorEvent);
    streams[0].close();
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => void result.current.execute());
    await waitFor(() => expect(streams).toHaveLength(2));
    await waitFor(() => expect(result.current.items).toEqual([]));
    expect(shown(result.current)).toEqual(loading([]));
  });

  it('succeed: done with every row', async () => {
    const { result } = await ended(o1, o2);
    expect(shown(result.current)).toEqual(done([o1, o2]));
  });

  it('fail midway (error event): error, keeping the rows received', async () => {
    const { result, streams } = await streaming(o1);
    streams[0].send(errorEvent);
    streams[0].close();
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(shown(result.current)).toEqual(failed([o1], expect.any(WowError)));
  });

  it('fail on request: error, no rows', async () => {
    const held = streamingServer();
    held.refuseWith(() => wowError('Unauthorized', 'No token.', 401));
    const { result } = family.render(held.server);
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(shown(result.current)).toEqual(
      failed([], expect.any(ExchangeError)),
    );
  });

  it('fail on request after a success: error, the rows emptied', async () => {
    const { result, refuseWith } = await ended(o1);
    refuseWith(() => wowError('Unauthorized', 'No token.', 401));
    await act(() => result.current.execute());
    expect(shown(result.current)).toEqual(
      failed([], expect.any(ExchangeError)),
    );
  });

  it('abort() mid-stream: idle, the stream cancelled, keeping the rows', async () => {
    const { result, streams, server } = await streaming(o1);
    await act(async () => result.current.abort());
    expect(server.requests[0].signal?.aborted).toBe(true);
    await waitFor(() => expect(streams[0].cancelled).toBe(true));
    expect(shown(result.current)).toEqual(idle([o1]));
  });

  it('abort() when ended: idle and no longer done, keeping every row', async () => {
    const { result } = await ended(o1, o2);
    await act(async () => result.current.abort());
    expect(shown(result.current)).toEqual(idle([o1, o2]));
  });

  it('abort() after an error: idle, the error cleared, keeping the rows', async () => {
    const { result, streams } = await streaming(o1);
    streams[0].send(errorEvent);
    streams[0].close();
    await waitFor(() => expect(result.current.status).toBe('error'));
    await act(async () => result.current.abort());
    expect(shown(result.current)).toEqual(idle([o1]));
  });

  it('reset() mid-stream: idle, the stream cancelled, the rows emptied', async () => {
    const { result, streams, server } = await streaming(o1);
    act(() => result.current.reset());
    expect(server.requests[0].signal?.aborted).toBe(true);
    await waitFor(() => expect(streams[0].cancelled).toBe(true));
    expect(shown(result.current)).toEqual(idle([]));
  });

  it('reset() when ended: idle, the rows emptied', async () => {
    const { result } = await ended(o1, o2);
    act(() => result.current.reset());
    expect(shown(result.current)).toEqual(idle([]));
  });

  it('reset() after an error: idle, the error cleared, the rows emptied', async () => {
    const { result, streams } = await streaming(o1);
    streams[0].send(errorEvent);
    streams[0].close();
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => result.current.reset());
    expect(shown(result.current)).toEqual(idle([]));
  });

  it('unmount mid-stream: the request aborted and the stream cancelled', async () => {
    const { streams, server, unmount } = await streaming(o1);
    unmount();
    expect(server.requests[0].signal?.aborted).toBe(true);
    await waitFor(() => expect(streams[0].cancelled).toBe(true));
  });
});

describe('stream hook state table: execute that ignores the abort signal', () => {
  /** A stream the hook cannot cancel before it opens. */
  async function uncancellable() {
    const opened = deferred<Response>();
    const server = fakeServer(() => opened.promise);
    const client = new SnapshotQueryClient<Row>({
      fetcher: server.fetcher,
      basePath: 'order',
    });
    const hook = renderHook(() =>
      useListStreamQuery<Row>({
        initialQuery: paidQuery,
        execute: query => client.listStateStream(query),
      }),
    );
    await waitFor(() => expect(server.requests).toHaveLength(1));
    const stream = liveStream();
    return {
      ...hook,
      stream,
      /** The server opens the stream and sends `rows` on it, left open. */
      open(...rows: Row[]) {
        opened.resolve(stream.response);
        if (rows.length > 0) stream.send(...rows.map(data => ({ data })));
      },
    };
  }

  it('a stream that opens after abort() is cancelled unread', async () => {
    const { result, stream, open } = await uncancellable();
    await act(async () => result.current.abort());
    open(o3);
    await settle();
    expect(stream.cancelled).toBe(true);
    expect(shown(result.current)).toEqual(idle([]));
  });

  it('a stream that opens after reset() is cancelled unread', async () => {
    const { result, stream, open } = await uncancellable();
    act(() => result.current.reset());
    open(o3);
    await settle();
    expect(stream.cancelled).toBe(true);
    expect(shown(result.current)).toEqual(idle([]));
  });
});
