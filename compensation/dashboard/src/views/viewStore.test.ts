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

import { Fetcher, type FetchExchange } from "@ahoo-wang/fetcher";
import { systemInstanceId } from "@ahoo-wang/wow-view-engine";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import {
  CONSOLE_APP_ID,
  CONSOLE_TENANT_ID,
  ConsoleViewStoreDefaults,
  consoleViewPermissions,
  consoleViewStoreFetcher,
  createConsoleViewStore,
} from "./viewStore.ts";

interface Sent {
  method: string;
  url: string;
  appId: string | null;
}

/** Answers every request with `[]`, and keeps what was asked. */
function recordRequests(): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      sent.push({
        method: request.method,
        url: request.url,
        appId: request.headers.get("CoSec-App-Id"),
      });
      return new Response("[]", {
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
  return sent;
}

function exchangeWith(
  path: Record<string, string>,
  headers: Record<string, string>,
): FetchExchange {
  const request = { url: "/x", urlParams: { path }, headers };
  return {
    request,
    ensureRequestUrlParams: () => request.urlParams,
    ensureRequestHeaders: () => request.headers,
  } as unknown as FetchExchange;
}

describe("the console's view store", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("asks the compensation service as the console: its tenant, the shared owner, its application", async () => {
    const sent = recordRequests();
    const store = createConsoleViewStore(
      consoleViewStoreFetcher("http://console.test/"),
    );

    expect(await store.list(EXECUTION_FAILED)).toEqual([]);

    const scope = `http://console.test/view-store/tenant/${encodeURIComponent(
      CONSOLE_TENANT_ID,
    )}/owner/${encodeURIComponent("(shared)")}`;
    expect(sent.map(({ method, url }) => `${method} ${url}`).sort()).toEqual(
      [
        `GET ${scope}/system-views?definitionId=${EXECUTION_FAILED}`,
        `POST ${scope}/view/snapshot/list`,
        `POST ${scope}/view/snapshot/list`,
      ].sort(),
    );
    expect(new Set(sent.map(({ appId }) => appId))).toEqual(
      new Set([CONSOLE_APP_ID]),
    );
    expect(CONSOLE_APP_ID).toBe("compensation-dashboard");
  });

  it("fills only what the request leaves out", () => {
    const defaults = new ConsoleViewStoreDefaults();
    const empty = exchangeWith({}, {});
    defaults.intercept(empty);
    expect(empty.ensureRequestUrlParams().path).toEqual({
      tenantId: "(0)",
      ownerId: "(shared)",
    });
    expect(empty.ensureRequestHeaders()["CoSec-App-Id"]).toBe(
      "compensation-dashboard",
    );

    const named = exchangeWith(
      { tenantId: "t1", ownerId: "alice" },
      { "CoSec-App-Id": "portal" },
    );
    defaults.intercept(named);
    expect(named.ensureRequestUrlParams().path).toEqual({
      tenantId: "t1",
      ownerId: "alice",
    });
    expect(named.ensureRequestHeaders()["CoSec-App-Id"]).toBe("portal");
  });

  it("runs after fetcher-cosec's resource attribution, which a token would fill first", () => {
    const fetcher = consoleViewStoreFetcher("http://console.test/");
    const names = fetcher.interceptors.request.interceptors.map(
      ({ name }) => name,
    );
    expect(names.indexOf("ConsoleViewStoreDefaults")).toBeGreaterThan(
      names.indexOf("ResourceAttributionRequestInterceptor"),
    );
    expect(fetcher).toBeInstanceOf(Fetcher);
  });

  it("offers shared views only, never writes a system view, and has no audience to change", () => {
    const permissions = consoleViewPermissions();
    expect(permissions).toMatchObject({
      createPersonal: false,
      createShared: true,
      reorder: true,
      setDefault: true,
    });
    expect(
      permissions.instance(systemInstanceId(EXECUTION_FAILED, "active")),
    ).toEqual({
      save: false,
      rename: false,
      delete: false,
      changeAudience: false,
    });
    expect(permissions.instance("view-1")).toEqual({
      save: true,
      rename: true,
      delete: true,
      changeAudience: false,
    });
  });
});
