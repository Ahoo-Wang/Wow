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
 * Characterization of when `onSuccess` and `onError` run, and what they see.
 * It pins the behaviour of today, fetcher-react 5.1.3 underneath, for the
 * refactor of `docs/design/refactor-2026-09.md`.
 *
 * Cells that batch B3 (#PRNUM) changed on purpose carry a `B3 changed this`
 * comment naming the value before.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ExchangeError } from '@ahoo-wang/fetcher';
import {
  filter,
  listQuery,
  SnapshotQueryClient,
  WowError,
} from '@ahoo-wang/wow-client';
import {
  useCountQuery,
  useFetcherCountQuery,
  useFetcherListStreamQuery,
  useListStreamQuery,
} from '../src';
import {
  deferred,
  fakeServer,
  json,
  liveStream,
  sse,
  wowError,
  type FakeServer,
  type LiveStream,
} from './support/fakeServer';

const paidFilter = filter.eq('state.status', 'PAID');
const shippedFilter = filter.eq('state.status', 'SHIPPED');

interface Callbacks<R> {
  onSuccess?: (result: R) => void | Promise<void>;
  onError?: (error: unknown) => void | Promise<void>;
}

interface Hook {
  status: string;
  execute(): Promise<void>;
  abort(): void;
  reset(): void;
  setQuery(query: typeof paidFilter): void;
}

/** Lets late responses, extractors and batched updates run. */
function settle() {
  return act(() => new Promise(resolve => setTimeout(resolve, 10)));
}

/** A server whose every request waits until the test answers it. */
function heldServer() {
  const answers: ReturnType<typeof deferred<Response>>[] = [];
  const server = fakeServer(() => {
    const answer = deferred<Response>();
    answers.push(answer);
    return answer.promise;
  });
  return {
    server,
    answer(index: number, response: Response) {
      answers[index].resolve(response);
    },
  };
}

let warn: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  warn.mockRestore();
});

const requestFamilies: {
  name: string;
  render(
    server: FakeServer,
    callbacks: Callbacks<number>,
  ): {
    result: { current: Hook };
    rerender(callbacks: Callbacks<number>): void;
  };
}[] = [
  {
    name: 'useCountQuery (query client as execute)',
    render(server, callbacks) {
      const client = new SnapshotQueryClient<unknown>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(
        ({ onSuccess, onError }: Callbacks<number>) =>
          useCountQuery({
            initialQuery: paidFilter,
            execute: (query, attributes, abortController) =>
              client.count(query, attributes, abortController),
            onSuccess,
            onError,
          }),
        { initialProps: callbacks },
      );
    },
  },
  {
    name: 'useFetcherCountQuery (url)',
    render(server, callbacks) {
      return renderHook(
        ({ onSuccess, onError }: Callbacks<number>) =>
          useFetcherCountQuery({
            fetcher: server.fetcher,
            url: 'order/snapshot/count',
            initialQuery: paidFilter,
            onSuccess,
            onError,
          }),
        { initialProps: callbacks },
      );
    },
  },
];

