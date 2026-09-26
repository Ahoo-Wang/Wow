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
  type BindingError,
  type ErrorCode,
  type ErrorInfo,
} from './errorInfo.js';
import { WowHeaders } from './headers.js';
import type { QueryViolation } from './queryErrorCodes.js';

/** Where a {@link WowError} came from, besides its `ErrorInfo`. */
export interface WowErrorOptions {
  /** The HTTP status of the response that carried the error, if any. */
  status?: number;
  /** The error this one explains: the fetcher's, or the stream's. */
  cause?: unknown;
}

/**
 * Wow 8.11 to 9.1.3 answer a list query without a `limit` with this message;
 * 9.1.5 applies its default list size instead.
 */
const MISSING_LIST_LIMIT = /\blist query limit\[0\] must be between /;

/**
 * What to do about an error whose cause the client knows, appended to the
 * message only: `errorMsg` stays the server's own words.
 */
function hintFor({ errorCode, errorMsg, bindingErrors }: ErrorInfo): string {
  // An older server says it with `IllegalArgument` alone; one that codes its
  // budget rejections (Wow 9.2) says `SIZE_OUT_OF_RANGE` too. The words are
  // what tell a missing limit from a limit out of range.
  const outOfRange =
    errorCode === ErrorCodes.ILLEGAL_ARGUMENT ||
    (bindingErrors ?? []).some(
      // `QueryErrorCodes.SIZE_OUT_OF_RANGE`, spelled out to keep this module
      // free of the catalogue.
      error => error?.code === 'SIZE_OUT_OF_RANGE',
    );
  if (
    outOfRange &&
    errorMsg !== undefined &&
    MISSING_LIST_LIMIT.test(errorMsg)
  ) {
    return (
      ' The query has no limit: omitting it needs Wow 9.1.5 or later, so ' +
      'pass one, for example listQuery({ limit: 100 }).'
    );
  }
  return '';
}

/**
 * An error the Wow server answered with.
 *
 * Its `message` is `[errorCode] errorMsg`. When the client knows what causes
 * an error it adds what to do to the message, never to `errorMsg`: a list
 * query without a `limit`, which Wow 8.11 to 9.1.3 refuse with
 * `IllegalArgument`, says to pass one.
 *
 * It carries the server's `ErrorInfo` — `errorCode`, `errorMsg`,
 * `bindingErrors` — and, when a response carried it, the HTTP status. Switch
 * on `errorCode` against {@link ErrorCodes}; a rejected query also says which
 * rule it broke and where as {@link WowError.violation}.
 *
 * Two paths produce one:
 * - a server-sent event stream of this package (query streams and
 *   `sendAndWaitStream`) errors with a `WowError` when the server sends an
 *   error event in the middle of it; a `for await` over the stream throws it;
 * - {@link toWowError} turns what a failed request threw into one.
 *
 * @example
 * ```typescript
 * async function findState(id: string) {
 *   try {
 *     return await snapshotClient.getStateById(id);
 *   } catch (error) {
 *     const wowError = await toWowError(error);
 *     if (wowError?.errorCode === ErrorCodes.NOT_FOUND) return undefined;
 *     throw wowError ?? error;
 *   }
 * }
 * ```
 */
