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
import { SOURCES } from "@/views/sources.ts";
import { createCatalogServices } from "./catalog.ts";
import { accessToken } from "./testing.ts";
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

/** The catalog, answering every request with `status` (and `body`). */
function service(status: number, body = status === 200 ? "[]" : "{}") {
  const fetch = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(
    async () =>
      new Response(body, {
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
    expect(sent(fetch)).not.toHaveProperty(DevelopmentCallerHeaders.USER);
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
    // The sign-in page says why: a token still valid was refused.
    expect(callers.signedOut()).toBe("refused");
    // Naming a caller again clears it.
    callers.set(VIEWER);
    expect(callers.signedOut()).toBeNull();
  });

  it("say a token that ran out was refused for that", async () => {
    service(401);
    const tokens = new TokenStorage();
    const callers = new CallerStore(null, tokens);
    const services = createCatalogServices(tokens, callers);
    const now = Date.now();
    callers.signIn(accessToken({ sub: "alice" }, Math.floor(now / 1000) + 60));
    vi.useFakeTimers({ now: now + 120_000, toFake: ["Date"] });
    try {
      await expect(services.datasets.refresh()).rejects.toThrow();
    } finally {
      vi.useRealTimers();
    }
    expect(callers.get()).toBeNull();
    expect(callers.signedOut()).toBe("expired");
  });

  it("sign out a caller of headers the service refuses: a deployment reads none", async () => {
    const fetch = service(401);
    const callers = new CallerStore(null, new TokenStorage());
    const services = createCatalogServices(undefined, callers);
    callers.set(VIEWER);
    await expect(services.datasets.refresh()).rejects.toThrow();
    expect(sent(fetch)).toMatchObject({
      [DevelopmentCallerHeaders.USER]: "victor",
    });
    // Panels saying "no credentials accepted here" under a caller still
    // shown as signed in told the data owner nothing.
    expect(callers.get()).toBeNull();
    expect(callers.signedOut()).toBe("headers-refused");
  });

  it("read the catalog's settings once, by its service route", async () => {
    const settings = {
      connection: "warehouse",
      requireSourceApproval: false,
      requirePublicationApproval: true,
      rolesDecide: true,
    };
    const fetch = service(200, JSON.stringify(settings));
    const callers = new CallerStore(null, new TokenStorage());
    const services = createCatalogServices(undefined, callers);
    callers.set(VIEWER);
    expect(services.settings.get()).toEqual({ status: "reading" });
    services.settings.load();
    services.settings.load();
    await vi.waitFor(() =>
      expect(services.settings.settings()).toEqual(settings),
    );
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(String(fetch.mock.calls[0][0])).toMatch(
      /\/analytics-catalog\/settings$/,
    );
    // Where a source takes no approver, no view of the sources awaits one.
    expect(
      services.engine.definitions.get(SOURCES)?.views?.map((view) => view.id),
    ).not.toContain("unapproved");
  });

  it("offer everything when the settings cannot be read, and read them again later", async () => {
    service(404);
    const services = createCatalogServices(
      undefined,
      new CallerStore(null, new TokenStorage()),
    );
    services.settings.load();
    await vi.waitFor(() =>
      expect(services.settings.get()).toEqual({ status: "failed" }),
    );
    expect(
      services.engine.definitions.get(SOURCES)?.views?.map((view) => view.id),
    ).toContain("unapproved");
    services.settings.load();
    expect(services.settings.get()).toEqual({ status: "reading" });
  });

  it("say a node without a dataset backend serves none, rather than its 404", async () => {
    // What the web server answers for a path no route serves.
    service(
      404,
      JSON.stringify({
        type: "about:blank",
        title: "Not Found",
        status: 404,
        detail: "No static resource wow/dataset.",
      }),
    );
    const services = createCatalogServices(
      undefined,
      new CallerStore(null, new TokenStorage()),
    );
    expect(services.serving.get()).toBeNull();
    await expect(services.datasets.refresh()).resolves.toMatchObject({
      datasets: [],
    });
    expect(services.serving.get()).toBe(false);
  });

  it("say a failure with a code as it is: a route answered it", async () => {
    service(
      404,
      JSON.stringify({
        type: "about:blank",
        title: "Not Found",
        status: 404,
        detail: "No such catalog.",
        code: "wow.not-found",
      }),
    );
    const services = createCatalogServices(
      undefined,
      new CallerStore(null, new TokenStorage()),
    );
    await expect(services.datasets.refresh()).rejects.toThrow();
    expect(services.serving.get()).toBeNull();
  });

  it("refresh no token: the data owner signs in again", async () => {
    await expect(
      noRefresh.refresh({ accessToken: "a", refreshToken: "a" }),
    ).rejects.toThrow("sign in again");
  });
});
