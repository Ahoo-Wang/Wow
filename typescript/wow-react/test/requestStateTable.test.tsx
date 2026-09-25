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
 * Characterization of the request hooks: one table of event × state, run
 * against both implementations — a query client as `execute`
 * (`useCountQuery`) and a URL (`useFetcherCountQuery`). It pins the
 * behaviour of today, fetcher-react 5.1.3 underneath, so that the refactor
 * of `docs/design/refactor-2026-09.md` can claim "unchanged" cell by cell.
 *
 * Batch B3 (#3371) moved the hooks onto the package's own state machine and
 * changed the cells of section 3.3 of the plan on purpose; each carries a
 * `B3 changed this` comment naming the value before.
 */

import { describe, expect, it } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ExchangeError } from '@ahoo-wang/fetcher';
import { filter, SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { useCountQuery, useFetcherCountQuery } from '../src';
import {
  deferred,
  fakeServer,
  json,
  wowError,
  type FakeServer,
} from './support/fakeServer';

const paidFilter = filter.eq('state.status', 'PAID');
const shippedFilter = filter.eq('state.status', 'SHIPPED');

/** What a request hook shows at one moment. */
interface Shown {
  status: string;
  loading: boolean;
  result: number | undefined;
  error: unknown;
}

interface Hook extends Shown {
  execute(): Promise<void>;
  abort(): void;
  reset(): void;
  setQuery(query: typeof paidFilter): void;
}

/**
 * A server whose every request waits until the test answers it, so that a
 * cell can act while a request is in flight.
 */
function heldServer() {
  const answers: ReturnType<typeof deferred<Response>>[] = [];
  const server = fakeServer(() => {
    const answer = deferred<Response>();
    answers.push(answer);
    return answer.promise;
  });
  return {
    server,
    /** Answers request `index` (0-based, in the order they were sent). */
    answer(index: number, response: Response) {
      answers[index].resolve(response);
    },
  };
}

const families: {
  name: string;
  render(server: FakeServer): { result: { current: Hook }; unmount(): void };
}[] = [
  {
    name: 'useCountQuery (query client as execute)',
    render(server) {
      const client = new SnapshotQueryClient<unknown>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(() =>
        useCountQuery({
          initialQuery: paidFilter,
          execute: (query, attributes, abortController) =>
            client.count(query, attributes, abortController),
        }),
      );
    },
  },
  {
    name: 'useFetcherCountQuery (url)',
    render(server) {
      return renderHook(() =>
        useFetcherCountQuery({
          fetcher: server.fetcher,
          url: 'order/snapshot/count',
          initialQuery: paidFilter,
        }),
      );
    },
  },
];

function shown(hook: Hook): Shown {
  const { status, loading, result, error } = hook;
  return { status, loading, result, error };
}

/** Lets late responses, extractors and batched updates run. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

const idle: Shown = {
  status: 'idle',
  loading: false,
  result: undefined,
  error: undefined,
};
const loading = (result: number | undefined): Shown => ({
  status: 'loading',
  loading: true,
  result,
  error: undefined,
});
const success = (result: number): Shown => ({
  status: 'success',
  loading: false,
  result,
  error: undefined,
});
const failed = (result: number | undefined): Shown => ({
  status: 'error',
  loading: false,
  result,
  error: expect.any(ExchangeError),
});

describe.each(families)('request hook state table: $name', family => {
  /** Mounts the hook and lets its first request succeed with `first`. */
  async function settledOn(first: number) {
    const held = heldServer();
    const hook = family.render(held.server);
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    held.answer(0, json(first));
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    return { ...held, ...hook };
  }

  /** Settled on `first`, then a second request in flight. */
  async function inFlightAfter(first: number) {
    const hook = await settledOn(first);
    act(() => void hook.result.current.execute());
    await waitFor(() => expect(hook.server.requests).toHaveLength(2));
    return hook;
  }

  it('start: loading, no result, no error', async () => {
    const { server } = heldServer();
    const { result } = family.render(server);
    await waitFor(() => expect(server.requests).toHaveLength(1));
    expect(shown(result.current)).toEqual(loading(undefined));
  });

  it('start again: loading, keeping the last result', async () => {
    const { result } = await inFlightAfter(1);
    expect(shown(result.current)).toEqual(loading(1));
  });

  it('start a new query: aborts the one in flight, keeping the last result', async () => {
    const { result, server } = await inFlightAfter(1);
    act(() => result.current.setQuery(shippedFilter));
    await waitFor(() => expect(server.requests).toHaveLength(3));
    expect(server.requests[1].signal?.aborted).toBe(true);
    expect(shown(result.current)).toEqual(loading(1));
  });

  it('start after an error: loading, the error cleared, the last result kept', async () => {
    const { result, server, answer } = await inFlightAfter(1);
    answer(1, wowError('IllegalArgument', 'bad'));
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => void result.current.execute());
    await waitFor(() => expect(server.requests).toHaveLength(3));
    // B3 changed this: was loading(undefined); the error no longer clears it.
    expect(shown(result.current)).toEqual(loading(1));
  });

  it('succeed: success with the new result', async () => {
    const { result, answer } = await inFlightAfter(1);
    answer(1, json(2));
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(shown(result.current)).toEqual(success(2));
  });

  it('fail: error, and the last result is kept', async () => {
    const { result, answer } = await inFlightAfter(1);
    answer(1, wowError('IllegalArgument', 'bad'));
    await waitFor(() => expect(result.current.status).toBe('error'));
    // B3 changed this (Q2): was failed(undefined).
    expect(shown(result.current)).toEqual(failed(1));
  });

  it('abort() in flight: idle, the request aborted, the last result kept', async () => {
    const { result, server } = await inFlightAfter(1);
    await act(async () => result.current.abort());
    expect(server.requests[1].signal?.aborted).toBe(true);
    // B3 changed this (Q2): was idle with no result.
    expect(shown(result.current)).toEqual({ ...idle, result: 1 });
  });

  it('abort() when settled: idle, the result kept', async () => {
    const { result } = await settledOn(1);
    await act(async () => result.current.abort());
    // B3 changed this (Q2): was idle with no result.
    expect(shown(result.current)).toEqual({ ...idle, result: 1 });
  });

  it('abort() after an error: idle, the error cleared, the last result kept', async () => {
    const { result, answer } = await inFlightAfter(1);
    answer(1, wowError('IllegalArgument', 'bad'));
    await waitFor(() => expect(result.current.status).toBe('error'));
    await act(async () => result.current.abort());
    // B3 changed this (Q2), unmarked in B0: was idle with no result. The error
    // keeps result 1, and abort() keeps it too.
    expect(shown(result.current)).toEqual({ ...idle, result: 1 });
  });

  it('a late response after abort() is dropped', async () => {
    const { result, answer } = await inFlightAfter(1);
    await act(async () => result.current.abort());
    answer(1, json(2));
    await settle();
    // B3 changed this (Q2): was idle with no result; the late 2 is dropped.
    expect(shown(result.current)).toEqual({ ...idle, result: 1 });
  });

  it('reset() when settled: idle, result and error cleared', async () => {
    const { result } = await settledOn(1);
    act(() => result.current.reset());
    expect(shown(result.current)).toEqual(idle);
  });

  it('reset() after an error: idle, result and error cleared', async () => {
    const { result, answer } = await inFlightAfter(1);
    answer(1, wowError('IllegalArgument', 'bad'));
    await waitFor(() => expect(result.current.status).toBe('error'));
    act(() => result.current.reset());
    expect(shown(result.current)).toEqual(idle);
  });

  it('reset() in flight: idle, and the request is aborted', async () => {
    const { result, server } = await inFlightAfter(1);
    act(() => result.current.reset());
    expect(shown(result.current)).toEqual(idle);
    // B3 changed this: was false; reset() left the request running.
    expect(server.requests[1].signal?.aborted).toBe(true);
  });

  it('a late response after reset() is dropped', async () => {
    const { result, answer } = await inFlightAfter(1);
    act(() => result.current.reset());
    answer(1, json(2));
    await settle();
    // B3 changed this: was success(2); the request came back to life.
    expect(shown(result.current)).toEqual(idle);
  });

  it('a late failure after reset() is dropped', async () => {
    const { result, answer } = await inFlightAfter(1);
    act(() => result.current.reset());
    answer(1, wowError('IllegalArgument', 'bad'));
    await settle();
    // B3 changed this: was failed(undefined).
    expect(shown(result.current)).toEqual(idle);
  });

  it('unmount in flight: the request is aborted', async () => {
    const { server, unmount } = await inFlightAfter(1);
    unmount();
    expect(server.requests[1].signal?.aborted).toBe(true);
  });
});

describe('request hook state table: execute that ignores the abort signal', () => {
  /** A request the hook cannot cancel: `execute` drops the controller. */
  async function uncancellable() {
    const held = heldServer();
    const client = new SnapshotQueryClient<unknown>({
      fetcher: held.server.fetcher,
      basePath: 'order',
    });
    const hook = renderHook(() =>
      useCountQuery({
        initialQuery: paidFilter,
        execute: query => client.count(query),
      }),
    );
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    return { ...held, ...hook };
  }

  it('a late response after abort() is dropped', async () => {
    const { result, answer } = await uncancellable();
    await act(async () => result.current.abort());
    answer(0, json(2));
    await settle();
    expect(shown(result.current)).toEqual(idle);
  });

  it('a late response after reset() is dropped', async () => {
    const { result, answer } = await uncancellable();
    act(() => result.current.reset());
    answer(0, json(2));
    await settle();
    // B3 changed this: was success(2).
    expect(shown(result.current)).toEqual(idle);
  });
});
