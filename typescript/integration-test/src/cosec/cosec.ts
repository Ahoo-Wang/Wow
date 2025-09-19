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
  CompositeToken,
  CoSecOptions,
  CoSecRequestInterceptor,
  AuthorizationResponseInterceptor,
  DeviceIdStorage,
  JwtTokenManager,
  TokenRefresher,
  TokenStorage,
  ResourceAttributionRequestInterceptor,
} from '@ahoo-wang/fetcher-cosec';
import { AuthorizationRequestInterceptor } from '@ahoo-wang/fetcher-cosec';

export class MockTokenRefresher implements TokenRefresher {
  refresh(token: CompositeToken): Promise<CompositeToken> {
    return Promise.reject('Token refresh failed');
  }
}

const cosecOptions: CoSecOptions = {
  appId: 'appId',
  deviceIdStorage: new DeviceIdStorage(),
  tokenManager: new JwtTokenManager(
    new TokenStorage(),
    new MockTokenRefresher(),
  ),
};

export const cosecRequestInterceptor = new CoSecRequestInterceptor(
  cosecOptions,
);
export const authorizationRequestInterceptor =
  new AuthorizationRequestInterceptor(cosecOptions);
export const authorizationResponseInterceptor =
  new AuthorizationResponseInterceptor(cosecOptions);
export const cosecResourceAttributionInterceptor =
  new ResourceAttributionRequestInterceptor({
    tokenStorage: cosecOptions.tokenManager.tokenStorage,
  });
