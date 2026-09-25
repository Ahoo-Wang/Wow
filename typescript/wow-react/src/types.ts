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
 * Runs one query: a query client method such as `pagedState`, or any function
 * that resolves to the result.
 *
 * wow-client's clients, generated ones included, bind their methods, so
 * `execute: client.pagedState` works as is. A method of an object of your own
 * loses its `this` when handed over: pass `method.bind(object)` or an arrow
 * function instead.
 *
 * It receives the query, the hook's `attributes` option, and the
 * `AbortController` of this run. Hand the controller on to the request, so
 * that a newer query, `abort()`, `reset()` or an unmount cancels it; every
 * wow-client query method takes it as its last parameter.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 */
export type QueryExecutor<Q, R> = (
  query: Q,
  attributes: Record<string, unknown> | undefined,
  abortController: AbortController,
) => Promise<R>;

/**
 * Opens the event stream of one query: a query client's `listStream` or
 * `listStateStream`, or any function that resolves to a stream of rows. It
 * receives what a {@link QueryExecutor} receives.
 *
 * @template R - One row of the stream
 * @template Q - The query
 */
export type ListStreamExecutor<R, Q> = QueryExecutor<Q, ReadableStream<R>>;

/**
 * The options every query hook takes. Each `Use…QueryOptions` extends it with
 * its own query and result types; the `useFetcher…` hooks take `url` and
 * `fetcher` in place of `execute`.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 * @template E - The error the hook reports, `Error` by default
 */
export interface QueryHookOptions<Q, R, E = Error> {
  /**
   * The query, controlled: the hook runs it again whenever it changes by
   * content, so a new object with the same content does not.
   */
  query?: Q;
  /** The first query, uncontrolled: change it later with `setQuery()`. */
  initialQuery?: Q;
  /**
   * Whether the query runs on mount and whenever it changes; `true` by
   * default. With `false` it runs only through `execute()`.
   */
  autoExecute?: boolean;
  /** Handed to `execute` with every run; not part of what identifies a run. */
  attributes?: Record<string, unknown>;
  /** Runs one query. */
  execute: QueryExecutor<Q, R>;
  /** Called with the result of each run that succeeds. */
  onSuccess?: (result: R) => void | Promise<void>;
  /** Called with the error of each run that fails. */
  onError?: (error: E) => void | Promise<void>;
}

/**
 * What every query hook returns. Each `Use…QueryReturn` extends it with its own
 * query and result types; the list-stream hooks return `items` and `done` in
 * place of `result`.
 *
 * @template Q - The query
 * @template R - What the query resolves to
 * @template E - The error the hook reports, `Error` by default
 */
export interface QueryHookReturn<Q, R, E = Error> {
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
  /** Why the latest run failed, or `undefined`. */
  error: E | undefined;
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
