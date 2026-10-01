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
 * The view store servers the `WowViewStore` suites run against, and the
 * callers they act as.
 *
 * A caller is a fetcher as a signed-in host has one: fetcher-cosec's
 * `ResourceAttributionRequestInterceptor` fills `{tenantId}` and
 * `{ownerId}` from the token's `tenantId` and `sub` where the store leaves
 * them out, and an interceptor sends `CoSec-App-Id` (fetcher-cosec's
 * `CoSecRequestInterceptor` in a host, which also needs a device id). The
 * server has no CoSec gateway in front of it, so the token is never checked:
 * the suites act as whoever they name.
 */

import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  CoSecHeaders,
  ResourceAttributionRequestInterceptor,
  TokenStorage,
} from '@ahoo-wang/fetcher-cosec';
import { exampleServerURL } from '../../src/wow/exampleFetcher';

/**
 * The standalone view store server (`wow-view-store-server`):
 * `WOW_VIEW_STORE_URL`, or `http://localhost:8090/` as in CI.
 */
export const viewStoreServerURL =
  process.env.WOW_VIEW_STORE_URL ?? 'http://localhost:8090/';

/** A server that serves the view store's routes. */
export interface ViewStoreServer {
  /** How the suites name it. */
  name: string;
  url: string;
}

/**
 * Every server the suites run against: the standalone view store server, and
 * the example server, which embeds `wow-view-store-starter` beside its own
 * aggregates (and wow-cosec). Both are started with the same system view.
 */
export const viewStoreServers: readonly ViewStoreServer[] = [
  { name: 'the view store server', url: viewStoreServerURL },
  { name: 'the example server', url: exampleServerURL },
];

/**
 * The definition the servers serve a system view for: CI starts each with
 * `wow.view-store.system-views[0]` set to it (README.md).
 */
export const SYSTEM_VIEW_DEFINITION = 'conformance-system';

/** Who a request comes from. */
export interface Caller {
  tenantId: string;
  /** The token's `sub`: the owner of the caller's personal views. */
  owner: string;
  appId: string;
}

/** An unsigned token with `payload`; nothing in front of the server checks it. */
function token(payload: Record<string, unknown>): string {
  const part = (value: unknown) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${part({ alg: 'none', typ: 'JWT' })}.${part(payload)}.signature`;
}

/** A `Storage` of this caller's own, so no two callers share a token. */
class MemoryStorage implements Storage {
  private readonly items = new Map<string, string>();
  get length(): number {
    return this.items.size;
  }
  clear(): void {
    this.items.clear();
  }
  getItem(key: string): string | null {
    return this.items.get(key) ?? null;
  }
  key(index: number): string | null {
    return [...this.items.keys()][index] ?? null;
  }
  removeItem(key: string): void {
    this.items.delete(key);
  }
  setItem(key: string, value: string): void {
    this.items.set(key, value);
  }
}

class AppId implements RequestInterceptor {
  readonly name = 'ConformanceAppId';
  readonly order = 0;
  constructor(private readonly appId: string) {}
  intercept(exchange: FetchExchange): void {
    exchange.ensureRequestHeaders()[CoSecHeaders.APP_ID] = this.appId;
  }
}

/** A fetcher on `server` that asks as `caller`. */
export function actingAs(caller: Caller, server: ViewStoreServer): Fetcher {
  const tokenStorage = new TokenStorage({
    key: `cosec-token-${caller.tenantId}-${caller.owner}`,
    storage: new MemoryStorage(),
  });
  const exp = Math.floor(Date.now() / 1000) + 24 * 60 * 60;
  tokenStorage.setCompositeToken({
    accessToken: token({ sub: caller.owner, tenantId: caller.tenantId, exp }),
    refreshToken: token({ sub: caller.owner, exp }),
  });
  const fetcher = new Fetcher({ baseURL: server.url });
  fetcher.interceptors.request.use(
    new ResourceAttributionRequestInterceptor({ tokenStorage }),
  );
  fetcher.interceptors.request.use(new AppId(caller.appId));
  return fetcher;
}
