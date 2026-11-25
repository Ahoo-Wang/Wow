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
import { type AnswerBound, unknownOutcome } from './answerBound.js';
import type { Answer } from './http.js';
import { type CommandResult, Stage } from './wire.js';
import {
  DEFAULT_RETRY,
  FrameworkErrorCodes,
  RAISED_PROBLEMS,
} from './frameworkErrors.js';
import {
  fromGateway,
  isProblem,
  PROBLEM_TYPE_PREFIX,
  rememberRequestId,
  WowProblemError,
} from './problem.js';

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
 * admission is the stream's last result, not an error. The server ends a
 * whole stream with an `end` event after the last result, which is no result:
 * the stream closes on it. An event this client does not know, which a newer
 * server may send, is skipped; a result of a stage it does not know is still
 * a result (decision D484). One that ends without `end`, however cleanly, one
 * whose `end` gives a reason other than `complete`, one whose connection is
 * lost, one that sends data that is no JSON, and one that outlasts its wait and
 * a grace (`CommandClient.stream`) error with a `wow.command.unknown`
 * {@link WowProblemError}, the command's outcome unknown and a retry with its
 * request id safe.
 */
export type CommandResultStream<R = Record<string, unknown>> = ReadableStream<
  CommandResult<R>
>;

/** The stages this client knows, whose events are a command's results. */
const RESULT_EVENTS: ReadonlySet<string> = new Set(Object.values(Stage));

/**
 * Whether `event` of a command's stream is a result: named by a stage this
 * client knows, or by the stage of the result it carries, one a newer server
 * added (a hop's stage is open, decision D484). Any other event is one a
 * newer server added, which this client skips.
 */
function isResult({ event, data }: JsonServerSentEvent<unknown>): boolean {
  return (
    isStage(event) ||
    (event !== undefined &&
      (data as { hop?: { stage?: unknown } } | null)?.hop?.stage === event)
  );
}

/** Whether `event` is named by a stage this client knows. */
const isStage = (event: string | undefined) => RESULT_EVENTS.has(event!);

/**
 * The events of `answer`'s server-sent event stream, each event's data read
 * as JSON: with the answer's exact numbers for an event `marked` names. An
 * event whose data is no JSON fails the read when its reader `knows` it, and
 * is left out otherwise: a newer server's event, which its reader skips
 * whatever it holds.
 */
function eventsOf(
  answer: Answer,
  marked: (event: string | undefined) => boolean,
  knows: (event: string | undefined) => boolean,
): ReadableStreamDefaultReader<JsonServerSentEvent<unknown>> {
  return toServerSentEventStream(answer.response)
    .pipeThrough(
      new TransformStream<ServerSentEvent, JsonServerSentEvent<unknown>>({
        transform(event, controller) {
          let data: unknown;
          try {
            data = parseJson(
              event.data,
              marked(event.event) ? answer.numbers : undefined,
            );
          } catch (error) {
            if (knows(event.event)) throw error;
            return;
          }
          controller.enqueue({ ...event, data });
        },
      }),
    )
    .getReader();
}

/**
 * The data of each event of `answer`'s server-sent event stream, the
 * results of the command sent with `requestId`, until its `end` event, which
 * says the stream is whole, all within `bound`. Past the head, whatever keeps
 * the stream from its `end` leaves the command's outcome unknown: a stream
 * that ends without it, an `end` of a reason other than `complete` (a newer
 * server's), a connection lost, an event whose data is no JSON, the bound
 * passing. Each fails the read with a `wow.command.unknown` problem that
 * carries `requestId` (`requestIdOf`), so a retry with the same id is
 * answered as the duplicate of a commit that did happen. An abort of the
 * caller's own signal fails it with the abort's reason, which carries
 * `requestId` too.
 */
