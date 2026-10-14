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
  FrameworkErrorCodes,
  toWowProblemError,
  type WowProblemError,
} from '@ahoo-wang/wow-client/v10';
import {
  isViewStoreError,
  ViewStoreError,
  type ViewStoreErrorCode,
} from '@ahoo-wang/wow-view-engine';
import { ViewStoreErrorCodes } from './generated/view-store/boundedContext.js';

/**
 * The V10 view store's own problem codes (`ViewStoreRejections` of
 * `platform/view-store`), beside the framework's (`FrameworkErrorCodes` of
 * `@ahoo-wang/wow-client/v10`): generated from the service's Manifest.
 *
 * Named apart from the engine's `ViewStoreErrorCode` (the port's codes):
 * these are the `code` of the server's problems, which a `ViewStoreError`
 * carries as `detail.code`.
 */
export const WowViewStoreErrorCodes = ViewStoreErrorCodes;

/** The port's code of each problem code the server answers with. */
const CODES: Readonly<Record<string, ViewStoreErrorCode>> = {
  [FrameworkErrorCodes.AGGREGATE_VERSION_MISMATCH]: 'CONFLICT',
  // A request id another command already used: the write cannot be told
  // from that one, so it is refused as the stale write it most likely is.
  [FrameworkErrorCodes.COMMAND_REQUEST_ID_REUSED]: 'CONFLICT',
  [FrameworkErrorCodes.AGGREGATE_NOT_FOUND]: 'NOT_FOUND',
  [FrameworkErrorCodes.AGGREGATE_DELETED]: 'NOT_FOUND',
  [ViewStoreErrorCodes.NOT_FOUND]: 'NOT_FOUND',
  [FrameworkErrorCodes.AUTH_UNAUTHENTICATED]: 'FORBIDDEN',
  [FrameworkErrorCodes.AUTH_FORBIDDEN]: 'FORBIDDEN',
  [FrameworkErrorCodes.AGGREGATE_FORBIDDEN]: 'FORBIDDEN',
  [ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY]: 'FORBIDDEN',
  [ViewStoreErrorCodes.INVALID]: 'INVALID',
  [ViewStoreErrorCodes.APP_REQUIRED]: 'INVALID',
  [ViewStoreErrorCodes.OWNER_REQUIRED]: 'INVALID',
  [ViewStoreErrorCodes.REFERENCED_BY_SHARED_DASHBOARD]: 'INVALID',
  [FrameworkErrorCodes.AGGREGATE_EXISTS]: 'INVALID',
  [FrameworkErrorCodes.COMMAND_MALFORMED]: 'INVALID',
  [FrameworkErrorCodes.COMMAND_INVALID]: 'INVALID',
  [FrameworkErrorCodes.COMMAND_UNHANDLED]: 'INVALID',
  [FrameworkErrorCodes.WAIT_INVALID]: 'INVALID',
  [FrameworkErrorCodes.QUERY_MALFORMED]: 'INVALID',
  [FrameworkErrorCodes.QUERY_INVALID]: 'INVALID',
};

/**
 * A request that did not succeed, as the store reads it: the server's
 * problem when it answered with one, else what the request threw.
 */
export class Failure {
  constructor(
    /** What the request threw. */
    readonly cause: unknown,
    /** The server's problem, when a response came back. */
    readonly problem?: WowProblemError,
  ) {}

  /** The problem's code: a framework code (`wow.*`) or the view store's. */
  get problemCode(): string | undefined {
    return this.problem?.code;
  }

  /**
   * The port's code, **by the problem's code first**: one HTTP status
   * carries several decisions (a `400` is a bad title and a missing
   * application alike). Only an answer without a code the store knows — a
   * gateway's own page, a proxy, a code a later server adds — falls back to
   * the status. A `404` without a code is a route the server does not serve:
   * no view store there (or, on the memory backend, no snapshot query), so
   * `UNSUPPORTED`. No answer at all is `UNAVAILABLE`: the outcome is
   * unknown, and a retry under the same request id is safe, since the
   * server deduplicates it.
   */
  get code(): ViewStoreErrorCode {
    const code = this.problemCode;
    const known = code === undefined ? undefined : CODES[code];
    if (known) return known;
    switch (this.problem?.status) {
      case 400:
      case 422:
        return 'INVALID';
      case 401:
      case 403:
        return 'FORBIDDEN';
      case 404:
        return code === undefined ? 'UNSUPPORTED' : 'NOT_FOUND';
      case 410:
        return 'NOT_FOUND';
      case 409:
      case 412:
        return 'CONFLICT';
      default:
        return 'UNAVAILABLE';
    }
  }

  /** Whether the server (or something in front of it) answered at all. */
  get reached(): boolean {
    return this.problem !== undefined;
  }

  get message(): string {
    if (this.problem) return this.problem.message;
    return `The view store could not be reached: ${reasonOf(this.cause)}`;
  }

  /** The port's error, holding what it was read from. */
  toStoreError(): ViewStoreError {
    return new ViewStoreError(this.code, this.message, this.held());
  }

  /**
   * What {@link toStoreError} holds, for an error of another code: the
   * request's own failure as `cause`, the problem's code as `detail` (a
   * host tells `view-store.app-required` from `view-store.invalid` by it),
   * and for `UNAVAILABLE` whether the server answered (`reachable`).
   */
  held(): {
    cause: unknown;
    detail?: { code: string };
    reachable?: true;
  } {
    const code = this.problemCode;
    return {
      cause: this.cause,
      ...(code === undefined ? {} : { detail: { code } }),
      ...(this.code === 'UNAVAILABLE' && this.reached
        ? { reachable: true as const }
        : {}),
    };
  }
}

/** What a failed request threw, read as a {@link Failure}. */
export async function failureOf(error: unknown): Promise<Failure> {
  if (error instanceof Failure) return error;
  return new Failure(error, await toWowProblemError(error));
}

/**
 * The titles of the shared dashboards a claim refusal names. The server
 * says them in the problem's `detail` only, each as `"title" (id)`:
 * `The view is shown by the shared dashboards "Ops" (b1), "Daily" (b2).`
 * A title holding `" (` is cut there; the server gives no structured list
 * (a gap of the V10 view store, roadmap 7.3a).
 */
export function boardTitles(detail: string | undefined): string[] {
  if (!detail) return [];
  return [...detail.matchAll(/"(.*?)" \(([^()]*)\)/g)].map(match => match[1]);
}

/**
 * Runs one of the port's methods, so that everything it rejects with is a
 * `ViewStoreError`: a failed request as its code says, anything else — a
 * body that did not parse — as `UNAVAILABLE`, the outcome unknown.
 */
export async function guard<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (thrown) {
    if (thrown instanceof Failure) throw thrown.toStoreError();
    if (isViewStoreError(thrown)) throw thrown;
    // A body that did not parse came back from something — a login wall's
    // page, a proxy's: answered, so not "could not be reached".
    throw new ViewStoreError(
      'UNAVAILABLE',
      thrown instanceof Error ? thrown.message : String(thrown),
      {
        cause: thrown,
        ...(thrown instanceof SyntaxError ? { reachable: true as const } : {}),
      },
    );
  }
}

/** Runs one request, so that what it throws is a {@link Failure}. */
export async function request<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (error) {
    // A body that did not parse is no failed request: `guard` reads it.
    if (error instanceof SyntaxError) throw error;
    throw await failureOf(error);
  }
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
