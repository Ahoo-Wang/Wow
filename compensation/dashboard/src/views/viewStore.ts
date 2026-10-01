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
  UrlBuilder,
  type FetchExchange,
  type RequestInterceptor,
} from "@ahoo-wang/fetcher";
import {
  CoSecHeaders,
  RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER,
} from "@ahoo-wang/fetcher-cosec";
import {
  isSystemInstanceId,
  type ViewPermissions,
} from "@ahoo-wang/wow-view-engine";
import { SHARED_OWNER_ID, WowViewStore } from "@ahoo-wang/wow-view-store";
import { coSecConfigurer } from "@/services/cosec.ts";

/**
 * The console's saved views and boards live in the compensation service,
 * which embeds the Wow view store (`/view-store/tenant/{tenantId}/owner/
 * {ownerId}/…`). Nobody signs in to the console, so no token names the
 * tenant or the owner: the console fills its own defaults, and with the
 * owner `(shared)` every view and every preference it keeps is shared by
 * everyone who opens it — there are no personal views.
 */

/** Wow's default tenant: the console's failures carry no tenant either. */
export const CONSOLE_TENANT_ID = "(0)";

/** The application the console's views belong to; `CoSecConfigurer` sends it. */
export const CONSOLE_APP_ID = coSecConfigurer.config.appId;

/**
 * Fills the tenant and the owner of a view store path, and the application,
 * where the request has none of its own: after fetcher-cosec's resource
 * attribution, so that a token, if a deployment puts one in front, still
 * names them.
 */
export class ConsoleViewStoreDefaults implements RequestInterceptor {
  readonly name = "ConsoleViewStoreDefaults";
  readonly order = RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER + 1;

  intercept(exchange: FetchExchange): void {
    const path = exchange.ensureRequestUrlParams().path;
    path.tenantId ??= CONSOLE_TENANT_ID;
    path.ownerId ??= SHARED_OWNER_ID;
    const headers = exchange.ensureRequestHeaders();
    headers[CoSecHeaders.APP_ID] ??= CONSOLE_APP_ID;
  }
}

/**
 * Shared views only: anyone may create, save, rename, delete, reorder and
 * set the default, as anyone may act on a failure in this console; a system
 * view is never written, and with no personal views there is no audience to
 * change.
 */
export function consoleViewPermissions(): ViewPermissions {
  return {
    createPersonal: false,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: (id) => {
      const editable = !isSystemInstanceId(id);
      return {
        save: editable,
        rename: editable,
        delete: editable,
        changeAudience: false,
      };
    },
  };
}

/** The fetcher the view store asks with: the console's CoSec and its defaults. */
export function consoleViewStoreFetcher(
  baseURL: string | undefined = import.meta.env.VITE_API_BASE_URL,
): Fetcher {
  const viewStoreFetcher = new Fetcher();
  viewStoreFetcher.urlBuilder = new UrlBuilder(baseURL ?? "");
  coSecConfigurer.applyTo(viewStoreFetcher);
  viewStoreFetcher.interceptors.request.use(new ConsoleViewStoreDefaults());
  return viewStoreFetcher;
}

/** The console's view store, on the compensation service. */
export function createConsoleViewStore(
  fetcher: Fetcher = consoleViewStoreFetcher(),
): WowViewStore {
  return new WowViewStore({ fetcher, permissions: consoleViewPermissions });
}
