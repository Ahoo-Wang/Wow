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
  ErrorCodes,
  toWowError,
  type BindingError,
  type WowError,
} from '@ahoo-wang/wow-client';
import {
  ViewStoreError,
  type ViewStoreErrorCode,
} from '@ahoo-wang/wow-view-engine';

/**
 * The view store's own error codes, beside Wow's (`ErrorCodes` of
 * `@ahoo-wang/wow-client`).
 *
 * Mirrors `ViewStoreErrorCodes` in
 * `view-store/wow-view-store-api/src/main/kotlin/me/ahoo/wow/viewstore/api/ViewStoreErrorCodes.kt`.
 */
export const ViewStoreErrorCodes = Object.freeze({
  /** A write that is not a valid view or preferences write (HTTP 400). */
  VIEW_INVALID: 'ViewInvalid',
  /** The request carries no `CoSec-App-Id` (HTTP 400). */
  VIEW_APP_REQUIRED: 'ViewAppRequired',
  /** A system view is read-only (HTTP 403). */
  SYSTEM_VIEW_READ_ONLY: 'SystemViewReadOnly',
  /** The path's tenant or owner is missing, blank or invisible (HTTP 400). */
  VIEW_SCOPE_REQUIRED: 'ViewScopeRequired',
  /** The view store's event streams are closed to HTTP queries (HTTP 403). */
  VIEW_EVENT_STREAM_CLOSED: 'ViewEventStreamClosed',
} as const);

/**
 * The binding error code a claim refusal names each shared dashboard with
 * (`View.REFERENCED_BY_SHARED_DASHBOARD`): the board's id as `name`, its
 * title as `msg`.
 */
export const REFERENCED_BY_SHARED_DASHBOARD = 'referenced-by-shared-dashboard';

/** The port's error code of each error code the server answers with. */
const CODES: Readonly<Record<string, ViewStoreErrorCode>> = {
  [ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT]: 'CONFLICT',
  [ErrorCodes.EVENT_VERSION_CONFLICT]: 'CONFLICT',
  [ErrorCodes.SOURCING_VERSION_CONFLICT]: 'CONFLICT',
  [ErrorCodes.NOT_FOUND]: 'NOT_FOUND',
  [ErrorCodes.ILLEGAL_ACCESS_DELETED_AGGREGATE]: 'NOT_FOUND',
  [ErrorCodes.ILLEGAL_ACCESS_OWNER_AGGREGATE]: 'FORBIDDEN',
  [ErrorCodes.ILLEGAL_ACCESS_SPACE_AGGREGATE]: 'FORBIDDEN',
  [ErrorCodes.ILLEGAL_ACCESS_QUERY_SCOPE]: 'FORBIDDEN',
  [ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY]: 'FORBIDDEN',
  [ViewStoreErrorCodes.VIEW_EVENT_STREAM_CLOSED]: 'FORBIDDEN',
  [ViewStoreErrorCodes.VIEW_INVALID]: 'INVALID',
  [ViewStoreErrorCodes.VIEW_APP_REQUIRED]: 'INVALID',
  [ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED]: 'INVALID',
  [ErrorCodes.BAD_REQUEST]: 'INVALID',
  [ErrorCodes.ILLEGAL_ARGUMENT]: 'INVALID',
  // The server's own state, not the request: what a retry may get past.
  [ErrorCodes.ILLEGAL_STATE]: 'UNAVAILABLE',
  [ErrorCodes.COMMAND_VALIDATION]: 'INVALID',
  [ErrorCodes.DUPLICATE_AGGREGATE_ID]: 'INVALID',
  [ErrorCodes.QUERY_SCHEMA_VALIDATION]: 'INVALID',
  [ErrorCodes.REQUEST_TIMEOUT]: 'UNAVAILABLE',
  [ErrorCodes.TOO_MANY_REQUESTS]: 'UNAVAILABLE',
  [ErrorCodes.INTERNAL_SERVER_ERROR]: 'UNAVAILABLE',
  [ErrorCodes.QUERY_SCHEMA_UNAVAILABLE]: 'UNAVAILABLE',
  [ErrorCodes.QUERY_SCHEMA_CONFLICT]: 'UNAVAILABLE',
};

