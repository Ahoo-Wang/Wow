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
 * Review round 1: behaviour the hooks promise that main does not keep. Each
 * test states the promised behaviour and fails on main for the defect's
 * reason.
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { type Positioned, QueryModelClient } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery, useStreamQuery } from '../src';
import { deferred, fakeServer, json } from './support/fakeServer';

interface Row {
  id: string;
}

const o1: Row = { id: 'o1' };
const o2: Row = { id: 'o2' };
const o3: Row = { id: 'o3' };
const s1: Row = { id: 's1' };

describe('useCursorQuery', () => {
  // The two queries differ in `size`, so the server can tell them apart.
  const paid = { filter: filter.eq('state.status', 'PAID'), size: 2 };
  const shipped = { filter: filter.eq('state.status', 'SHIPPED'), size: 3 };

  interface Sent {
    size: number;
    cursor: string | null;
  }

  it('r1 a page cursor is only ever sent with the query whose page answered it, also with autoExecute off', async () => {
    // PAID has two pages (the second after cursor `c1`); SHIPPED has one.
    // A cursor is the position in one query's order: `nextCursor` of the
    // page before, "with the same filter and sort" (CursorQuery.cursor).
    const server = fakeServer(request => {
      const { size, cursor } = request.body as Sent;
      if (cursor === 'c1') return json({ list: [o3], nextCursor: null });
      return size === paid.size
        ? json({ list: [o1, o2], nextCursor: 'c1' })
        : json({ list: [s1], nextCursor: null });
    });
    const client = new QueryModelClient<Row>('/shop/order/snapshot/query', {
      fetcher: server.fetcher,
    });
    const { result, rerender } = renderHook(
      ({ query }: { query: typeof paid }) =>
        useCursorQuery<Row>({
          query,
          autoExecute: false,
          execute: (q, options) => client.cursor(q, options),
        }),
      { initialProps: { query: paid } },
    );
    await act(() => result.current.execute());
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.hasMore).toBe(true);

    // The query changes; with autoExecute off nothing runs yet.
    rerender({ query: shipped });
    expect(result.current.getQuery()).toEqual(shipped);
    await act(() => result.current.fetchNext());

    // Whatever fetchNext() does now, it never asks SHIPPED for the page
    // after PAID's cursor, and never appends to PAID's rows a page read
    // with SHIPPED's filter.
    const sentWithShipped = server.requests
      .map(request => request.body as Sent)
      .filter(body => body.size === shipped.size);
    expect(sentWithShipped.map(body => body.cursor)).not.toContain('c1');
    expect(result.current.items).not.toEqual([o1, o2, o3]);
  });
});

describe('useStreamQuery', () => {
  const paidQuery = listQuery({ filter: filter.eq('state.status', 'PAID') });

  it('r1 abort() keeps every row the hook had read from the stream, not only the ones it had published', async () => {
    // A stream that hands out a row only when the test enqueues it, and
    // says when the reader asks for the next one: once it asks for a third,
    // it has read the second.
    let controller!: ReadableStreamDefaultController<Positioned<Row>>;
    let pulls = 0;
    const thirdRead = deferred<void>();
    const stream = new ReadableStream<Positioned<Row>>(
      {
        start(c) {
          controller = c;
        },
        pull() {
          pulls += 1;
          if (pulls === 3) thirdRead.resolve();
        },
      },
      { highWaterMark: 0 },
    );
    const { result } = renderHook(() =>
      useStreamQuery<Row>({
        initialQuery: paidQuery,
        execute: async () => stream,
      }),
    );
    await waitFor(() => expect(pulls).toBe(1));
    controller.enqueue({ row: o1, position: '1' });
    await waitFor(() => expect(result.current.items).toEqual([o1]));

    // The second row is read; within the same turn, before the next
    // publish is due, the reader stops the stream.
    await act(async () => {
      controller.enqueue({ row: o2, position: '2' });
      await thirdRead.promise;
      result.current.abort();
    });
    await act(() => new Promise(resolve => setTimeout(resolve, 40)));

    expect(result.current.status).toBe('idle');
    // "abort() and an error keep the records received before them": o2 was
    // received; and `position` is where the stream stopped, for a resume.
    expect(result.current.items).toEqual([o1, o2]);
    expect(result.current.position).toBe('2');
  });
});
