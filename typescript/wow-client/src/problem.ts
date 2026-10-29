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

import { FRAMEWORK_RETRIES, type Retry } from './frameworkErrors.js';
import {
  CommandResultStatus,
  type RefusedResult,
  type Violation,
} from './wire.js';

export {
  DEFAULT_RETRY,
  FRAMEWORK_RETRIES,
  type FrameworkRetry,
  type FrameworkErrorCode,
  FrameworkErrorCodes,
  type Retry,
} from './frameworkErrors.js';

/** The prefix of every framework code, and of no domain code. */
export const FRAMEWORK_ERROR_PREFIX = 'wow.';

/** The prefix of a problem's `type` that carries a Wow code. */
export const PROBLEM_TYPE_PREFIX = 'urn:wow:error:';

/**
 * An RFC 9457 problem as a Wow service writes it:
 * `type` is `urn:wow:error:<code>`, or `about:blank` for an error that is
 * only HTTP's (a missing route, 406, 415).
 */
export interface Problem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  /** The Wow code; absent for an error that is only HTTP's. */
  readonly code?: string;
  /**
   * What a retry may do, as the error catalog declares it for the code: every
   * problem a Wow service writes carries it.
   */
  readonly retry?: Retry;
  readonly violations?: readonly Violation[];
  /**
   * The whole result of a command that has a ref; its id is null when
   * admission refused a creation before choosing one.
   */
  readonly result?: RefusedResult;
  /**
   * The problem's extension members (RFC 9457): every member the server
   * wrote besides the ones above and `instance`, such as a rejection's
   * structured data (decision D211). Absent when it wrote none.
   */
  readonly extensions?: Readonly<Record<string, unknown>>;
}

/** The members of a problem that are not extension members. */
const PROBLEM_MEMBERS: ReadonlySet<string> = new Set([
  'type',
  'title',
  'status',
  'detail',
  'instance',
  'code',
  'retry',
  'violations',
  'result',
  'extensions',
]);

/**
 * `problem` with its extension members gathered as `extensions` (internal):
 * a problem read from the wire carries them as top-level members.
 */
function withExtensions(problem: Problem): Problem {
  if (problem.extensions !== undefined) return problem;
  const extensions = Object.fromEntries(
    Object.entries(problem).filter(([name]) => !PROBLEM_MEMBERS.has(name)),
  );
  return Object.keys(extensions).length === 0
    ? problem
    : { ...problem, extensions };
}

/**
 * What a retry of `problem` may do: its `retry` member, which every problem a
 * Wow service writes carries; else the error catalog's for its code; else, for
 * a body that is no Wow problem (a proxy's 503 or 429), `same-request`.
 *
 * @param problem - The problem a request was answered with
 */
export function retryOf(problem: Problem): Retry {
  if (problem.retry !== undefined) return problem.retry;
  const known =
    problem.code === undefined
      ? undefined
      : FRAMEWORK_RETRIES.get(problem.code);
  if (known) return known.retry;
  return problem.code === undefined &&
    (problem.status === 503 || problem.status === 429)
    ? 'same-request'
    : 'none';
}

/**
 * A request a Wow service answered with a problem: a non-2xx response, a
 * command result that did not succeed, or the `error` event that ends a
 * streamed query.
 */
export class WowProblemError extends Error {
  override readonly name = 'WowProblemError';
  /** The problem as the server wrote it. */
  readonly problem: Problem;
  /** The HTTP status. */
  readonly status: number;
  /** The Wow code; absent for an error that is only HTTP's. */
  readonly code?: string;
  readonly violations: readonly Violation[];
  /** The command's result, when the command had a ref ({@link RefusedResult}). */
  readonly result?: RefusedResult;
  /** What a retry may do ({@link retryOf}). */
  readonly retry: Retry;
  /**
   * How long to wait before a retry, in milliseconds: the response's
   * `Retry-After`, else the error catalog's for its code; none when no retry
   * may get past it.
   */
  readonly retryAfterMs?: number;
  /** The error this one explains: the fetcher's, or the stream's. */
  readonly cause?: unknown;