/**
 * A request that did not succeed, as the store reads it: the server's error,
 * when the server answered with one, and the HTTP status, when a response
 * came back at all.
 */
export class Failure {
  constructor(
    /** What the request threw. */
    readonly cause: unknown,
    /** The server's error, read from the failed response. */
    readonly wow?: WowError,
    /** The status of the failed response; absent when none came back. */
    readonly status?: number,
  ) {}

  /** The server's error code, if it answered with one. */
  get errorCode(): string | undefined {
    return this.wow?.errorCode;
  }

  get bindingErrors(): readonly BindingError[] {
    return this.wow?.bindingErrors ?? [];
  }

  /**
   * The path variable the fetcher's interceptors never filled, when that is
   * why the request was never sent: the host's set-up is wrong, not the
   * network.
   */
  get unfilled(): string | undefined {
    if (this.wow || this.status !== undefined) return undefined;
    for (let at: unknown = this.cause, depth = 0; at && depth < 4; depth++) {
      const message = (at as { message?: unknown }).message;
      const found = typeof message === 'string' ? UNFILLED.exec(message) : null;
      if (found) return found[1];
      at = (at as { cause?: unknown }).cause;
    }
    return undefined;
  }

  /**
   * The port's code for it (see {@link portCodeOf}). A path variable the
   * interceptors never filled is `INVALID`, as the server's own
   * `ViewScopeRequired` is: the request is wrong as built and a retry sends
   * the same — not `UNAVAILABLE`, which would offer one.
   */
  get code(): ViewStoreErrorCode {
    if (this.unfilled !== undefined) return 'INVALID';
    return portCodeOf(this.errorCode, this.status);
  }

  get message(): string {
    if (this.wow) return this.wow.message;
    const unfilled = this.unfilled;
    if (unfilled !== undefined)
      return `The fetcher's interceptors did not fill the path variable {${unfilled}}`;
    if (this.status !== undefined)
      return `The view store answered HTTP ${this.status}`;
    return `The view store could not be reached: ${reasonOf(this.cause)}`;
  }

  /** The port's error, with nothing held. */
  toStoreError(): ViewStoreError {
    return new ViewStoreError(this.code, this.message);
  }
}

/** The fetcher's refusal of a route whose path variable has no value. */
const UNFILLED = /^Missing required path parameter: (\S+)/;

/** Whether the request id of a write was used before. */
export function isDuplicateRequest(failure: Failure): boolean {
  return failure.errorCode === ErrorCodes.DUPLICATE_REQUEST_ID;
}

/**
 * The port's code for a server's answer, **by Wow's error code first**: the
 * code says what the server decided, where one HTTP status carries several
 * decisions (a `400` is a stale version on no server, but a bad title and a
 * missing application alike). Only an answer without a code the store knows
 * — a gateway's own page, a proxy, a code a later server adds — falls back to
 * the status, and no answer at all (a network failure, a timeout, an abort)
 * is `UNAVAILABLE`: the outcome is unknown, and a retry under the same
 * `requestId` is what the port expects.
 */
export function portCodeOf(
  errorCode: string | undefined,
  status: number | undefined,
): ViewStoreErrorCode {
  const known = errorCode === undefined ? undefined : CODES[errorCode];
  if (known) return known;
  switch (status) {
    case 400:
    case 422:
      return 'INVALID';
    case 401:
    case 403:
      return 'FORBIDDEN';
    case 404:
    case 410:
      return 'NOT_FOUND';
    case 409:
    case 412:
      return 'CONFLICT';
    default:
      return 'UNAVAILABLE';
  }
}

/** What a failed request threw, read as a {@link Failure}. */
export async function failureOf(error: unknown): Promise<Failure> {
  const wow = await toWowError(error);
  const status = wow?.status ?? statusOf(error);
  return new Failure(error, wow, status);
}

/** The status of a fetcher error's response, when it carries one. */
function statusOf(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const response = (error as { exchange?: { response?: Response } }).exchange
    ?.response;
  return response?.status;
}

/** What a refusal said; a `DOMException` is an `Error` only in some realms. */
function reasonOf(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { name, message } = error as { name?: unknown; message?: unknown };
    if (typeof message === 'string')
      return typeof name === 'string' ? `${name}: ${message}` : message;
  }
  return String(error);
}
