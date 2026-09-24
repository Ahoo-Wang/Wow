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
 * service's message is the reason. Without one, the status is all there is
 * to say; anything else says what its message says.
 */
export async function sourceReason(error: unknown): Promise<string> {
  const exchange = exchangeOf(error);
  if (exchange) {
    const told = await toldReason(exchange);
    if (told) return told;
    const status = exchange.response?.status;
    if (typeof status === 'number') return `HTTP ${status}`;
  }
  return error instanceof Error ? error.message : String(error);
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

/** The service's own message from the body it answered with, if it has one. */
async function toldReason(exchange: ExchangeLike): Promise<string | null> {
  if (typeof exchange.extractResult !== 'function') return null;
  try {
    const body = await exchange.extractResult();
    if (typeof body !== 'object' || body === null) return null;
    const { errorMsg, errorCode } = body as Record<string, unknown>;
    if (typeof errorMsg === 'string' && errorMsg.trim()) return errorMsg.trim();
    if (typeof errorCode === 'string' && errorCode.trim())
      return errorCode.trim();
  } catch {
    // No body to read — or not one a reason is in. The status says the rest.
  }
  return null;
}