  /**
   * The request id (`Wow-Request-Id`) of the command this answers: its
   * result's, else the one the client sent. Retry with it when
   * {@link WowProblemError.retry} is `same-request`.
   */
  get requestId(): string | undefined {
    return this.result?.requestId ?? SENT_REQUEST_IDS.get(this);
  }

  /**
   * @param problem - The problem the server answered with
   * @param options - The response's `Retry-After` in milliseconds, and the
   * error this one explains
   */
  constructor(
    problem: Problem,
    options: { retryAfterMs?: number; cause?: unknown } = {},
  ) {
    super(
      problem.code
        ? `[${problem.code}] ${problem.detail ?? problem.title}`
        : `${problem.status} ${problem.detail ?? problem.title}`,
    );
    this.problem = withExtensions(problem);
    this.status = problem.status;
    if (problem.code !== undefined) this.code = problem.code;
    this.violations = problem.violations ?? [];
    if (problem.result !== undefined) this.result = problem.result;
    this.retry = retryOf(problem);
    const retryAfterMs =
      options.retryAfterMs ??
      (problem.code === undefined
        ? undefined
        : FRAMEWORK_RETRIES.get(problem.code)?.retryAfterMs);
    if (this.retry !== 'none' && retryAfterMs !== undefined) {
      this.retryAfterMs = retryAfterMs;
    }
    if (options.cause !== undefined) this.cause = options.cause;
  }

  /** Whether the code is a framework one (`wow.*`) rather than the domain's. */
  get isFramework(): boolean {
    return this.code?.startsWith(FRAMEWORK_ERROR_PREFIX) ?? false;
  }
}

/**
 * The command was rejected: its result's status is `rejected`, decided by
 * its aggregate (a business outcome with the domain's code) or at admission
 * (an unknown command, an invalid one, a caller the authorizer forbids).
 * Sending it again with the same request id is rejected again. As Kotlin's
 * `CommandRejectedException`. `send` and `wait` map what a command is
 * answered by the result's status, not by where it was refused (Kotlin's
 * `CommandClient` maps it the same way):
 *
 * - a result that succeeded: resolved;
 * - a rejected result: a {@link CommandRejectedError};
 * - a problem whose result is rejected, an admission refusal (an unknown
 *   command, an invalid one, a forbidden caller): a
 *   {@link CommandRejectedError}, its `problem` set;
 * - a result of a status this client does not know, a newer service's: a
 *   {@link WowProblemError} `wow.command.failed`, not retryable;
 * - any other problem, refused before admission (a body that does not
 *   parse), a technical failure (a busy or closing node, a store or a broker
 *   that is down, a wait that timed out) or a failed result: a
 *   {@link WowProblemError}, with its result when the service sent one, and
 *   what a retry may do ({@link WowProblemError.retry}).
 *
 * `stream` emits every result, whatever its status.
 */
export class CommandRejectedError extends Error {
  override readonly name = 'CommandRejectedError';
  /** The rejected result; its ref names no aggregate id when none was decided. */
  readonly result: RefusedResult;
  /** The problem the service answered with, when it answered one. */
  readonly problem?: Problem;
  /** The error this one explains. */
  readonly cause?: unknown;

  /**
   * @param result - The rejected result
   * @param options - The problem it came in, and the error this one explains
   */
  constructor(
    result: RefusedResult,
    options: { problem?: Problem; cause?: unknown } = {},
  ) {
    const said = [result.error?.code, result.error?.message]
      .filter(Boolean)
      .join(' ');
    super(
      `${result.ref.type.context}.${result.ref.type.name}/${result.ref.id ?? '(no id)'} rejected the command` +
        (said ? `: ${said}` : '.'),
    );
    if (result.status !== CommandResultStatus.REJECTED) {
      throw new TypeError(
        `A rejection has a rejected result, not ${result.status}.`,
      );
    }
    this.result = result;
    if (options.problem !== undefined) this.problem = options.problem;
    if (options.cause !== undefined) this.cause = options.cause;
  }

