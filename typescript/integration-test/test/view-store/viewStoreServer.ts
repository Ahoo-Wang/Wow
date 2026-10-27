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

/*
 * The callers the suites act as. The view store service takes the
 * caller from the request through its `CallerResolver`; in development, the
 * host's stands in for CoSec and reads headers — `Wow-User`, `Wow-Owner`,
 * `Wow-Tenant`, `Wow-Roles` — and `Wow-App`, the application. A caller is
 * a fetcher whose interceptor sends them, as a host's CoSec interceptors
 * send its token; the store adds `Wow-Scope` itself where it acts as
 * `(shared)` or `(system)`.
 */

import {
  Fetcher,
  type FetchExchange,
  type RequestInterceptor,
} from '@ahoo-wang/fetcher';
import { inject } from 'vitest';

/** The definition the service serves a configured system view for (globalSetup.ts). */
export const SYSTEM_VIEW_DEFINITION = 'conformance-system';

/** The role that writes the owner `(shared)` and the tenant's dataset wording. */
export const SHARED_WRITER = 'view-store.shared-writer';
/** The role that writes the stored system views. */
export const SYSTEM_WRITER = 'view-store.system-writer';

/** Who a request comes from. */
export interface Caller {
  tenantId: string;
  /** The user: the owner of the caller's personal views. */
  user: string;
  appId: string;
  roles?: readonly string[];
}

class CallerHeaders implements RequestInterceptor {
  readonly name = 'ServiceCaller';
  readonly order = 0;
  constructor(private readonly caller: Caller) {}
  intercept(exchange: FetchExchange): void {
    const headers = exchange.ensureRequestHeaders();
    headers['Wow-User'] = this.caller.user;
    headers['Wow-Owner'] = this.caller.user;
    headers['Wow-Tenant'] = this.caller.tenantId;
    headers['Wow-App'] = this.caller.appId;
    if (this.caller.roles?.length)
      headers['Wow-Roles'] = this.caller.roles.join(',');
  }
}

/** The service's base URL, as the global setup provides it. */
export function serviceURL(): string {
  return inject('viewStoreURL');
}

/** A fetcher on the service that asks as `caller`. */
export function actingAs(caller: Caller): Fetcher {
  const fetcher = new Fetcher({ baseURL: serviceURL() });
  fetcher.interceptors.request.use(new CallerHeaders(caller));
  return fetcher;
}