describe.each(requestFamilies)('callbacks of $name', family => {
  it('onSuccess runs once with the result, before the render that shows success', async () => {
    const held = heldServer();
    const seen: unknown[] = [];
    const hook = family.render(held.server, {
      onSuccess: result => {
        seen.push([result, hook.result.current.status]);
      },
    });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    held.answer(0, json(1));
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    expect(seen).toEqual([[1, 'loading']]);
  });

  it('onError runs once with the error, before the render that shows it', async () => {
    const held = heldServer();
    const seen: unknown[] = [];
    const hook = family.render(held.server, {
      onError: error => {
        seen.push([error, hook.result.current.status]);
      },
    });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    held.answer(0, wowError('IllegalArgument', 'bad'));
    await waitFor(() => expect(hook.result.current.status).toBe('error'));
    expect(seen).toEqual([[expect.any(ExchangeError), 'loading']]);
  });

  it('execute() waits for an async onSuccess', async () => {
    const server = fakeServer(() => json(1));
    const gate = deferred<void>();
    const hook = family.render(server, { onSuccess: () => gate.promise });
    await waitFor(() => expect(server.requests).toHaveLength(1));
    let resolved = false;
    act(() => {
      void hook.result.current.execute().then(() => {
        resolved = true;
      });
    });
    await settle();
    expect(resolved).toBe(false);
    gate.resolve();
    await settle();
    expect(resolved).toBe(true);
  });

  it('execute() resolves, not rejects, when the request fails', async () => {
    const server = fakeServer(() => wowError('IllegalArgument', 'bad'));
    const onError = vi.fn();
    const hook = family.render(server, { onError });
    await waitFor(() => expect(hook.result.current.status).toBe('error'));
    await act(() =>
      expect(hook.result.current.execute()).resolves.toBe(undefined),
    );
    expect(onError).toHaveBeenCalledTimes(2);
  });

  it('an onSuccess that throws is logged; the hook still shows success', async () => {
    const server = fakeServer(() => json(1));
    const thrown = new Error('onSuccess failed');
    const hook = family.render(server, {
      onSuccess: () => {
        throw thrown;
      },
    });
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    expect(warn).toHaveBeenCalledWith(expect.any(String), thrown);
  });

  it('an onError that throws is logged; the hook still shows the error', async () => {
    const server = fakeServer(() => wowError('IllegalArgument', 'bad'));
    const thrown = new Error('onError failed');
    const hook = family.render(server, {
      onError: () => {
        throw thrown;
      },
    });
    await waitFor(() => expect(hook.result.current.status).toBe('error'));
    expect(warn).toHaveBeenCalledWith(expect.any(String), thrown);
  });

  it('the callback of the latest render runs', async () => {
    const held = heldServer();
    const first = vi.fn();
    const latest = vi.fn();
    const hook = family.render(held.server, { onSuccess: first });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    hook.rerender({ onSuccess: latest });
    held.answer(0, json(1));
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    expect(first).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledWith(1);
  });

  it('neither runs for an aborted request', async () => {
    const held = heldServer();
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const hook = family.render(held.server, { onSuccess, onError });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    await act(async () => hook.result.current.abort());
    held.answer(0, json(1));
    await settle();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  it('neither runs for a request a newer query superseded', async () => {
    const held = heldServer();
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const hook = family.render(held.server, { onSuccess, onError });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    act(() => hook.result.current.setQuery(shippedFilter));
    await waitFor(() => expect(held.server.requests).toHaveLength(2));
    held.answer(0, json(1));
    held.answer(1, json(2));
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    await settle();
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onSuccess).toHaveBeenCalledWith(2);
    expect(onError).not.toHaveBeenCalled();
  });

  it('onSuccess does not run for a response that arrives after reset()', async () => {
    const held = heldServer();
    const onSuccess = vi.fn();
    const hook = family.render(held.server, { onSuccess });
    await waitFor(() => expect(held.server.requests).toHaveLength(1));
    act(() => hook.result.current.reset());
    held.answer(0, json(1));
    await settle();
    // B3 changed this: onSuccess ran with 1.
    expect(onSuccess).not.toHaveBeenCalled();
  });
});

const streamFamilies: {
  name: string;
  render(
    server: FakeServer,
    callbacks: Callbacks<unknown[]>,
  ): { result: { current: Omit<Hook, 'setQuery'> } };
}[] = [
  {
    name: 'useListStreamQuery (query client as execute)',
    render(server, { onSuccess, onError }) {
      const client = new SnapshotQueryClient<unknown>({
        fetcher: server.fetcher,
        basePath: 'order',
      });
      return renderHook(() =>
        useListStreamQuery<unknown>({
          initialQuery: listQuery({ filter: paidFilter }),
          execute: (query, attributes, abortController) =>
            client.listStateStream(query, attributes, abortController),
          onSuccess,
          onError,
        }),
      );
    },
  },
  {
    name: 'useFetcherListStreamQuery (url)',
    render(server, { onSuccess, onError }) {
      return renderHook(() =>
        useFetcherListStreamQuery<unknown>({
          fetcher: server.fetcher,
          url: 'order/snapshot/list/state',
          initialQuery: listQuery({ filter: paidFilter }),
          onSuccess,
          onError,
        }),
      );
    },
  },
];

describe.each(streamFamilies)('callbacks of $name', family => {
  it('onSuccess runs once, with every row, when the stream ends', async () => {
    let stream!: LiveStream;
    const server = fakeServer(() => {
      stream = liveStream();
      return stream.response;
    });
    const onSuccess = vi.fn();
    const hook = family.render(server, { onSuccess });
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: 1 });
    await settle();
    stream.send({ data: 2 });
    await settle();
    expect(onSuccess).not.toHaveBeenCalled();
    stream.close();
    await waitFor(() => expect(hook.result.current.status).toBe('success'));
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onSuccess).toHaveBeenCalledWith([1, 2]);
  });

  it('onError runs once with the WowError of an error event', async () => {
    const server = fakeServer(() =>
      sse(
        { data: 1 },
        {
          event: 'IllegalArgument',
          data: { errorCode: 'IllegalArgument', errorMsg: 'bad' },
        },
      ),
    );
    const onError = vi.fn();
    const hook = family.render(server, { onError });
    await waitFor(() => expect(hook.result.current.status).toBe('error'));
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(expect.any(WowError));
  });

  it('neither runs when the stream is aborted', async () => {
    const stream = liveStream();
    const server = fakeServer(() => stream.response);
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const hook = family.render(server, { onSuccess, onError });
    await waitFor(() => expect(server.requests).toHaveLength(1));
    stream.send({ data: 1 });
    await settle();
    await act(async () => hook.result.current.abort());
    await settle();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });
});