  /** The rejection's error code. */
  get code(): string {
    return this.result.error?.code ?? '';
  }

  /** The request id (`Wow-Request-Id`) of the rejected command. */
  get requestId(): string {
    return this.result.requestId;
  }
}

/** The request ids the client sent, by the errors the requests failed with. */
const SENT_REQUEST_IDS = new WeakMap<object, string>();

/**
 * Remembers that the request that failed with `error` was sent with
 * `requestId` (internal): {@link requestIdOf} answers it.
 *
 * @param error - What the request failed with
 * @param requestId - The `Wow-Request-Id` it was sent with
 */
export function rememberRequestId(error: unknown, requestId: string): void {
  if (
    (typeof error === 'object' && error !== null) ||
    typeof error === 'function'
  ) {
    if (!SENT_REQUEST_IDS.has(error)) SENT_REQUEST_IDS.set(error, requestId);
  }
}

/**
 * The request id (`Wow-Request-Id`) a command that failed with `error` was
 * sent with: also after a failure no server answered (the network, a proxy,
 * an abort), so the command can be retried with the same id and the service
 * answers a commit that did happen as a duplicate.
 * Undefined for an error of no command the client sent.
 *
 * @param error - What a command rejected with
 */
export function requestIdOf(error: unknown): string | undefined {
  if (error instanceof WowProblemError) return error.requestId;
  return (typeof error === 'object' && error !== null) ||
    typeof error === 'function'
    ? SENT_REQUEST_IDS.get(error)
    : undefined;
}

/** Whether `value` reads as a problem: an object with a numeric `status`. */
export function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { status?: unknown }).status === 'number'
  );
}

/**
 * The milliseconds of a `Retry-After` header: delay seconds or an HTTP date.
 *
 * @param value - The header's value
 * @param now - The current time, in epoch milliseconds
 */
export function parseRetryAfter(
  value: string | null | undefined,
  now: number = Date.now(),
): number | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed) * 1000;
  const date = Date.parse(trimmed);
  return Number.isNaN(date) ? undefined : Math.max(0, date - now);
}

/**
 * The problem a response that did not succeed carries: its body when it is
 * a problem (or a code the `type` names), else one made of its status.
 *
 * @param response - A response that is not 2xx
 */
export async function readProblem(response: Response): Promise<Problem> {
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    // Not JSON, or already read: the status is all there is.
  }
  if (isProblem(body)) {
    const code =
      body.code ??
      (body.type?.startsWith(PROBLEM_TYPE_PREFIX)
        ? body.type.slice(PROBLEM_TYPE_PREFIX.length)
        : undefined);
    return withExtensions({
      ...body,
      type: body.type ?? 'about:blank',
      title: body.title ?? response.statusText,
      ...(code === undefined ? {} : { code }),
    });
  }
  return {
    type: 'about:blank',
    title: response.statusText,
    status: response.status,
  };
}

interface ExchangeLike {
  exchange?: { response?: Response };
  cause?: unknown;
}

/**
 * The {@link WowProblemError} of an error a request rejected with: the
 * fetcher's `ExchangeError` of a response that is not 2xx, read as a problem.
 * Undefined for a failure no server answered (a network error, an abort).
 *
 * @param error - What the request rejected with
 */
export async function toWowProblemError(
  error: unknown,
): Promise<WowProblemError | undefined> {
  if (error instanceof WowProblemError) return error;
  if (typeof error !== 'object' || error === null) return undefined;
  const { exchange, cause } = error as ExchangeLike;
  if (cause instanceof WowProblemError) return cause;
  const response = exchange?.response;
  if (!response || response.ok) return undefined;
  const problem = await readProblem(response);
  const retryAfterMs = parseRetryAfter(response.headers.get('Retry-After'));
  return new WowProblemError(problem, {
    cause: error,
    ...(retryAfterMs === undefined ? {} : { retryAfterMs }),
  });
}
