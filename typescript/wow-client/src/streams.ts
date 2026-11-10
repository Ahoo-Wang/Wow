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

import {
  type JsonServerSentEvent,
  type ServerSentEvent,
  toServerSentEventStream,
} from '@ahoo-wang/fetcher-eventstream';
import { parseJson } from './exactJson.js';
import type { Answer } from './http.js';
import type { CommandResult } from './wire.js';
import { DEFAULT_RETRY } from './frameworkErrors.js';
import { isProblem, rememberRequestId, WowProblemError } from './problem.js';

/** The event of each record of a streamed query list. */
const ROW_EVENT = 'row';

/** The event that ends a streamed query list that failed: the problem. */
const ERROR_EVENT = 'error';

/** The event that ends a resumable streamed query list (decision D253). */
const END_EVENT = 'end';

/** The name an event without one is read under. */
const UNNAMED_EVENT = 'message';

/**
 * The results of a command's event stream (`Accept: text/event-stream`), one
 * per hop, each event named by its stage. A rejection or a failure after
 * admission is the stream's last result, not an error.
 */
export type CommandResultStream<R = Record<string, unknown>> = ReadableStream<
  CommandResult<R>
>;

/**
 * The events of `answer`'s server-sent event stream, each event's data read
 * as JSON: with the answer's exact numbers for an event `marked` names.
 */
function eventsOf(
  answer: Answer,
  marked: (event: string | undefined) => boolean,
): ReadableStreamDefaultReader<JsonServerSentEvent<unknown>> {
  return toServerSentEventStream(answer.response)
    .pipeThrough(
      new TransformStream<ServerSentEvent, JsonServerSentEvent<unknown>>({
        transform(event, controller) {
          controller.enqueue({
            ...event,
            data: parseJson(
              event.data,
              marked(event.event) ? answer.numbers : undefined,
            ),
          });
        },
      }),
    )
    .getReader();
}

/**
 * The data of each event of `answer`'s server-sent event stream, the
 * results of the command sent with `requestId`. A read that fails, such as a
 * connection lost after the response began, fails with an error that carries
 * `requestId` (`requestIdOf`): the command may have been committed, and
 * a retry with the same id is answered as its duplicate.
 */
export function commandResults<R>(
  answer: Answer,
  requestId: string,
): CommandResultStream<R> {
  const reader = eventsOf(answer, () => true);
  return new ReadableStream<CommandResult<R>>({
    async pull(controller) {
      let next: ReadableStreamReadResult<JsonServerSentEvent<unknown>>;
      try {
        next = await reader.read();
      } catch (error) {
        rememberRequestId(error, requestId);
        throw error;
      }
      if (next.done) controller.close();
      else controller.enqueue(next.value.data as CommandResult<R>);
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}

/**
 * Why a resumable streamed list ended: `complete` once every
 * record was sent; the others are the server's, and resumable: the stream ran
 * for its hold time, its store had no query permit for its next chunk, or
 * the node is stopping.
 */
export type StreamEndReason =
  'complete' | 'hold-expired' | 'permit-reclaimed' | 'node-stopping';

/** The data of the `end` event that ends a resumable streamed list. */
export interface StreamEnd {
  /** Why it ended. */
  readonly reason: StreamEndReason | (string & {});
  /** Whether asking again with `Last-Event-ID` goes on where it ended. */
  readonly resumable: boolean;
}

/** One row of a streamed list, with its position. Kotlin's `Positioned`. */
export interface Positioned<T> {
  /** The row. */
  readonly row: T;
  /**
   * The record's position, its event's `id`: an opaque cursor token to
   * resume after it ({@link StreamResumeOptions.lastEventId}). Absent from a
   * list whose sort cannot order a cursor, which cannot be resumed.
   */
  readonly position?: string;
}

/**
 * How a streamed list resumes. A stream the server ends with
 * a resumable `end`, one cut off before its `end`, and one that fails with a
 * retryable problem (a 429 after its `Retry-After`) are asked for again with
 * `Last-Event-ID`, the position of the last record read, and go on strictly
 * after it.
 */
export interface StreamResumeOptions {
  /** Starts after this position: a {@link Positioned.position} read before. */
  readonly lastEventId?: string;
  /**
   * How many times in a row the stream may be resumed without a record in
   * between before it fails with what stopped it; by default one fewer than
   * the catalog's attempts ({@link DEFAULT_RETRY}, as the Kotlin client), 0
   * never resumes.
   */
  readonly maxRetries?: number;
  /**
   * The wait before the first such retry, doubled for each next; the
   * catalog's backoff by default ({@link DEFAULT_RETRY}).
   */
  readonly initialDelayMs?: number;
  /** The longest wait between retries; the catalog's by default ({@link DEFAULT_RETRY}). */
  readonly maxDelayMs?: number;
}

const HOLD_EXPIRED: StreamEndReason = 'hold-expired';
const COMPLETE: StreamEndReason = 'complete';

/** Waits `ms`, or rejects once `signal` aborts. */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', abort);
  });
}

