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

import { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CallerStore, type CatalogCaller } from "./caller.ts";
import { APP_ID, noRefresh } from "./cosec.ts";
import { createCatalogServices } from "./catalog.ts";
import { accessToken } from "./testing.ts";

/** The catalog, answering every request with `status`. */
function service(status: number) {
  const fetch = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(
    async () =>
      new Response(status === 200 ? "[]" : "{}", {
        status,
        headers: {
          "Content-Type":
            status === 200 ? "application/json" : "application/problem+json",
        },
      }),
  );
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

function sent(fetch: ReturnType<typeof service>): Record<string, string> {
  return (fetch.mock.calls[0][1].headers ?? {}) as Record<string, string>;
}

const VIEWER: CatalogCaller = { user: "victor", tenant: "(0)", roles: [] };

describe("the catalog services", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it("send the CoSec token signed in, with the console's application", async () => {
    const fetch = service(200);
    const tokens = new TokenStorage();
    const callers = new CallerStore(null, tokens);
    const services = createCatalogServices(tokens, callers);
    const token = accessToken({ sub: "alice" });
    callers.signIn(token);
    await services.datasets.refresh();
    expect(sent(fetch)).toMatchObject({
      Authorization: `Bearer ${token}`,
      "CoSec-App-Id": APP_ID,
    });
    expect(sent(fetch)).not.toHaveProperty("X-User");
  });

  it("sign the console out when the service refuses its token", async () => {
    service(401);
    const tokens = new TokenStorage();
    const callers = new CallerStore(null, tokens);
    const services = createCatalogServices(tokens, callers);
    callers.signIn(accessToken({ sub: "alice" }));
    await expect(services.datasets.refresh()).rejects.toThrow();
    expect(callers.get()).toBeNull();
    expect(tokens.authenticated).toBe(false);
  });

  it("keep a caller of headers the service refuses: it names another", async () => {
    const fetch = service(401);
    const callers = new CallerStore(null, new TokenStorage());
    const services = createCatalogServices(undefined, callers);
    callers.set(VIEWER);
    await expect(services.datasets.refresh()).rejects.toThrow();
    expect(callers.get()).toEqual(VIEWER);
    expect(sent(fetch)).toMatchObject({ "X-User": "victor" });
  });

  it("refresh no token: the data owner signs in again", async () => {
    await expect(
      noRefresh.refresh({ accessToken: "a", refreshToken: "a" }),
    ).rejects.toThrow("sign in again");
  });
});
