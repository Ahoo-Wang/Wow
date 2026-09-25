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

import { dequal } from 'dequal';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';
import {
  initialQueryState,
  queryTransition,
  type QueryEvent,
  type QueryState,
} from './queryTransitions.js';

/**
 * `value`, or the one of an earlier render when that one has the same
 * content, so that an object rebuilt on every render keeps one identity.
 */
function useContentStable<T>(value: T): T {
  const [kept, setKept] = useState(value);
  if (kept === value || dequal(kept, value)) return kept;
  setKept(value);
  return value;
}

/** Hands a callback its argument; one that throws never breaks the state. */
async function notify<T>(
  name: string,
  callback: ((value: T) => void | Promise<void>) | undefined,
  value: T,
): Promise<void> {
  try {
    await callback?.(value);
  } catch (thrown) {
    // The state already shows the outcome; the host learns of its broken
    // callback without the hook swallowing it.
    // eslint-disable-next-line no-console
    console.warn(`wow-react: ${name} threw`, thrown);
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

/**
 * The one request state machine every hook runs on.
 *
 * - Latest wins: every run takes a new number and aborts the one before; an
 *   answer that is no longer the latest, arrives after `abort()` or
 *   `reset()`, or arrives after unmount, changes nothing.
 * - A run starts on mount, whenever the query changes by content or through
 *   `setQuery()`, whenever `identity` changes, and when `autoExecute` turns
 *   on; `execute()` starts one by hand.
 * - `abort()`, `reset()` and unmount abort the request in flight.
 * - StrictMode's second mount aborts the first run and starts one more, so
 *   exactly one answer lands.
 *
 * `identity` is what else a run depends on besides the query: the endpoint
 * of a `useFetcher…` hook. With `retainResult` off the runner hands a run's
 * result to `onSuccess` but does not keep it as `result`: the list-stream
 * hooks keep their rows as `items` already. `execute`, `attributes` and the callbacks are read
 * when a run starts or settles, so a change to them never starts one.
 */
export function useQueryRunner<Q, R, E>(
  options: QueryHookOptions<Q, R, E>,
  identity?: string,
  retainResult = true,
): QueryHookReturn<Q, R, E> {
  const autoExecute = options.autoExecute ?? true;
  const query = useContentStable(options.query);
  const [state, setState] = useState<QueryState<R, E>>(() =>
    initialQueryState(
      autoExecute && (options.query ?? options.initialQuery) !== undefined,
    ),
  );
  const latest = useRef(options);
  const current = useRef<Q | undefined>(options.query ?? options.initialQuery);
  const request = useRef<{ id: number; controller?: AbortController }>({
    id: 0,
  });
  const mounted = useRef(false);

  useLayoutEffect(() => {
    latest.current = options;
  });

  /** Applies an event unless run `id` is no longer the one that counts. */
  const settle = useCallback((id: number, event: QueryEvent<R, E>) => {
    if (!mounted.current || request.current.id !== id) return false;
    setState(state => queryTransition(state, event));
    return true;
  }, []);

  /** Stops the run in flight: no answer of it changes anything any more. */
  const cancel = useCallback(() => {
    const { controller } = request.current;
    request.current = { id: request.current.id + 1 };
    controller?.abort();
  }, []);

  const execute = useCallback(async () => {
    const query = current.current;
    if (!mounted.current || query === undefined) return;
    request.current.controller?.abort();
    const controller = new AbortController();
    const id = request.current.id + 1;
    request.current = { id, controller };
    setState(state => queryTransition(state, { type: 'start' }));
    const { execute, attributes } = latest.current;
    try {
      const result = await execute(query, attributes, controller);
      if (controller.signal.aborted) {
        settle(id, { type: 'abort' });
      } else if (
        settle(id, {
          type: 'succeed',
          result: retainResult ? result : undefined,
        })
      ) {
        await notify('onSuccess', latest.current.onSuccess, result);
      }
    } catch (error) {
      if (controller.signal.aborted || isAbortError(error)) {
        settle(id, { type: 'abort' });
      } else if (settle(id, { type: 'fail', error: error as E })) {
        await notify('onError', latest.current.onError, error as E);
      }
    } finally {
      if (request.current.controller === controller)
        request.current = { id: request.current.id };
    }
  }, [settle, retainResult]);

  const abort = useCallback(() => {
    cancel();
    setState(state => queryTransition(state, { type: 'abort' }));
  }, [cancel]);

  const reset = useCallback(() => {
    cancel();
    setState(state => queryTransition(state, { type: 'reset' }));
  }, [cancel]);

  const getQuery = useCallback(() => current.current, []);

  const setQuery = useCallback(
    (query: Q) => {
      current.current = query;
      if (latest.current.autoExecute ?? true) void execute();
    },
    [execute],
  );

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      cancel();
    };
  }, [cancel]);

  const previousQuery = useRef(query);
  useEffect(() => {
    const cleared = query === undefined && previousQuery.current !== undefined;
    previousQuery.current = query;
    if (cleared) {
      // A controlled query set to `undefined` (`id ? singleQuery(…) :
      // undefined`) means nothing to run: stop, keep what was shown.
      current.current = undefined;
      abort();
      return;
    }
    if (query !== undefined) current.current = query;
    if (autoExecute) void execute();
  }, [query, identity, autoExecute, execute, abort]);

  return {
    status: state.status,
    loading: state.status === 'loading',
    result: state.result,
    error: state.error,
    execute,
    abort,
    reset,
    getQuery,
    setQuery,
  };
}
