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
  /** 429: the command was not taken, its aggregate or the node being busy; retry later. */
  COMMAND_BUSY: 'wow.command.busy',
  /** 503: whether the command committed is unknown; retrying with the same request id is safe. */
  COMMAND_UNKNOWN: 'wow.command.unknown',
  /** 500: the command's handling failed with an unexpected error; it committed nothing, and a retry fails alike. */
  COMMAND_FAILED: 'wow.command.failed',
  /** 409: the aggregate's current state refuses the command (a check of its decide function); the message says why. */
  COMMAND_REFUSED: 'wow.command.refused',
  /** 503: the node is shutting down; retry on another node. */
  NODE_CLOSING: 'wow.node.closing',
  /** 503: the node is on standby until a migration's cut-over; it serves reads only. */
  NODE_STANDBY: 'wow.node.standby',
  /** 503, with `Retry-After`: a store could not be reached in time; retry later. */
  STORAGE_UNAVAILABLE: 'wow.storage.unavailable',
  /** 503: a scheduled command arrived too long before its due time; it is sent again later. */
  SCHEDULE_NOT_DUE: 'wow.schedule.not-due',
  /** 400: the job command does not fit its job or instance (decision D198). */
  SCHEDULE_JOB_INVALID: 'wow.schedule.job-invalid',
  /** 500: a subscription on the wait chain parked the aggregate. */
  SUBSCRIPTION_PARKED: 'wow.subscription.parked',
  /** 409: a ledger action names a parking the entry no longer is; read it again. */
  LEDGER_STALE: 'wow.ledger.stale',
  /** 504: the wait ran out of time; the command may have committed. */
  WAIT_TIMEOUT: 'wow.wait.timeout',
  /** 400: the query body cannot be read as a query. */
  QUERY_MALFORMED: 'wow.query.malformed',
  /** 400: the query failed admission; each violation has its code. */
  QUERY_INVALID: 'wow.query.invalid',
  /** 500: the query backend failed to run the query. */
  QUERY_FAILED: 'wow.query.failed',
  /** 504: the query ran longer than its budget allows; not retried (decision D254 ③). */
  QUERY_TIMEOUT: 'wow.query.timeout',
  /** 429, with `Retry-After`: the query's store runs as many queries as it allows; retry later. */
  QUERY_BUSY: 'wow.query.busy',
  /**
   * 404: a `single` query matched no record, whatever its model (review R4-Q,
   * Q-4); a state read by id that finds none is `wow.aggregate.not-found`.
   */
  QUERY_NOT_FOUND: 'wow.query.not-found',
  /** 404: no dataset of that name is served to the caller. */
  QUERY_DATASET_NOT_FOUND: 'wow.query.dataset-not-found',
  /**
   * 409, with `Retry-After`: a state read asked for a version (`minVersion`) the
   * aggregate has not reached within the wait; the problem's `version` is its
   * current one (review R4-Q, Q-13).
   */
  QUERY_MIN_VERSION_NOT_REACHED: 'wow.query.min-version-not-reached',
  /** 409: the dataset is served but cannot be queried now (it needs revision, decision D259). */
  QUERY_DATASET_UNAVAILABLE: 'wow.query.dataset-unavailable',
  /** 403: the calling service may not call this command (a synchronous call on the internal port, decision D226). */
  CALL_COMMAND_FORBIDDEN: 'wow.call.command-forbidden',
} as const;

/** A {@link FrameworkErrorCodes} value. */
export type FrameworkErrorCode =
  (typeof FrameworkErrorCodes)[keyof typeof FrameworkErrorCodes];

/** The prefix of every framework code, and of no domain code. */
export const FRAMEWORK_ERROR_PREFIX = 'wow.';

/** The prefix of a problem's `type` that carries a Wow code. */
export const PROBLEM_TYPE_PREFIX = 'urn:wow:error:';

/**
 * An RFC 9457 problem as a Wow service writes it (decisions D101, D102):
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
 * the 503s (`wow.command.unknown`, `wow.node.closing`, `wow.node.standby`,
 * `wow.storage.unavailable`, `wow.schedule.not-due`),
 * the 429s `wow.command.busy` and `wow.query.busy`, the 409s `wow.command.contended` and
 * `wow.query.min-version-not-reached` (once the command commits), and the 504
 * `wow.wait.timeout`, whose command may have committed.
 */
const RETRYABLE: ReadonlyMap<string, boolean> = new Map([
  [FrameworkErrorCodes.COMMAND_UNKNOWN, true],
  [FrameworkErrorCodes.NODE_CLOSING, true],
  [FrameworkErrorCodes.NODE_STANDBY, true],
  [FrameworkErrorCodes.STORAGE_UNAVAILABLE, true],
  [FrameworkErrorCodes.SCHEDULE_NOT_DUE, true],
  [FrameworkErrorCodes.COMMAND_BUSY, true],
  [FrameworkErrorCodes.QUERY_BUSY, false],
  [FrameworkErrorCodes.QUERY_MIN_VERSION_NOT_REACHED, false],
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
  /** The command's result, when the command had a ref. */
  readonly result?: CommandResult;
  /** What a client may do about it. */
  readonly retry: RetryHint;
  /** The error this one explains: the fetcher's, or the stream's. */
  readonly cause?: unknown;

  /**
   * The request id (`Wow-Request-Id`) of the command this answers: its
   * result's, else the one the client sent. Retry with it when
   * {@link RetryHint.sameRequestId} says so.
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
 * answers a commit that did happen as a duplicate (decision D223).
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
