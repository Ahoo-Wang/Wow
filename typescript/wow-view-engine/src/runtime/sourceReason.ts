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

import type { QueryViolation } from '@ahoo-wang/wow-client';

/**
 * What a source said went wrong: its reason in words, and — when it is a
 * Wow service that rejected a query — which rule the query broke and where.
 */
export interface SourceFailure {
  /** The reason, in the source's own words where it gave some. */
  reason: string;
  /**
   * The first binding error of the body that carries a stable `code`: a
   * query Wow rejected while decoding it (the JSON path) or admitting it
   * against its query model (the logical field path, `''` for the model).
   * Absent otherwise — a budget rejection, a failure the service did not
   * answer, a source that is not Wow.
   */
  violation?: QueryViolation;
  /** The service's `errorCode`, as it gave it, when it gave one. */
  errorCode?: string;
  /** The HTTP status the source answered with, when there was one. */
  status?: number;
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
 * Wow service rejects a query it refuses with a 4xx whose body says why —
 * `{ errorCode: 'IllegalArgument', errorMsg: 'HTTP page window[12000] must
 * not exceed 10000.' }` — while the HTTP client's error only says that the
 * request failed, and names the URL it failed for: an operator shown that
 * learns nothing they can act on and something they should not see. So a
 * rejection that carries its exchange (fetcher's `ExchangeError`, read by
 * its shape rather than its class: the engine does not depend on the HTTP
 * client, only on what an exchange offers) is asked for its body, and the
 * service's message is the reason. A rejection that is itself such a body
 * (wow-client's `WowError`, which a stream errors with) is read the same way.
 * Without either, the status is all there is to say; anything else says what
 * its message says.
 *
 * The body's first binding error with a `code` is the violation. It never
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
  // A rejection that is an error body itself (a `WowError`) names its code
  // and carries its status; an exchange's body is the service's whether it
  // names a code or not.
  if (
    typeof (error as { errorCode?: unknown } | null)?.errorCode === 'string'
  ) {
    const told = toldFailure(error, statusOf(error));
    if (told) return told;
  }
  const exchange = exchangeOf(error);
  if (exchange) {
    const status = statusOf(exchange.response);
    const answered = toldFailure(await bodyOf(exchange), status);
    if (answered) return answered;
    if (status !== undefined) return { reason: `HTTP ${status}`, status };
  }
  return { reason: error instanceof Error ? error.message : String(error) };
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
 * The service's own message and violation from an error body, if it names
 * one: its `errorMsg`, else its `errorCode`.
 */
function toldFailure(
  body: unknown,
  status: number | undefined,
): SourceFailure | null {
  if (typeof body !== 'object' || body === null) return null;
  const { errorMsg, errorCode, bindingErrors } = body as Record<
    string,
    unknown
  >;
  const reason =
    typeof errorMsg === 'string' && errorMsg.trim()
      ? errorMsg.trim()
      : typeof errorCode === 'string'
        ? errorCode.trim()
        : '';
  if (!reason) return null;
  const violation = violationOf(bindingErrors, reason);
  return {
    reason,
    ...(violation ? { violation } : {}),
    ...(typeof errorCode === 'string' && errorCode ? { errorCode } : {}),
    ...(status !== undefined ? { status } : {}),
  };
}

/**
 * Whether the source refused the reader rather than the query: HTTP 403, or
 * one of Wow's `IllegalAccess*` codes — the owner's or the space's aggregate
 * (`IllegalAccessOwnerAggregate`, `IllegalAccessSpaceAggregate`), a query
 * the server requires a tenant scope for (`IllegalAccessQueryScope`), and
 * any it adds under that prefix. `IllegalAccessDeletedAggregate` is not one:
 * it is 410, the aggregate is gone, and asking again is no use for another
 * reason.
 */
export function isForbiddenFailure(failure: SourceFailure): boolean {
  if (failure.status === 403) return true;
  const code = failure.errorCode;
  return (
    code !== undefined &&
    code.startsWith('IllegalAccess') &&
    code !== 'IllegalAccessDeletedAggregate'
  );
}

function violationOf(
  bindingErrors: unknown,
  reason: string,
): QueryViolation | undefined {
  if (!Array.isArray(bindingErrors)) return undefined;
  for (const entry of bindingErrors as unknown[]) {
    if (typeof entry !== 'object' || entry === null) continue;
    const { code, name, msg } = entry as Record<string, unknown>;
    if (typeof code !== 'string' || !code) continue;
    return {
      code,
      path: typeof name === 'string' ? name : '',
      message: typeof msg === 'string' && msg.trim() ? msg.trim() : reason,
    };
  }
  return undefined;
}