function problemOf(data: unknown): WowProblemError {
  return new WowProblemError(
    isProblem(data)
      ? data
      : { type: 'about:blank', title: 'Query failed', status: 500 },
  );
}

/**
 * The records of a resumable streamed list, read from
 * `response` and, each time the stream is to be resumed, from what `reopen`
 * answers for the position of the last record read (none before the first),
 * with how many records were read so far: `undefined` when no more are
 * wanted. Each record comes with its position. An `error` event that is not
 * retryable, a failure `reopen` cannot get past, and an abort of `signal`
 * error the stream. The signal `reopen` is given aborts once `signal` does or
 * the stream's reader cancels it: a cancel during a wait to resume, or while
 * the stream is asked for again, sends nothing more and leaves no response
 * open.
 *
 * @param response - The first response
 * @param reopen - Asks for the stream again after a position
 * @param options - How the stream resumes, and what cancels it
 */
export function resumableQueryRows<T>(
  answer: Answer,
  reopen: (
    lastEventId: string | undefined,
    received: number,
    signal: AbortSignal,
  ) => Promise<Answer | undefined>,
  options: StreamResumeOptions & { readonly signal?: AbortSignal } = {},
): ReadableStream<Positioned<T>> {
  const {
    maxRetries = DEFAULT_RETRY.attempts - 1,
    initialDelayMs = DEFAULT_RETRY.backoffMs,
    maxDelayMs = DEFAULT_RETRY.maxWaitMs,
    signal,
  } = options;
  // A record's exact numbers are its answer's (review R4-Q, Q-9).
  const events = (from: Answer) =>
    eventsOf(from, event => event === ROW_EVENT || event === UNNAMED_EVENT);
  let reader = events(answer);
  let lastEventId = options.lastEventId;
  let received = 0;
  let failures = 0;
  // Aborted once the reader cancels: a read in flight then ends, and must not
  // be taken for a cut-off stream to ask for again. `halted` aborts with it or
  // with `signal`, and a wait to resume, or a request to, ends with `halted`
  // (`AbortSignal.any`: Node 20.3, Chrome 116, Firefox 124, Safari 17.4, all
  // within the runtimes the compatibility page states).
  const cancelling = new AbortController();
  const halted =
    signal === undefined
      ? cancelling.signal
      : AbortSignal.any([signal, cancelling.signal]);

  /** Asks for the stream again, or throws `cause` once out of retries. */
  async function resume(cause: unknown, delayMs?: number): Promise<boolean> {
    for (;;) {
      if (halted.aborted) throw halted.reason;
      if (failures++ >= maxRetries) throw cause;
      const wait =
        delayMs ?? Math.min(maxDelayMs, initialDelayMs * 2 ** (failures - 1));
      if (wait > 0) await sleep(wait, halted);
      try {
        const next = await reopen(lastEventId, received, halted);
        if (halted.aborted) {
          // Answered as it was cancelled: nothing reads this response.
          await next?.response.body?.cancel(halted.reason);
          throw halted.reason;
        }
        if (next) reader = events(next);
        return next !== undefined;
      } catch (error) {
        if (
          halted.aborted ||
          (error instanceof WowProblemError && error.retry === 'none')
        ) {
          throw error;
        }
        delayMs = (error as WowProblemError).retryAfterMs;
        cause = error;
      }
    }
  }

  return new ReadableStream<Positioned<T>>({
    async pull(controller) {
      for (;;) {
        let stop: unknown;
        let delayMs: number | undefined;
        try {
          const next = await reader.read();
          if (next.done) {
            // Every stream ends with `end`: one without was cut off.
            stop = new TypeError('The query stream was cut off.');
          } else {
            const { event, id, data } = next.value;
            if (event === ROW_EVENT || event === UNNAMED_EVENT) {
              if (id) lastEventId = id;
              received++;
              failures = 0;
              controller.enqueue(
                id ? { row: data as T, position: id } : { row: data as T },
              );
              return;
            }
            if (event === END_EVENT) {
              const end = data as StreamEnd;
              if (end.reason === COMPLETE) {
                controller.close();
                return;
              }
              stop = new TypeError(`The query stream ended: ${end.reason}.`);
              if (!end.resumable) throw stop;
              if (end.reason === HOLD_EXPIRED) delayMs = 0;
            } else if (event === ERROR_EVENT) {
              const error = problemOf(data);
              if (error.retry === 'none') throw error;
              stop = error;
              delayMs = error.retryAfterMs;
            } else {
              continue;
            }
          }
        } catch (error) {
          if (error === stop || error instanceof WowProblemError) throw error;
          stop = error;
        }
        if (cancelling.signal.aborted) return;
        // Only a stream read from the start, or whose records have positions, can be asked for again.
        if (signal?.aborted) throw signal.reason;
        if (lastEventId === undefined && received > 0) throw stop;
        if (!(await resume(stop, delayMs))) {
          controller.close();
          return;
        }
      }
    },
    cancel(reason) {
      cancelling.abort(reason);
      return reader.cancel(reason);
    },
  });
}
