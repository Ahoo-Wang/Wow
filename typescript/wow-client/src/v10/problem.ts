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

import type { CommandResult, Violation } from './wire.js';

/**
 * The framework's error codes (design 05 §3, decisions D102, D103, D130),
 * each answered with the status of the Manifest's `errors` table. A domain
 * rejection has a code of its own, answered with 422 unless the service
 * configures another status.
 */
export const FrameworkErrorCodes = {
  /** 401: the request carries no valid credentials. */
  AUTH_UNAUTHENTICATED: 'wow.auth.unauthenticated',
  /** 403: the caller may not send this command. */
  AUTH_FORBIDDEN: 'wow.auth.forbidden',
  /** 403: the aggregate is outside the caller's scope. */
  AGGREGATE_FORBIDDEN: 'wow.aggregate.forbidden',
  /** 400: the command body cannot be read as the command type. */
  COMMAND_MALFORMED: 'wow.command.malformed',
  /** 400: the command failed validation; the problem lists the violations. */
  COMMAND_INVALID: 'wow.command.invalid',
  /** 400: the wait is not a chain the runtime can follow. */
  WAIT_INVALID: 'wow.wait.invalid',
  /** 400: the aggregate does not handle this command. */
  COMMAND_UNHANDLED: 'wow.command.unhandled',
  /** 404: the aggregate does not exist. */
  AGGREGATE_NOT_FOUND: 'wow.aggregate.not-found',
  /** 409: the aggregate already exists. */
  AGGREGATE_EXISTS: 'wow.aggregate.exists',
  /** 409, or 412 with `If-Match`: the aggregate is not at the expected version. */
  AGGREGATE_VERSION_MISMATCH: 'wow.aggregate.version-mismatch',
  /** 409: the request id was used by another command. */
  COMMAND_REQUEST_ID_REUSED: 'wow.command.request-id-reused',
  /** 409: concurrent writers kept winning; retrying with the same request id is safe. */
  COMMAND_CONTENDED: 'wow.command.contended',
  /** 410: the aggregate is deleted; only RestoreAggregate applies. */
  AGGREGATE_DELETED: 'wow.aggregate.deleted',
  /** 429: the node has too many commands in flight; retry later. */
  COMMAND_BUSY: 'wow.command.busy',
  /** 503: whether the command committed is unknown; retrying with the same request id is safe. */
  COMMAND_UNKNOWN: 'wow.command.unknown',
  /** 503: the node is shutting down; retry on another node. */
  NODE_CLOSING: 'wow.node.closing',
  /** 503, with `Retry-After`: a store could not be reached in time; retry later. */
  STORAGE_UNAVAILABLE: 'wow.storage.unavailable',
  /** 503: a scheduled command arrived too long before its due time; it is sent again later. */
  SCHEDULE_NOT_DUE: 'wow.schedule.not-due',
  /** 400: the job command does not fit its job or instance (decision D198). */
  SCHEDULE_JOB_INVALID: 'wow.schedule.job-invalid',
  /** 500: a subscription on the wait chain parked the aggregate. */
  SUBSCRIPTION_PARKED: 'wow.subscription.parked',
  /** 504: the wait ran out of time; the command may have committed. */
  WAIT_TIMEOUT: 'wow.wait.timeout',
  /** 400: the query body cannot be read as a query. */
  QUERY_MALFORMED: 'wow.query.malformed',
  /** 400: the query failed admission; each violation has its code. */
  QUERY_INVALID: 'wow.query.invalid',
  /** 500: the query backend failed to run the query. */
  QUERY_FAILED: 'wow.query.failed',
  /** 503: the query ran longer than its budget allows. */
  QUERY_TIMEOUT: 'wow.query.timeout',
} as const;

/** A {@link FrameworkErrorCodes} value. */
export type FrameworkErrorCode =
  (typeof FrameworkErrorCodes)[keyof typeof FrameworkErrorCodes];

