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
 * The bound over a command's whole answer, and the error of a command whose
 * outcome is unknown (internal): the command client's and its stream
 * reader's alone, so no other entry, the service routes', carries them.
 *
 * @packageDocumentation
 */

import { FrameworkErrorCodes } from './frameworkErrors.js';
import {
  PROBLEM_TYPE_PREFIX,
  rememberRequestId,
  WowProblemError,
} from './problem.js';

/**
 * The grace past a command's wait before its answer is given up on, as the
 * Kotlin client's `RESPONSE_GRACE`: the service answers `wow.wait.timeout` at
 * the wait's end, so a node that has not answered by then never will.
 */
export const ANSWER_GRACE_MS = 5_000;

/**
 * The longest a service waits by default, the default `wow.wait.max-timeout`:
 * the wait that bounds a command stating none, whose wait is the service's
 * default, unless the client's `defaultWaitMs` says otherwise.
 */
export const LONGEST_WAIT_MS = 60_000;

/**
 * A bound over a command's whole answer (internal): its head, its body and,
 * for a stream, every line, from when it is sent until `ms` later. Its
 * `signal` aborts once the bound passes or the caller's own signal aborts,
 * which ends the request and closes its connection.
 */
export class AnswerBound {
  private readonly passing = new AbortController();
  private readonly timer: ReturnType<typeof setTimeout>;
  /** What the request is sent with: the caller's signal and the bound. */
  readonly signal: AbortSignal;

  /**
   * @param ms - How long the whole answer may take
   * @param caller - The caller's own signal, when it gave one
   */
  constructor(
    readonly ms: number,
    private readonly caller?: AbortSignal,
  ) {
    this.signal =
      caller === undefined
        ? this.passing.signal
        : AbortSignal.any([caller, this.passing.signal]);
    this.timer = setTimeout(
      () =>
        this.passing.abort(
          new Error(`The command was not answered within ${ms} ms.`),
        ),
      ms,
    );
    // A process that only waits for this bound need not stay up for it.
    (this.timer as { unref?: () => void }).unref?.();
  }

  /** Whether the bound passed before the caller gave up. */
  get passed(): boolean {
    return this.passing.signal.aborted && !this.abandoned;
  }

  /** Whether the caller aborted its request itself. */
  get abandoned(): boolean {
    return this.caller?.aborted === true;
  }

  /** Ends the bound: the answer is whole, or the request has failed. */
  clear(): void {
    clearTimeout(this.timer);
  }
}

/**
 * The error of a command whose outcome is unknown (internal): a
 * `wow.command.unknown` problem, `retry` `same-request`, carrying the request
 * id the command was sent with, so a retry with it is answered as the
 * duplicate of a commit that did happen.
 *
 * @param detail - Why the outcome is unknown
 * @param requestId - The `Wow-Request-Id` the command was sent with
 * @param cause - What the answer failed with, when it failed
 */
export function unknownOutcome(
  detail: string,
  requestId: string,
  cause?: unknown,
): WowProblemError {
  const code = FrameworkErrorCodes.COMMAND_UNKNOWN;
  const error = new WowProblemError(
    {
      type: `${PROBLEM_TYPE_PREFIX}${code}`,
      title: 'Command outcome unknown',
      status: 503,
      code,
      detail,
    },
    cause === undefined ? {} : { cause },
  );
  rememberRequestId(error, requestId);
  return error;
}
