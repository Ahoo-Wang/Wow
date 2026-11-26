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
 * What ends a run and what a hook offers next (D486 ①, #6921's questions):
 * a change of the query or the request options aborts the run in flight,
 * `autoExecute` or not; only the run's own signal aborts it, so an
 * `AbortError` from elsewhere is its failure; and a cursor hook whose
 * controlled query is `undefined` has nothing more to read.
 */

import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CursorPage, FilterExpression } from '@ahoo-wang/wow-client/dsl';
import { useCountQuery, useCursorQuery } from '../src';

const MATCH_ALL = { op: 'match-all' } as unknown as FilterExpression;

/** A promise the test settles by hand. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe('a run of a query hook', () => {
  it('is aborted when the query changes while it is in flight, though autoExecute is off', async () => {
    const signals: AbortSignal[] = [];
    const answer = deferred<{ count: number; exact: boolean }>();
    const { result, rerender } = renderHook(
      ({ query }) =>
        useCountQuery({
          query,
          autoExecute: false,
          execute: (_query, options) => {
            signals.push(options.signal);
            return answer.promise;
          },
        }),
      { initialProps: { query: { filter: MATCH_ALL, limit: 1 } } },
    );
    act(() => void result.current.execute());
    await waitFor(() => expect(result.current.status).toBe('loading'));

    // The query on screen moves on; the run of the old one answers it no more.
    rerender({ query: { filter: MATCH_ALL, limit: 2 } });
    await waitFor(() => expect(signals[0].aborted).toBe(true));
    expect(result.current.status).toBe('idle');
    // Nothing runs by itself: autoExecute is off.
    expect(signals).toHaveLength(1);
    await act(async () => answer.resolve({ count: 7, exact: true }));
    expect(result.current.result).toBeUndefined();
    // The same query by content is no change: nothing is aborted.
    act(() => void result.current.execute());
    rerender({ query: { filter: MATCH_ALL, limit: 2 } });
    expect(signals[1].aborted).toBe(false);
  });

  it("fails with an AbortError that is not its own signal's, and goes idle only for its own", async () => {
    // An executor whose own controller gave up: the request did not end
    // because the hook stopped it.
    const timedOut = Object.assign(new Error('The request timed out.'), {
      name: 'AbortError',
    });
    const { result } = renderHook(() =>
      useCountQuery({
        initialQuery: { filter: MATCH_ALL },
        execute: () => Promise.reject(timedOut),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.error).toBe(timedOut);
    expect(result.current.problem).toBeUndefined();
  });
});

describe('a cursor hook', () => {
  it('has no more to read while its controlled query is undefined', async () => {
    const page: CursorPage<{ id: string }> = {
      list: [{ id: 'a' }],
      nextCursor: 'after-a',
    };
    const { result, rerender } = renderHook(
      ({ query }: { query: { filter: FilterExpression } | undefined }) =>
        useCursorQuery<{ id: string }>({
          query,
          execute: () => Promise.resolve(page),
        }),
      {
        initialProps: { query: { filter: MATCH_ALL } } as {
          query: { filter: FilterExpression } | undefined;
        },
      },
    );
    await waitFor(() => expect(result.current.hasMore).toBe(true));
    rerender({ query: undefined });
    expect(result.current.hasMore).toBe(false);
    // What was read stays shown.
    expect(result.current.items).toEqual([{ id: 'a' }]);
    rerender({ query: { filter: MATCH_ALL } });
    await waitFor(() => expect(result.current.hasMore).toBe(true));
  });
});