export class WowError extends Error implements ErrorInfo {
  override readonly name = 'WowError';
  readonly errorCode: ErrorCode;
  readonly errorMsg: string;
  readonly bindingErrors: BindingError[];
  /** The HTTP status of the response that carried the error, if any. */
  readonly status?: number;
  /** The error this one explains: the fetcher's, or the stream's. */
  readonly cause?: unknown;
  /**
   * Which rule a rejected request broke, and where, read from the first of
   * `bindingErrors` that carries a `code`; `undefined` when none does.
   *
   * Wow answers a query it rejects while decoding (`IllegalArgument`) or
   * admitting it against the query model (`QuerySchemaValidation`) with one
   * such binding error, whose message is `errorMsg`. Switch on its `code`
   * against {@link QueryErrorCodes}, and fall back to `errorMsg` for a code
   * this package does not know: the list only grows. From Wow 9.2 the
   * entry's budget and gates say their rule too (`SIZE_OUT_OF_RANGE`,
   * `EXPENSIVE_OPERATOR_DISABLED`, …); an older server answers them with
   * text alone (`HTTP list query limit[...]`). A command's validation error
   * carries no code, and neither does a failure of the server itself (HTTP
   * 500 `InternalServerError`).
   *
   * @example
   * ```typescript
   * async function fieldInError(error: unknown): Promise<string | undefined> {
   *   const violation = (await toWowError(error))?.violation;
   *   if (violation?.code === QueryErrorCodes.UNKNOWN_FIELD) {
   *     return violation.path; // e.g. 'state.items.sku'
   *   }
   *   return undefined;
   * }
   * ```
   */
  readonly violation?: QueryViolation;

  constructor(errorInfo: ErrorInfo, options: WowErrorOptions = {}) {
    super(
      (errorInfo.errorMsg
        ? `[${errorInfo.errorCode}] ${errorInfo.errorMsg}`
        : errorInfo.errorCode) + hintFor(errorInfo),
    );
    if (options.cause !== undefined) this.cause = options.cause;
    this.errorCode = errorInfo.errorCode;
    this.errorMsg = errorInfo.errorMsg ?? '';
    this.bindingErrors = errorInfo.bindingErrors ?? [];
    if (options.status !== undefined) this.status = options.status;
    for (const { name, msg, code } of this.bindingErrors) {
      if (typeof code !== 'string') continue;
      this.violation = {
        code,
        path: name ?? '',
        message: msg ?? this.errorMsg,
      };
      break;
    }
  }
}

/**
 * Whether a value has the shape of the server's `ErrorInfo`: an object with a
 * string `errorCode`, and `errorMsg` a string when present.
 */
export function isErrorInfo(value: unknown): value is ErrorInfo {
  if (typeof value !== 'object' || value === null) return false;
  const { errorCode, errorMsg } = value as Partial<Record<string, unknown>>;
  return (
    typeof errorCode === 'string' &&
    (errorMsg === undefined || typeof errorMsg === 'string')
  );
}

/** The part of a fetcher error this module reads, without importing fetcher. */
interface ExchangeLike {
  exchange?: { response?: Response };
  cause?: unknown;
}

async function readErrorInfo(
  response: Response,
): Promise<ErrorInfo | undefined> {
  const header = response.headers.get(WowHeaders.ERROR_CODE);
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    // Not JSON, or already read: the header is all there is.
  }
  if (isErrorInfo(body)) return body;
  if (header) return { errorCode: header, errorMsg: response.statusText };
  return undefined;
}

/**
 * Turns what a failed request threw into a {@link WowError}, or `undefined`
 * when it is not an error the Wow server answered with (a network failure, a
 * timeout, an abort, a proxy's own error page).
 *
 * It reads, in order: a `WowError` itself or as the `cause`; otherwise the
 * failed response of a fetcher error (`ExchangeError`,
 * `HttpStatusValidationError`), whose body is the server's `ErrorInfo`, or
 * failing that its `Wow-Error-Code` header. The response is cloned, so its
 * body stays readable.
 *
 * It is asynchronous because the error body has to be read; a thrown fetcher
 * error has not read it.
 */
export async function toWowError(
  error: unknown,
): Promise<WowError | undefined> {
  if (error instanceof WowError) return error;
  if (typeof error !== 'object' || error === null) return undefined;
  const { exchange, cause } = error as ExchangeLike;
  if (cause instanceof WowError) return cause;
  const response = exchange?.response;
  if (!response || response.ok) return undefined;
  const errorInfo = await readErrorInfo(response);
  return errorInfo
    ? new WowError(errorInfo, { status: response.status, cause: error })
    : undefined;
}
