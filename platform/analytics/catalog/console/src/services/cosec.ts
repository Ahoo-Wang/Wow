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

import type {
  Fetcher,
  FetchExchange,
  ResponseInterceptor,
} from "@ahoo-wang/fetcher";
import {
  AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER,
  CoSecConfigurer,
  type TokenRefresher,
  type TokenStorage,
} from "@ahoo-wang/cosec-fetcher";

/**
 * The console holds a token the enterprise's CoSec issued, and the catalog
 * issues none, so there is nothing to refresh it with: an expired token is
 * refused, which signs the console out ({@link SignOutOnUnauthorized}). It
 * is still a refresher: the CoSec client puts the token on a request only
 * when one is configured (as the analytics console found).
 */
export const noRefresh: TokenRefresher = {
  refresh: () =>
    Promise.reject(
      new Error("The console cannot refresh a token; sign in again."),
    ),
};

/**
 * Calls `onUnauthorized` when the service answers a request 401, whatever
 * CoSec's problem says: the token expired, was revoked, or the service does
 * not take it. A response interceptor, right before the CoSec client's own,
 * so it runs on every 401 the client sees. The client's would not sign the
 * console out by itself: it refreshes on `token-expired` (or a 401 without
 * CoSec's body), and a refresh that fails other than with a 401 keeps the
 * session, which `noRefresh` always does; on `token-invalid` and
 * `token-revoked` it removes the token, which no listener here hears. Once
 * this one has signed out, the client finds the token the request was sent
 * with gone and ends the exchange with that failure (its
 * `RefreshSessionChangedError`) without refreshing.
 */
export class SignOutOnUnauthorized implements ResponseInterceptor {
  readonly name = "CatalogSignOutOnUnauthorized";
  readonly order = AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER - 1;

  constructor(private readonly onUnauthorized: () => void) {}

  intercept(exchange: FetchExchange): void {
    if (exchange.response?.status === UNAUTHORIZED) this.onUnauthorized();
  }
}

const UNAUTHORIZED = 401;

/**
 * Sends the CoSec token `tokens` holds with every request of `fetcher`, with
 * the console's device; a request the service answers 401 calls
 * `onUnauthorized`. The client sends them only to the page's origin (its
 * default `sameOriginTrust`), which is where the console calls. The app is
 * the token's own (its `aud`): CoSec 8 reads no header that names one, so
 * sign in with a token issued for this console.
 */
export function withCoSec(
  fetcher: Fetcher,
  tokens: TokenStorage,
  onUnauthorized: () => void,
): Fetcher {
  new CoSecConfigurer({
    tokenStorage: tokens,
    tokenRefresher: noRefresh,
  }).applyTo(fetcher);
  fetcher.interceptors.response.use(new SignOutOnUnauthorized(onUnauthorized));
  return fetcher;
}
