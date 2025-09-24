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
  Fetcher,
  FetchExchange,
  RequestInterceptor,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';

export const exampleFetcher = new Fetcher({
  baseURL: 'http://localhost:8080/',
});

export const currentUserId = idGenerator.generateId();

class AppendOwnerId implements RequestInterceptor {
  readonly name: string = 'AppendOwnerId';
  readonly order: number = URL_RESOLVE_INTERCEPTOR_ORDER - 1;

  intercept(exchange: FetchExchange) {
    const urlParams = exchange.ensureRequestUrlParams();
    urlParams.path['ownerId'] = currentUserId;
  }
}

class RequestLogger implements RequestInterceptor {
  readonly name: string = 'RequestLogger';
  readonly order: number = Number.MAX_SAFE_INTEGER;

  intercept(exchange: FetchExchange) {
    console.log('exchange: ', exchange);
  }
}

exampleFetcher.interceptors.request.use(new AppendOwnerId());
exampleFetcher.interceptors.request.use(new RequestLogger());