/** The prefix of every framework code, and of no domain code. */
export const FRAMEWORK_ERROR_PREFIX = 'wow.';

/** The prefix of a problem's `type` that carries a Wow code. */
export const PROBLEM_TYPE_PREFIX = 'urn:wow:error:';

/**
 * An RFC 9457 problem as a Wow V10 service writes it (decisions D101, D102):
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
  readonly violations?: readonly Violation[];
  /** The whole result of a command that has a ref. */
  readonly result?: CommandResult;
}

/**
 * What a client may do about a failed request: whether retrying can succeed,
 * whether to keep the request id (`Wow-Request-Id`) so the server
 * deduplicates a command that did commit, and how long to wait first.
 */
export interface RetryHint {
  readonly retryable: boolean;
  /** Retry with the same `Wow-Request-Id`: a commit is then answered as a duplicate. */
  readonly sameRequestId: boolean;
  /** The server's `Retry-After`, in milliseconds. */
  readonly afterMs?: number;
}

/**
 * The codes a retry may get past, each with whether to keep the request id:
 * the 503s (`wow.command.unknown`, `wow.node.closing`,
 * `wow.storage.unavailable`, `wow.schedule.not-due`, `wow.query.timeout`),
 * the 429 `wow.command.busy`, the 409 `wow.command.contended` and the 504
 * `wow.wait.timeout`, whose command may have committed.
 */
const RETRYABLE: ReadonlyMap<string, boolean> = new Map([
  [FrameworkErrorCodes.COMMAND_UNKNOWN, true],
  [FrameworkErrorCodes.NODE_CLOSING, true],
  [FrameworkErrorCodes.STORAGE_UNAVAILABLE, true],
  [FrameworkErrorCodes.SCHEDULE_NOT_DUE, true],
  [FrameworkErrorCodes.QUERY_TIMEOUT, false],
  [FrameworkErrorCodes.COMMAND_BUSY, true],
  [FrameworkErrorCodes.COMMAND_CONTENDED, true],
  [FrameworkErrorCodes.WAIT_TIMEOUT, true],
]);

/**
 * The retry hint of a problem with `code` and `status`: a code of the
 * retryable table, or a 503 without a code (a proxy's), may be retried.
 *
 * @param code - The problem's Wow code, if any
 * @param status - The HTTP status
 * @param afterMs - The `Retry-After` the response carried, in milliseconds
 */
export function retryHintOf(
  code: string | undefined,
  status: number,
  afterMs?: number,
): RetryHint {
  const sameRequestId = code === undefined ? undefined : RETRYABLE.get(code);
  const retryable =
    sameRequestId !== undefined || (code === undefined && status === 503);
  return {
    retryable,
    sameRequestId: sameRequestId ?? retryable,
    ...(afterMs === undefined ? {} : { afterMs }),
  };
}

/**
 * A request a Wow V10 service answered with a problem: a non-2xx response, a
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
  /** The command's result, when the command had a ref. */
  readonly result?: CommandResult;
  /** What a client may do about it. */
  readonly retry: RetryHint;
  /** The error this one explains: the fetcher's, or the stream's. */
  readonly cause?: unknown;

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
    this.problem = problem;
    this.status = problem.status;
    if (problem.code !== undefined) this.code = problem.code;
    this.violations = problem.violations ?? [];
    if (problem.result !== undefined) this.result = problem.result;
    this.retry = retryHintOf(
      problem.code,
      problem.status,
      options.retryAfterMs,
    );
    if (options.cause !== undefined) this.cause = options.cause;
  }

  /** Whether the code is a framework one (`wow.*`) rather than the domain's. */
  get isFramework(): boolean {
    return this.code?.startsWith(FRAMEWORK_ERROR_PREFIX) ?? false;
  }
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
    return {
      ...body,
      type: body.type ?? 'about:blank',
      title: body.title ?? response.statusText,
      ...(code === undefined ? {} : { code }),
    };
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
