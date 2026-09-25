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

import { useCallback, useState } from 'react';
import type {
  ListStreamExecutor,
  QueryHookOptions,
  QueryHookReturn,
} from '../types.js';
import { readStreamRows } from './readStreamRows.js';
import { useQueryRunner } from './useQueryRunner.js';

/** The options of a list-stream hook: `execute` opens the stream. */
export type ListStreamOptions<Q, R, E> = Omit<
  QueryHookOptions<Q, R[], E>,
  'execute'
> & { execute: ListStreamExecutor<R, Q> };

/** What a list-stream hook returns: the rows in place of `result`. */
export type ListStreamReturn<Q, R, E> = Omit<
  QueryHookReturn<Q, R[], E>,
  'result'
> & { items: R[]; done: boolean };

/**
 * The list-stream hooks on the request state machine: a run opens the
 * stream and reads it into `items`. Each run starts from no rows; `abort()`
 * and an error keep the rows received; `reset()` empties them. The runner
 * aborts the run's controller whenever the run stops counting, and
 * readStreamRows stops publishing then, so rows of a stale stream never
 * reach `items`.
 */
export function useListStream<Q, R, E>(
  options: ListStreamOptions<Q, R, E>,
  identity?: string,
): ListStreamReturn<Q, R, E> {
  const [items, setItems] = useState<R[]>(() => []);
  const openStream = options.execute;
  const { status, loading, error, execute, abort, reset, getQuery, setQuery } =
    useQueryRunner<Q, R[], E>(
      {
        ...options,
        execute: async (query, attributes, abortController) => {
          setItems([]);
          const stream = await openStream(query, attributes, abortController);
          return readStreamRows(stream, abortController.signal, setItems);
        },
      },
      identity,
    );
  const resetRows = useCallback(() => {
    reset();
    setItems([]);
  }, [reset]);
  return {
    items,
    done: status === 'success',
    loading,
    error,
    status,
    execute,
    abort,
    reset: resetRows,
    getQuery,
    setQuery,
  };
}
