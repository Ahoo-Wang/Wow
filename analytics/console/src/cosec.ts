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

import { fetcher, type Fetcher } from '@ahoo-wang/fetcher';
import {
  CoSecConfigurer,
  TokenStorage,
  type CompositeToken,
  type TokenRefresher,
} from '@ahoo-wang/fetcher-cosec';

/** The application the analytics server keeps the console's views under. */
export const APP_ID = 'analytics';

/** The role the analytics server lets change the dataset registry. */
export const ADMIN_ROLE = 'analytics-admin';

export const LOGIN_PATH = '/login';

/**
 * The CoSec token, in the browser storage CoSec front ends share
 * (`cosec-token`): a single sign-on that already stored one signs the
 * console in too.
 */
export const tokenStorage = new TokenStorage();

/**
 * The console holds a token the enterprise's CoSec issued, and the analytics
 * server issues none, so there is nothing to refresh it with: an expired
 * token is refused here, which signs the console out (`onUnauthorized`).
 *
 * It is still a refresher: fetcher-cosec puts the token on a request only
 * when one is configured, and without it every request went out without
 * `Authorization` — each answered 401 by the server, which signed the
 * console out the moment it signed in (found by the demo, analytics/demo).
 */
export const noRefresh: TokenRefresher = {
  refresh: (_token: CompositeToken) =>
    Promise.reject(
      new Error('The console cannot refresh a token; sign in again.'),
    ),
};

const coSecConfigurer = new CoSecConfigurer({
  appId: APP_ID,
  tokenStorage,
  tokenRefresher: noRefresh,
  // An expired or refused token: sign in again.
  onUnauthorized: () => {
    tokenStorage.signOut();
    window.location.assign(LOGIN_PATH);
  },
});

/**
 * Sends the token, the application and the device with every request, and
 * puts the token's tenant and subject into the view store's paths.
 */
export function withCoSec(target: Fetcher = fetcher): Fetcher {
  coSecConfigurer.applyTo(target);
  return target;
}

/** Signs in with an access token the enterprise's CoSec issued. */
export function signIn(accessToken: string, refreshToken = accessToken): void {
  tokenStorage.signIn({ accessToken, refreshToken });
}

export function signOut(): void {
  tokenStorage.signOut();
}

/** Who is signed in, as the token says; `null` when nobody is. */
export interface CurrentUser {
  id: string;
  tenantId?: string;
  roles: readonly string[];
  admin: boolean;
}

export function currentUser(
  storage: TokenStorage = tokenStorage,
): CurrentUser | null {
  if (!storage.authenticated) return null;
  const payload = storage.currentUser;
  if (!payload) return null;
  const roles = payload.roles ?? [];
  return {
    id: payload.sub,
    tenantId: payload.tenantId,
    roles,
    admin: roles.includes(ADMIN_ROLE),
  };
}
