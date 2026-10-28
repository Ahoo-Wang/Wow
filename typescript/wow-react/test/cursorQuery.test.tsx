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
 * The cursor hook against a fake `fetch`: the pages it reads, how
 * `fetchNext()` appends them, and what a new query, a failure, `abort()`
 * and `reset()` do to the rows.
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryModelClient } from '@ahoo-wang/wow-client';
import { type CursorPage, filter } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery } from '../src';
import {
  deferred,
  fakeServer,
  json,
  problem,
  type FakeServer,
  type Handler,
} from './support/fakeServer';

interface Row {
  id: string;
}

const o1: Row = { id: 'o1' };
const o2: Row = { id: 'o2' };
const o3: Row = { id: 'o3' };
const paid = { filter: filter.eq('state.status', 'PAID'), size: 2 };
const shipped = { filter: filter.eq('state.status', 'SHIPPED'), size: 2 };

/** The cursor each request asked for. */
function cursors(server: FakeServer): unknown[] {
  return server.requests.map(
    request => (request.body as { cursor: unknown }).cursor,
  );
}

/** Answers the first page with o1, o2 and the second with o3, the last. */
const twoPages: Handler = request =>
  (request.body as { cursor: string | null }).cursor === null
    ? json({ list: [o1, o2], nextCursor: 'c1' })
    : json({ list: [o3], nextCursor: null });

function render(server: FakeServer, query: typeof paid = paid) {
  const client = new QueryModelClient<Row>('/shop/order/snapshot/query', {
    fetcher: server.fetcher,
  });
  return renderHook(
    ({ query }: { query: typeof paid }) =>
      useCursorQuery<Row>({
        query,
        execute: (q, options) => client.cursor(q, options),
      }),
    { initialProps: { query } },
  );
}

describe('useCursorQuery', () => {
  it('reads the first page from no cursor, then appends the next on fetchNext()', async () => {
    const server = fakeServer(twoPages);
    const { result } = render(server);
    expect(result.current.hasMore).toBe(false);
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.hasMore).toBe(true);
    expect(server.requests[0].body).toMatchObject({ kind: 'cursor', size: 2 });
    await act(() => result.current.fetchNext());
    expect(result.current.items).toEqual([o1, o2, o3]);
    expect(result.current.hasMore).toBe(false);
    expect(cursors(server)).toEqual([null, 'c1']);
  });

  it('does nothing on fetchNext() after the last page', async () => {
    const server = fakeServer(() => json({ list: [o1], nextCursor: null }));
    const { result } = render(server);
    await waitFor(() => expect(result.current.status).toBe('success'));
    await act(() => result.current.fetchNext());
    expect(server.requests).toHaveLength(1);
  });

  it('does nothing on fetchNext() while a page is loading', async () => {
    const second = deferred<Response>();
    const server = fakeServer(request =>
      (request.body as { cursor: string | null }).cursor === null
        ? json({ list: [o1, o2], nextCursor: 'c1' })
        : second.promise,
    );
    const { result } = render(server);
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    let first!: Promise<void>;
    act(() => {
      first = result.current.fetchNext();
    });
    expect(result.current.loading).toBe(true);
    await act(() => result.current.fetchNext());
    expect(cursors(server)).toEqual([null, 'c1']);
    second.resolve(json({ list: [o3], nextCursor: null }));
    await act(() => first);
    expect(result.current.items).toEqual([o1, o2, o3]);
  });

  it('starts again from the first page when the query changes, and on execute()', async () => {
    const server = fakeServer(twoPages);
    const { result, rerender } = render(server);
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    await act(() => result.current.fetchNext());
    rerender({ query: shipped });
    await waitFor(() => expect(server.requests).toHaveLength(3));
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(result.current.items).toEqual([o1, o2]);
    await act(() => result.current.execute());
    expect(cursors(server)).toEqual([null, 'c1', null, null]);
    expect(result.current.items).toEqual([o1, o2]);
  });

  it('keeps the rows when fetchNext() fails, and retries the same page', async () => {
    let failNext = true;
    const server = fakeServer(request => {
      if ((request.body as { cursor: string | null }).cursor === null)
        return json({ list: [o1, o2], nextCursor: 'c1' });
      if (failNext) {
        failNext = false;
        return problem('wow.query.timeout', 'Too slow.', 503);
      }
      return json({ list: [o3], nextCursor: null });
    });
    const { result } = render(server);
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    await act(() => result.current.fetchNext());
    expect(result.current.status).toBe('error');
    expect(result.current.problem?.code).toBe('wow.query.timeout');
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.hasMore).toBe(true);
    await act(() => result.current.fetchNext());
    expect(cursors(server)).toEqual([null, 'c1', 'c1']);
    expect(result.current.items).toEqual([o1, o2, o3]);
  });

  it('drops the page of a fetchNext() that abort() stopped, and keeps the rows', async () => {
    const second = deferred<Response>();
    const server = fakeServer(request =>
      (request.body as { cursor: string | null }).cursor === null
        ? json({ list: [o1, o2], nextCursor: 'c1' })
        : second.promise,
    );
    const { result } = render(server);
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    act(() => void result.current.fetchNext());
    await waitFor(() => expect(server.requests).toHaveLength(2));
    act(() => result.current.abort());
    expect(server.requests[1].signal?.aborted).toBe(true);
    expect(result.current.status).toBe('idle');
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.hasMore).toBe(true);
  });

  it('drops a page answered after abort() even when execute ignores the signal', async () => {
    const second = deferred<CursorPage<Row>>();
    const { result } = renderHook(() =>
      useCursorQuery<Row>({
        query: paid,
        execute: query =>
          query.cursor === null
            ? Promise.resolve({ list: [o1, o2], nextCursor: 'c1' })
            : second.promise,
      }),
    );
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    act(() => void result.current.fetchNext());
    act(() => result.current.abort());
    second.resolve({ list: [o3], nextCursor: null });
    await act(() => second.promise);
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.hasMore).toBe(true);
    expect(result.current.status).toBe('idle');
  });

  it('empties the rows on reset()', async () => {
    const server = fakeServer(twoPages);
    const { result } = render(server);
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    act(() => result.current.reset());
    expect(result.current.items).toEqual([]);
    expect(result.current.hasMore).toBe(false);
    expect(result.current.status).toBe('idle');
  });
});
