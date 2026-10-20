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
  toJsonServerSentEventStream,
  toServerSentEventStream,
} from '@ahoo-wang/fetcher-eventstream';
import type { CommandResult } from './wire.js';
import { isProblem, WowProblemError } from './problem.js';

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

/** The data of each event of `response`'s server-sent event stream. */
export function commandResults<R>(response: Response): CommandResultStream<R> {
  return toJsonServerSentEventStream<CommandResult<R>>(
    toServerSentEventStream(response),
  ).pipeThrough(
    new TransformStream<
      JsonServerSentEvent<CommandResult<R>>,
      CommandResult<R>
    >({
      transform(event, controller) {
        controller.enqueue(event.data);
      },
    }),
  );
}

/**
 * Why a resumable streamed list ended (decision D253): `complete` once every
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

/** One record of a streamed list, with its position. */
export interface StreamedRow<T> {
  /** The record. */
  readonly record: T;
  /**
   * The record's position, its event's `id`: an opaque cursor token to
   * resume after it ({@link StreamResumeOptions.lastEventId}). Absent from a
   * list whose sort cannot order a cursor, which cannot be resumed.
   */
  readonly position?: string;
}

/**
 * How a streamed list resumes (decision D253). A stream the server ends with
 * a resumable `end`, one cut off before its `end`, and one that fails with a
 * retryable problem (a 429 after its `Retry-After`) are asked for again with
 * `Last-Event-ID`, the position of the last record read, and go on strictly
 * after it.
 */
export interface StreamResumeOptions {
  /** Starts after this position: a {@link StreamedRow.position} read before. */
  readonly lastEventId?: string;
  /**
   * How many times in a row the stream may be resumed without a record in
   * between before it fails with what stopped it; 5 by default, 0 never
   * resumes.
   */
  readonly maxRetries?: number;
  /** The wait before the first such retry, doubled for each next; 200 ms by default. */
  readonly initialDelayMs?: number;
  /** The longest wait between retries; 10 s by default. */
  readonly maxDelayMs?: number;
}

const DEFAULT_MAX_RETRIES = 5;
const DEFAULT_INITIAL_DELAY_MS = 200;
const DEFAULT_MAX_DELAY_MS = 10_000;
const HOLD_EXPIRED: StreamEndReason = 'hold-expired';

/** Waits `ms`, or rejects once `signal` aborts; the caller checked it was not aborted yet. */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', abort, { once: true });
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
 * The records of a resumable streamed list (decision D253), read from
 * `response` and, each time the stream is to be resumed, from what `reopen`
 * answers for the position of the last record read (none before the first),
 * with how many records were read so far: `undefined` when no more are
 * wanted. Each record comes with its position. An `error` event that is not
 * retryable, a failure `reopen` cannot get past, and an abort of `signal`
 * error the stream.
 *
 * @param response - The first response
 * @param reopen - Asks for the stream again after a position
 * @param options - How the stream resumes, and what cancels it
 */
export function resumableQueryRows<T>(
  response: Response,
  reopen: (
    lastEventId: string | undefined,
    received: number,
  ) => Promise<Response | undefined>,
  options: StreamResumeOptions & { readonly signal?: AbortSignal } = {},
): ReadableStream<StreamedRow<T>> {
  const maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
  const initialDelayMs = options.initialDelayMs ?? DEFAULT_INITIAL_DELAY_MS;
  const maxDelayMs = options.maxDelayMs ?? DEFAULT_MAX_DELAY_MS;
  const signal = options.signal;
  const events = (from: Response) =>
    toJsonServerSentEventStream<unknown>(
      toServerSentEventStream(from),
    ).getReader();
  let reader = events(response);
  let lastEventId = options.lastEventId;
  let received = 0;
  let failures = 0;
  // Nothing was read yet, or the records read have positions.
  const resumable = () => lastEventId !== undefined || received === 0;

  /** Asks for the stream again, or throws `cause` once out of retries. */
  async function resume(cause: unknown, delayMs?: number): Promise<boolean> {
    let wait = delayMs;
    let last = cause;
    for (;;) {
      if (signal?.aborted) throw signal.reason;
      if (failures >= maxRetries) throw last;
      failures++;
      await sleep(
        wait ?? Math.min(maxDelayMs, initialDelayMs * 2 ** (failures - 1)),
        signal,
      );
      wait = undefined;
      try {
        const next = await reopen(lastEventId, received);
        if (next === undefined) return false;
        reader = events(next);
        return true;
      } catch (error) {
        if (signal?.aborted) throw error;
        if (error instanceof WowProblemError) {
          if (!error.retry.retryable) throw error;
          wait = error.retry.afterMs;
        }
        last = error;
      }
    }
  }

  return new ReadableStream<StreamedRow<T>>({
    async pull(controller) {
      for (;;) {
        let next: ReadableStreamReadResult<JsonServerSentEvent<unknown>>;
        try {
          next = await reader.read();
        } catch (error) {
          if (signal?.aborted || !resumable()) throw error;
          if (await resume(error)) continue;
          controller.close();
          return;
        }
        if (next.done) {
          // A resumable stream always ends with `end`: one without was cut off.
          if (
            lastEventId !== undefined &&
            (await resume(new TypeError('The query stream was cut off.')))
          ) {
            continue;
          }
          controller.close();
          return;
        }
        const event = next.value;
        if (event.event === ROW_EVENT || event.event === UNNAMED_EVENT) {
          if (event.id) lastEventId = event.id;
          received++;
          failures = 0;
          controller.enqueue({
            record: event.data as T,
            ...(event.id ? { position: event.id } : {}),
          });
          return;
        }
        if (event.event === END_EVENT) {
          const end = event.data as StreamEnd;
          const delay = end.reason === HOLD_EXPIRED ? 0 : undefined;
          if (
            end.resumable &&
            resumable() &&
            (await resume(
              new TypeError(`The query stream ended: ${end.reason}.`),
              delay,
            ))
          ) {
            continue;
          }
          controller.close();
          return;
        }
        if (event.event === ERROR_EVENT) {
          const error = problemOf(event.data);
          if (
            error.retry.retryable &&
            resumable() &&
            (await resume(error, error.retry.afterMs))
          ) {
            continue;
          }
          throw error;
        }
      }
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}
