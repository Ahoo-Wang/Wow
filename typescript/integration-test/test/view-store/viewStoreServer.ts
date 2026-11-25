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

import { Fetcher } from '@ahoo-wang/fetcher';
import { developmentCallerHeaders } from '@ahoo-wang/wow-client';
import { developmentCaller } from '@ahoo-wang/wow-client/testing';
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
  /** Signed in as `user` with a scope that names no owner: a tenant-level caller. */
  ownerless?: boolean;
}

/** The service's base URL, as the global setup provides it. */
export function serviceURL(): string {
  return inject('viewStoreURL');
}

/** The development caller of `caller`: its user, the owner unless it has none. */
function developmentOf(caller: Caller) {
  return {
    user: caller.user,
    ...(caller.ownerless ? {} : { owner: caller.user }),
    tenant: caller.tenantId,
    app: caller.appId,
    ...(caller.roles === undefined ? {} : { roles: caller.roles }),
  };
}

/** A fetcher on the service that asks as `caller`. */
export function actingAs(caller: Caller): Fetcher {
  const fetcher = new Fetcher({ baseURL: serviceURL() });
  fetcher.interceptors.request.use(developmentCaller(developmentOf(caller)));
  return fetcher;
}

/**
 * Sends one request to the service as `caller`, outside any store, and
 * answers the response as it came; `body` is sent as JSON.
 */
export function sendAs(
  caller: Caller,
  request: {
    method: string;
    path: string;
    headers?: Record<string, string>;
    body?: unknown;
  },
): Promise<Response> {
  const { method, path, headers = {}, body } = request;
  return fetch(new URL(path.replace(/^\//, ''), serviceURL()), {
    method,
    headers: {
      ...developmentCallerHeaders(developmentOf(caller)),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
