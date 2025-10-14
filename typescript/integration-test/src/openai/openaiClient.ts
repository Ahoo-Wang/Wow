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
  BaseURLCapable,
  ContentTypeValues, Fetcher,
  FetchExchange,
  REQUEST_BODY_INTERCEPTOR_ORDER,
  RequestInterceptor,
} from '@ahoo-wang/fetcher';
import { ChatRequest } from '../eventstream/types';

export interface OpenAiOptions extends BaseURLCapable {
  apiKey: string;
  model?: string;
}

export class OpenAiRequestInterceptor implements RequestInterceptor {
  readonly name: string = 'OpenAiRequestInterceptor';
  readonly order: number = REQUEST_BODY_INTERCEPTOR_ORDER - 1;

  constructor(private openAiOptions: OpenAiOptions) {
  }

  intercept(exchange: FetchExchange): void {
    const chatRequest = exchange.request.body as ChatRequest;
    if (!chatRequest.model) {
      chatRequest.model = this.openAiOptions.model;
    }
  }
}

export function createOpenAiFetcher(options: OpenAiOptions): Fetcher {
  const openaiFetcher = new Fetcher({
    baseURL: options.baseURL,
    headers: {
      Authorization: `Bearer ${options.apiKey}`,
      'Content-Type': ContentTypeValues.APPLICATION_JSON,
    },
  });
  openaiFetcher.interceptors.request.use(new OpenAiRequestInterceptor(options));
  return openaiFetcher;
}