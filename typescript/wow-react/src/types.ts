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

import type {
  RequestOptions,
  Positioned,
  StreamOptions,
  WowProblemError,
} from '@ahoo-wang/wow-client';

/**
 * Where a query hook stands:
 *
 * - `idle` — nothing has run yet, or `abort()` or `reset()` stopped it;
 * - `loading` — a request is in flight, or is about to be: a hook that runs
 *   its query on mount already renders its first frame, on the server too,
 *   as `loading`;
 * - `success` — the latest request answered, and `result` holds its answer;
 * - `error` — the latest request failed, and `error` says why.
 *
 * A plain string union, so a literal such as `'success'` can be written
 * wherever a status is expected: a component's props, a test, a story.
 */
export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';

/**
 * Runs one query: a query client's method, such as a generated
 * `…SnapshotQueryClient`'s `paged`, or any function that resolves to the
 * result. Hand it over as an arrow function,
 * `(query, options) => client.paged(query, options)`: a method loses its
 * `this` when handed over alone.
 *
 * It receives the query and the request options of this run: the hook's
 * `requestOptions`, with the `signal` of this run. Hand the options on to the
 * request, so that a newer query, `abort()`, `reset()` or an unmount cancels
 * it; every wow-client query method takes them as its last parameter.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 * @template O - The request options the hook hands on
 */
export type QueryExecutor<Q, R, O extends RequestOptions = RequestOptions> = (
  query: Q,
  options: O & { readonly signal: AbortSignal },
) => Promise<R>;

/**
 * Opens the event stream of one query: a query client's `streamRows`, which
 * answers each record with its position, so the hook can say where the
 * stream stopped. It receives the query and the stream options of this run,
 * `resume` included, with the run's `signal`.
 *
 * @template R - One record of the stream
 * @template Q - The query
 */
export type StreamExecutor<R, Q> = QueryExecutor<
  Q,
  ReadableStream<Positioned<R>>,
  StreamOptions
>;

/**
 * The options every query hook takes. Each `Use…QueryOptions` extends it with
 * its own query and result types.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 * @template O - The request options the hook hands on
 */
export interface QueryHookOptions<
  Q,
  R,
  O extends RequestOptions = RequestOptions,
> {
  /**
   * The query, controlled: the hook runs it again whenever it changes by
   * content, so a new object with the same content does not. Set to
   * `undefined` after a query (`id ? singleQuery(…) : undefined`), it aborts
   * the request in flight and goes `idle`, keeping the last result, as
   * `abort()` does; nothing runs until a query is given again.
   */
  query?: Q;
  /** The first query, uncontrolled: change it later with `setQuery()`. */
  initialQuery?: Q;
  /**
   * Whether the query runs on mount and whenever it changes; `true` by
   * default. With `false` it runs only through `execute()`.
   */
  autoExecute?: boolean;
  /**
   * Handed to `execute` with every run, with the run's `signal`: the scope,
   * the space or more headers of the request. Part of what identifies a run,
   * as the query is: a change by content runs the query again, since the
   * scope, the space and the headers can change the answer. A header whose
   * value changes on every render, such as a timestamp, therefore restarts
   * the run on every render; keep such values stable.
   */
  requestOptions?: Omit<O, 'signal'>;
  /** Runs one query. */
  execute: QueryExecutor<Q, R, O>;
  /** Called with the result of each run that succeeds. */
  onSuccess?: (result: R) => void | Promise<void>;
  /** Called with the error of each run that fails, the hook's `error`. */
  onError?: (error: Error) => void | Promise<void>;
}

/**
 * What every query hook returns. Each `Use…QueryReturn` extends it with its own
 * query and result types; the list-stream and cursor hooks return `items` in
 * place of `result`.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 */
export interface QueryHookReturn<Q, R> {
  /** Where the hook stands; see {@link QueryStatus}. */
  status: QueryStatus;
  /** Whether a request is in flight: the same as `status === 'loading'`. */
  loading: boolean;
  /**
   * The result of the latest successful run, or `undefined`. A failed run,
   * `abort()` and a new run keep it until a new result arrives; only
   * `reset()` clears it.
   */
  result: R | undefined;
  /**
   * Why the latest run failed, or `undefined`. A value thrown that is not an
   * `Error` arrives wrapped, as `new Error(String(thrown), { cause: thrown })`.
   */
  error: Error | undefined;
  /**
   * The problem a Wow service answered the latest run with: `error` when it
   * is a `WowProblemError`, with its `code`, `status` and retry hint;
   * `undefined` for a failure no service answered, such as a network error.
   */
  problem: WowProblemError | undefined;
  /** Runs the current query again, aborting the request in flight. */
  execute: () => Promise<void>;
  /**
   * Aborts the request in flight and returns to `idle`, keeping `result`;
   * a late answer to that request is dropped.
   */
  abort: () => void;
  /**
   * Aborts the request in flight, returns to `idle`, and clears `result` and
   * `error`; a late answer to that request is dropped.
   */
  reset: () => void;
  /** The current query. */
  getQuery: () => Q | undefined;
  /** Replaces the query; it runs when `autoExecute` is on. */
  setQuery: (query: Q) => void;
}