export function commandResults<R>(
  answer: Answer,
  requestId: string,
  bound: AnswerBound,
): CommandResultStream<R> {
  const reader = eventsOf(
    answer,
    event => event !== END_EVENT,
    event => event === END_EVENT || isStage(event),
  );
  /** The next result or `end`, past the events a newer server added. */
  async function read(): Promise<
    ReadableStreamReadResult<JsonServerSentEvent<unknown>>
  > {
    for (;;) {
      let next: ReadableStreamReadResult<JsonServerSentEvent<unknown>>;
      try {
        next = await reader.read();
      } catch (error) {
        bound.clear();
        if (bound.abandoned) {
          rememberRequestId(error, requestId);
          throw error;
        }
        throw unknownOutcome(
          bound.passed
            ? `The command's event stream did not end within ${bound.ms} ms, its wait and a grace: whether it committed is unknown.`
            : "The command's event stream was lost, or sent what is no result, before its end event: whether it committed is unknown.",
          requestId,
          error,
        );
      }
      if (next.done || next.value.event === END_EVENT || isResult(next.value))
        return next;
    }
  }
  return new ReadableStream<CommandResult<R>>({
    async pull(controller) {
      const next = await read();
      if (next.done) {
        bound.clear();
        throw unknownOutcome(
          "The command's event stream ended without its end event: whether it committed is unknown.",
          requestId,
        );
      }
      const { event, data } = next.value;
      if (event === END_EVENT) {
        bound.clear();
        // What may follow, a keep-alive, is not read.
        await reader.cancel().catch(() => undefined);
        const reason = (data as { reason?: unknown } | null)?.reason;
        // A reason a newer server added: this client cannot tell the stream whole.
        if (reason !== COMPLETE)
          throw unknownOutcome(
            `The command's event stream ended for an unknown reason (${JSON.stringify(reason)}): whether it committed is unknown.`,
            requestId,
          );
        controller.close();
        return;
      }
      controller.enqueue(data as CommandResult<R>);
    },
    cancel(reason) {
      bound.clear();
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
   * How many times in a row the stream is asked for without a record in
   * between, the first request included, before it fails with what stopped
   * it, as the Kotlin client's `RetryPolicy.maxAttempts`: the catalog's
   * attempts by default; 1 never resumes.
   */
  readonly maxAttempts?: number;
  /**
   * The wait before the first resume without a `Retry-After`, doubled for
   * each next, as `RetryPolicy.backoff`: the catalog's by default.
   */
  readonly backoffMs?: number;
  /**
   * The longest wait between attempts, as `RetryPolicy.maxRetryAfter`: the
   * catalog's by default. A `Retry-After` beyond it is not waited out: the
   * stream fails at once with the problem that asked for it.
   */
  readonly maxRetryAfterMs?: number;
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

/**
 * The error of a query stream that lost its connection, or was cut off
 * before its `end` event, past its resumes (internal): a
 * `wow.peer.unavailable` problem, `retry` `new-request`, as the Kotlin
 * client's `HttpQueryApi` answers one, the network's error its `cause`.
 *
 * @param detail - What happened to the stream
 * @param cause - What the read or the request to resume failed with
 */
function streamLost(detail: string, cause?: unknown): WowProblemError {
  const code = FrameworkErrorCodes.PEER_UNAVAILABLE;
  return new WowProblemError(
    {
      type: `${PROBLEM_TYPE_PREFIX}${code}`,
      ...RAISED_PROBLEMS[code],
      code,
      detail,
    },
    cause === undefined ? {} : { cause },
  );
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
    maxAttempts = DEFAULT_RETRY.maxAttempts,
    backoffMs = DEFAULT_RETRY.backoffMs,
    maxRetryAfterMs = DEFAULT_RETRY.maxRetryAfterMs,
    signal,
  } = options;
  // A record's exact numbers are its answer's (review R4-Q, Q-9).
  const isRow = (event: string | undefined) =>
    event === ROW_EVENT || event === UNNAMED_EVENT;
  const events = (from: Answer) =>
    eventsOf(
      from,
      isRow,
      event => isRow(event) || event === END_EVENT || event === ERROR_EVENT,
    );
  let reader = events(answer);
  /**
   * Releases a response the stream reads no more of: past the event that
   * ended it, its body may still be open (a peer, a proxy or the caller's
   * fetcher need not close it at once). A release that fails hides nothing:
   * what ended the stream is what its reader learns.
   */
  const release = (from: typeof reader, reason?: unknown) =>
    from.cancel(reason).catch(() => undefined);
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
      if (++failures >= maxAttempts) throw cause;
      // A Retry-After past the longest wait between retries is not waited
      // out: the stream fails with what said it, as the Kotlin client's
      // resume does (`RetryPolicy.delayBefore`).
      if (delayMs !== undefined && delayMs > maxRetryAfterMs) throw cause;
      const wait =
        delayMs ?? Math.min(maxRetryAfterMs, backoffMs * 2 ** (failures - 1));
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
          (error instanceof WowProblemError &&
            (error.retry === 'none' || fromGateway(error.problem)))
        ) {
          throw error;
        }
        if (error instanceof WowProblemError) {
          delayMs = error.retryAfterMs;
          cause = error;
        } else {
          // No answer at all: the stream could not be asked for again. Its
          // wait is the backoff's, not the catalog's for the problem it reads as.
          delayMs = undefined;
          cause = streamLost(
            'The query stream could not be asked for again.',
            error,
          );
        }
      }
    }
  }

  return new ReadableStream<Positioned<T>>({
    async pull(controller) {
      try {
        await read(controller);
      } catch (error) {
        await release(reader, error);
        throw error;
      }
    },
    cancel(reason) {
      cancelling.abort(reason);
      return reader.cancel(reason);
    },
  });

  /** Reads the next record into `controller`, resuming as the stream says. */
  async function read(
    controller: ReadableStreamDefaultController<Positioned<T>>,
  ): Promise<void> {
    for (;;) {
      let stop: unknown;
      let delayMs: number | undefined;
      try {
        const next = await reader.read();
        if (next.done) {
          // Every stream ends with `end`: one without was cut off.
          stop = streamLost('The query stream was cut off before its end.');
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
              await release(reader);
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
        // The connection was lost, unless the reader cancelled it (below).
        stop = streamLost(
          'The connection of the query stream was lost.',
          error,
        );
      }
      if (cancelling.signal.aborted) return;
      // Only a stream read from the start, or whose records have positions, can be asked for again.
      if (signal?.aborted) throw signal.reason;
      if (lastEventId === undefined && received > 0) throw stop;
      // This response ends here: release it before asking for another.
      await release(reader, stop);
      if (!(await resume(stop, delayMs))) {
        controller.close();
        return;
      }
    }
  }
}
