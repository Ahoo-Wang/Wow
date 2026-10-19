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

import type { Fetcher } from "@ahoo-wang/fetcher";
import {
  CoSecConfigurer,
  type TokenRefresher,
  type TokenStorage,
} from "@ahoo-wang/fetcher-cosec";

/** The application the console calls the ops service from (`CoSec-App-Id`). */
export const APP_ID = "ops-console";

/**
 * The console holds a token the enterprise's CoSec issued, and the ops service
 * issues none, so there is nothing to refresh it with: an expired token is
 * refused, which signs the console out (`onUnauthorized`). It is still a
 * refresher: fetcher-cosec puts the token on a request only when one is
 * configured (as the analytics console found).
 */
export const noRefresh: TokenRefresher = {
  refresh: () =>
    Promise.reject(
      new Error("The console cannot refresh a token; sign in again."),
    ),
};

/**
 * Sends the CoSec token `tokens` holds with every request of `fetcher`, with
 * the console's application and device; a request the service answers 401
 * calls `onUnauthorized`.
 */
export function withCoSec(
  fetcher: Fetcher,
  tokens: TokenStorage,
  onUnauthorized: () => void,
): Fetcher {
  new CoSecConfigurer({
    appId: APP_ID,
    tokenStorage: tokens,
    tokenRefresher: noRefresh,
    onUnauthorized,
  }).applyTo(fetcher);
  return fetcher;
}
