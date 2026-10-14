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

import { vi } from 'vitest';
import {
  Fetcher,
  type FetchExchange,
  type RequestInterceptor,
} from '@ahoo-wang/fetcher';
import {
  fakeV10ViewStore,
  type FakeV10ViewStore,
  type FakeV10ViewStoreOptions,
} from '../support/fakeV10ViewStore.js';

export const BASE_URL = 'http://view-store.test/';

/** Who asks: the headers the service's caller resolver reads. */
export interface Caller {
  user?: string;
  tenant?: string;
  roles?: readonly string[];
  app?: string;
}

/** Both writer roles of the service as shipped. */
export const WRITER_ROLES = [
  'view-store.shared-writer',
  'view-store.system-writer',
] as const;

class CallerHeaders implements RequestInterceptor {
  readonly name = 'FakeV10Caller';
  readonly order = 0;
  constructor(private readonly caller: Caller) {}
  intercept(exchange: FetchExchange): void {
    const headers = exchange.ensureRequestHeaders();
    const { user, tenant, roles, app } = this.caller;
    if (user !== undefined) headers['X-User'] = user;
    if (tenant !== undefined) headers['X-Tenant'] = tenant;
    if (roles?.length) headers['X-Role'] = roles.join(',');
    if (app !== undefined) headers['CoSec-App-Id'] = app;
  }
}

/** A Fetcher on {@link BASE_URL} that asks as `caller`. */
export function actingAs(caller: Caller): Fetcher {
  const fetcher = new Fetcher({ baseURL: BASE_URL });
  fetcher.interceptors.request.use(new CallerHeaders(caller));
  return fetcher;
}

/** Installs the fake service as the global `fetch`, for this test. */
export function installFake(
  options?: FakeV10ViewStoreOptions,
): FakeV10ViewStore {
  const fake = fakeV10ViewStore(options);
  vi.stubGlobal('fetch', vi.fn(fake.fetch));
  return fake;
}
