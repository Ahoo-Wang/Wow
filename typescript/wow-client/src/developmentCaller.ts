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

import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';

/**
 * The identity headers a development run of a Wow service reads the caller
 * from (the host's development callers, in a profile of `wow.dev-profiles`,
 * when CoSec is off): any client can set them, so a deployment never reads
 * them. Without {@link DevelopmentCallerHeaders.USER} the request is the
 * anonymous caller of the default tenant, and only
 * {@link DevelopmentCallerHeaders.APP} is kept.
 *
 * A console that offers a local run's caller, and a test, send them with
 * {@link developmentCaller}; `@ahoo-wang/wow-client/testing` exports it
 * too.
 */
export const DevelopmentCallerHeaders = {
  /** The caller's subject. */
  USER: 'Wow-User',
  /** The owner of its scope: an owner-level caller; a tenant-level one without it. */
  OWNER: 'Wow-Owner',
  /** Its tenant; the service's default tenant without it. */
  TENANT: 'Wow-Tenant',
  /** Its roles, comma-separated. */
  ROLES: 'Wow-Roles',
  /** The application it calls in, which each command records as its `app_id`. */
  APP: 'Wow-App',
} as const;

/**
 * Who a request comes from, as a development run of a Wow service reads it
 * ({@link DevelopmentCallerHeaders}).
 */
export interface DevelopmentCaller {
  /** The caller's subject. */
  readonly user: string;
  /**
   * The owner of its scope, for an owner-level caller; a tenant-level caller
   * without it. The owner is never the user unless it says so.
   */
  readonly owner?: string;
  /** Its tenant; the service's default tenant (`wow.tenant.default`) without it. */
  readonly tenant?: string;
  /** Its roles. */
  readonly roles?: readonly string[];
  /** The application it calls in. */
  readonly app?: string;
}

/** The headers that name `caller` to a development run: only those it sets. */
export function developmentCallerHeaders(
  caller: DevelopmentCaller,
): Record<string, string> {
  const headers: Record<string, string> = {
    [DevelopmentCallerHeaders.USER]: caller.user,
  };
  if (caller.owner !== undefined)
    headers[DevelopmentCallerHeaders.OWNER] = caller.owner;
  if (caller.tenant !== undefined)
    headers[DevelopmentCallerHeaders.TENANT] = caller.tenant;
  if (caller.roles?.length)
    headers[DevelopmentCallerHeaders.ROLES] = caller.roles.join(',');
  if (caller.app !== undefined)
    headers[DevelopmentCallerHeaders.APP] = caller.app;
  return headers;
}

/**
 * A request interceptor that sends every request as `caller`, by the headers
 * a development run reads ({@link developmentCallerHeaders}): a fetcher that
 * uses it asks as that caller, as a host's CoSec interceptors send its token.
 * Given a function, it sends the caller the function answers at each request,
 * and nothing when it answers none.
 *
 * ```ts
 * const fetcher = new Fetcher({ baseURL });
 * fetcher.interceptors.request.use(
 *   developmentCaller({ user: 'alice', owner: 'alice', tenant: 'acme' }),
 * );
 * ```
 */
export function developmentCaller(
  caller: DevelopmentCaller | (() => DevelopmentCaller | null | undefined),
): RequestInterceptor {
  const current = typeof caller === 'function' ? caller : () => caller;
  return {
    name: 'DevelopmentCaller',
    order: 0,
    intercept(exchange: FetchExchange): void {
      const asking = current();
      if (!asking) return;
      Object.assign(
        exchange.ensureRequestHeaders(),
        developmentCallerHeaders(asking),
      );
    },
  };
}
