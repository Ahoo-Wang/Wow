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
import type { FilterListQuery } from '@ahoo-wang/wow-client/dsl';
import type {
  UseListStreamQueryOptions,
  UseListStreamQueryReturn,
} from '../hooks/useListStreamQuery.js';
import { readStreamRows } from './readStreamRows.js';
import { useQueryRunner } from './useQueryRunner.js';

/**
 * The list-stream hooks on the request state machine: a run opens the
 * stream and reads it into `items`. Each run starts from no rows; `abort()`
 * and an error keep the rows received; `reset()` empties them. The runner
 * aborts the run's signal whenever the run stops counting, and
 * readStreamRows stops publishing then, so rows of a stale stream never
 * reach `items`.
 */
export function useListStream<
  R,
  FIELDS extends string,
  E,
  Q extends FilterListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
  identity?: string,
): UseListStreamQueryReturn<R, FIELDS, E, Q> {
  const [items, setItems] = useState<R[]>(() => []);
  const openStream = options.execute;
  const { status, loading, error, execute, abort, reset, getQuery, setQuery } =
    useQueryRunner<Q, R[], E>(
      {
        ...options,
        execute: async (query, options) => {
          setItems([]);
          const stream = await openStream(query, options);
          return readStreamRows(stream, options.signal, setItems);
        },
      },
      identity,
      false,
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
