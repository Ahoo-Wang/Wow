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

import type { QueryViolation } from '@ahoo-wang/wow-client/dsl';

/**
 * What a source said went wrong: its reason in words, and — when it is a
 * Wow service that rejected a query — which rule the query broke and where.
 */
export interface SourceFailure {
  /** The reason, in the source's own words where it gave some. */
  reason: string;
  /**
   * The first violation of the problem that carries a stable `code`: a
   * query Wow rejected while decoding it (the JSON path) or admitting it
   * against its query model (the logical field path, `''` for the model).
   * Absent otherwise — a failure the service did not answer, a source that
   * is not Wow.
   */
  violation?: QueryViolation;
  /** The problem's Wow `code`, as the service gave it, when it gave one. */
  errorCode?: string;
  /** The HTTP status the source answered with, when there was one. */
  status?: number;
  /**
   * The request never reached the service: the browser's fetch rejected
   * with a `TypeError` (「Failed to fetch」, 「Load failed」,
   * 「NetworkError when attempting to fetch resource.」), bare or as the
   * cause of an exchange that has no response, with no status and no
   * body. `reason` keeps the browser's words, which are English whatever
   * the reader's language, for the host; the reader is told the server
   * could not be reached.
   */
  unreachable?: true;
}

/**
 * One read per failure: the body of an exchange can be read once, and a
 * failure is asked for by the report to the host and by the Issue on
 * screen alike.
 */
const READ = new WeakMap<object, Promise<SourceFailure>>();

/**
 * What a source said went wrong, in its own words where it gave some.
 *
 * A source is the host's, and the engine words whatever it rejects with. A
 * Wow service rejects a query it refuses with an RFC 9457 problem whose
 * `detail` says why — `{ code: 'wow.query.invalid', detail: 'The query
 * failed admission.', violations: [...] }` — while the HTTP client's error
 * only says that the request failed, and names the URL it failed for: an
 * operator shown that learns nothing they can act on and something they
 * should not see. So a rejection that carries its problem (wow-client's
 * `WowProblemError`, read by its shape rather than its class) or its
 * exchange (fetcher's `ExchangeError`, whose body is asked for) is read for
 * the problem, and the service's words are the reason. Without either, the
 * status is all there is to say; anything else says what its message says.
 *
 * The problem's first violation with a `code` is the violation. It never
 * rejects, and it reads a failure once however often it is asked.
 */
export function sourceFailure(error: unknown): Promise<SourceFailure> {
  if (typeof error !== 'object' || error === null) return readFailure(error);
  let read = READ.get(error);
  if (!read) {
    read = readFailure(error);
    READ.set(error, read);
  }
  return read;
}

/** The reason alone; see `sourceFailure`. */
export async function sourceReason(error: unknown): Promise<string> {
  return (await sourceFailure(error)).reason;
}

async function readFailure(error: unknown): Promise<SourceFailure> {
  // A rejection that carries its problem (a `WowProblemError`) names its
  // code and its status; an exchange's body is the service's problem.
  const problem = (error as { problem?: unknown } | null)?.problem;
  if (typeof problem === 'object' && problem !== null) {
    const told = toldFailure(problem, statusOf(error) ?? statusOf(problem));
    if (told) return told;
  }
  const exchange = exchangeOf(error);
  if (exchange) {
    const status = statusOf(exchange.response);
    const answered = toldFailure(await bodyOf(exchange), status);
    if (answered) return answered;
    if (status !== undefined) return { reason: `HTTP ${status}`, status };
  }
  const reason = error instanceof Error ? error.message : String(error);
  return isNetworkFailure(error) ? { reason, unreachable: true } : { reason };
}

/**
 * Whether a failure with no status and no body is fetch's own: the fetch
 * standard rejects a request that got no response with a `TypeError`, and
 * fetcher's `ExchangeError` carries it as its `cause`.
 */
function isNetworkFailure(error: unknown): boolean {
  return (
    error instanceof TypeError ||
    (error as { cause?: unknown } | null)?.cause instanceof TypeError
  );
}

function statusOf(holder: unknown): number | undefined {
  const status = (holder as { status?: unknown } | null | undefined)?.status;
  return typeof status === 'number' ? status : undefined;
}

/** What of an HTTP exchange a reason is read from. */
interface ExchangeLike {
  extractResult?(): Promise<unknown>;
  response?: { status?: unknown } | null;
}

function exchangeOf(error: unknown): ExchangeLike | null {
  if (typeof error !== 'object' || error === null || !('exchange' in error))
    return null;
  const { exchange } = error;
  return typeof exchange === 'object' && exchange !== null ? exchange : null;
}

/** The body the service answered with, or `null` when there is none to read. */
async function bodyOf(exchange: ExchangeLike): Promise<unknown> {
  if (typeof exchange.extractResult !== 'function') return null;
  try {
    return await exchange.extractResult();
  } catch {
    // No body to read — or not one a reason is in. The status says the rest.
    return null;
  }
}

/**
 * The service's own words and violation from a problem, if it gives some:
 * its `detail`, else its `title`, else its `code`.
 */
function toldFailure(
  body: unknown,
  status: number | undefined,
): SourceFailure | null {
  if (typeof body !== 'object' || body === null) return null;
  const { detail, title, code, violations } = body as Record<string, unknown>;
  const reason = [detail, title, code]
    .map(text => (typeof text === 'string' ? text.trim() : ''))
    .find(Boolean);
  if (!reason) return null;
  const violation = violationOf(violations, reason);
  return {
    reason,
    ...(violation ? { violation } : {}),
    ...(typeof code === 'string' && code ? { errorCode: code } : {}),
    ...(status !== undefined ? { status } : {}),
  };
}

/**
 * Whether the source refused the reader rather than the query: HTTP 403, or
 * a Wow code that names a refusal (`wow.auth.forbidden`,
 * `wow.aggregate.forbidden`, `wow.call.command-forbidden`, and any it adds
 * ending in `forbidden`). `wow.aggregate.deleted` is not one: it is 410, the
 * aggregate is gone, and asking again is no use for another reason.
 */
export function isForbiddenFailure(failure: SourceFailure): boolean {
  if (failure.status === 403) return true;
  return /[.-]forbidden$/.test(failure.errorCode ?? '');
}

function violationOf(
  violations: unknown,
  reason: string,
): QueryViolation | undefined {
  if (!Array.isArray(violations)) return undefined;
  for (const entry of violations as unknown[]) {
    if (typeof entry !== 'object' || entry === null) continue;
    const { code, path, message } = entry as Record<string, unknown>;
    if (typeof code !== 'string' || !code) continue;
    return {
      code,
      path: typeof path === 'string' ? path : '',
      message:
        typeof message === 'string' && message.trim() ? message.trim() : reason,
    };
  }
  return undefined;
}
