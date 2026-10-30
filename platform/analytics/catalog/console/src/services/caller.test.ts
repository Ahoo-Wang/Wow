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

import type { FetchExchange } from "@ahoo-wang/fetcher";
import { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  callerHeaders,
  CallerStore,
  DEFAULT_TENANT,
  hasRole,
  isApprover,
  isEditor,
  parseRoles,
  readCaller,
  tokenCaller,
  type CatalogCaller,
} from "./caller.ts";
import { accessToken } from "./testing.ts";
import { DevelopmentCallerHeaders } from "@ahoo-wang/wow-client";

const ALICE: CatalogCaller = {
  user: "alice",
  tenant: "(0)",
  roles: ["viewer", "analytics.catalog.editor"],
};

function exchange(): FetchExchange & { headers: Record<string, string> } {
  const headers: Record<string, string> = {};
  return {
    headers,
    ensureRequestHeaders: () => headers,
  } as unknown as FetchExchange & { headers: Record<string, string> };
}

describe("the caller", () => {
  it("reads roles as a header lists them", () => {
    expect(parseRoles(" viewer, analytics.catalog.editor ,,")).toEqual([
      "viewer",
      "analytics.catalog.editor",
    ]);
  });

  it("edits as an editor or an admin, and approves as an approver only", () => {
    expect(isEditor(ALICE)).toBe(true);
    expect(isEditor({ ...ALICE, roles: ["analytics.catalog.admin"] })).toBe(
      true,
    );
    expect(isEditor({ ...ALICE, roles: ["viewer"] })).toBe(false);
    expect(isEditor(null)).toBe(false);
    expect(
      isApprover({ ...ALICE, roles: ["analytics.catalog.approver"] }),
    ).toBe(true);
    expect(isApprover({ ...ALICE, roles: ["analytics.catalog.admin"] })).toBe(
      false,
    );
    expect(hasRole(null, "viewer")).toBe(false);
  });

  it("is read back from storage, or not at all", () => {
    expect(readCaller(JSON.stringify(ALICE))).toEqual(ALICE);
    expect(
      readCaller(JSON.stringify({ user: " bob ", roles: [1, "x"] })),
    ).toEqual({ user: "bob", tenant: DEFAULT_TENANT, roles: ["x"] });
    expect(readCaller(JSON.stringify({ user: "" }))).toBeNull();
    expect(readCaller("{not json")).toBeNull();
    expect(readCaller(null)).toBeNull();
  });

  it("is kept on this machine, and its change told", () => {
    const store = new CallerStore(localStorage);
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);
    store.set(ALICE);
    expect(store.get()).toEqual(ALICE);
    expect(new CallerStore(localStorage).get()).toEqual(ALICE);
    store.set(null);
    expect(new CallerStore(localStorage).get()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
    store.set(ALICE);
    expect(listener).toHaveBeenCalledTimes(2);
    store.set(null);
  });

  it("works without storage", () => {
    const store = new CallerStore(null);
    store.set(ALICE);
    expect(store.get()).toEqual(ALICE);
  });

  it("reads no caller from a storage the browser refuses, and still keeps one in memory", () => {
    // Blocked site data: every read of the storage throws.
    const refusing = {
      getItem: () => {
        throw new DOMException("blocked", "SecurityError");
      },
      setItem: () => {},
      removeItem: () => {},
    } as unknown as Storage;
    const store = new CallerStore(refusing);
    expect(store.get()).toBeNull();
    store.set(ALICE);
    expect(store.get()).toEqual(ALICE);
  });

  it("uses the page's storage by default, and none when the page may not reach it", () => {
    localStorage.clear();
    new CallerStore().set(ALICE);
    expect(new CallerStore().get()).toEqual(ALICE);
    localStorage.clear();
    const original = Object.getOwnPropertyDescriptor(window, "localStorage")!;
    Object.defineProperty(window, "localStorage", {
      configurable: true,
      get() {
        throw new DOMException("blocked", "SecurityError");
      },
    });
    try {
      const store = new CallerStore();
      expect(store.get()).toBeNull();
      store.set(ALICE);
      expect(store.get()).toEqual(ALICE);
    } finally {
      Object.defineProperty(window, "localStorage", original);
    }
  });

  it("is sent as the headers the catalog reads, and nothing without one", () => {
    let caller: CatalogCaller | null = ALICE;
    const interceptor = callerHeaders(() => caller);
    const sent = exchange();
    interceptor.intercept(sent);
    expect(sent.headers).toEqual({
      [DevelopmentCallerHeaders.USER]: "alice",
      [DevelopmentCallerHeaders.TENANT]: "(0)",
      [DevelopmentCallerHeaders.ROLES]: "viewer,analytics.catalog.editor",
    });
    caller = { ...ALICE, roles: [] };
    const noRoles = exchange();
    interceptor.intercept(noRoles);
    expect(noRoles.headers).not.toHaveProperty(DevelopmentCallerHeaders.ROLES);
    caller = null;
    const anonymous = exchange();
    interceptor.intercept(anonymous);
    expect(anonymous.headers).toEqual({});
  });

  it("is not sent as headers when it is a CoSec token's", () => {
    const sent = exchange();
    callerHeaders(() => ({ ...ALICE, via: "cosec" })).intercept(sent);
    expect(sent.headers).toEqual({});
  });
});

describe("the CoSec sign-in", () => {
  afterEach(() => localStorage.clear());

  it("reads the caller from the token: its subject, tenant and roles", () => {
    const tokens = new TokenStorage();
    expect(tokenCaller(tokens)).toBeNull();
    expect(tokenCaller(null)).toBeNull();
    const token = accessToken({
      sub: "alice",
      roles: ["analytics.catalog.editor"],
    });
    tokens.signIn({ accessToken: token, refreshToken: token });
    expect(tokenCaller(tokens)).toEqual({
      user: "alice",
      tenant: DEFAULT_TENANT,
      roles: ["analytics.catalog.editor"],
      via: "cosec",
      // When it stops being one, to say why it was signed out.
      expiresAt: expect.any(Number),
    });
    const other = accessToken({ sub: "olga", tenantId: "t-1" });
    tokens.signIn({ accessToken: other, refreshToken: other });
    expect(tokenCaller(tokens)).toMatchObject({ tenant: "t-1", roles: [] });
  });

  it("signs in over a caller of headers, and out again", () => {
    const store = new CallerStore(localStorage, new TokenStorage());
    const listener = vi.fn();
    store.subscribe(listener);
    store.set(ALICE);
    expect(store.signsIn).toBe(true);
    expect(store.signIn(accessToken({ sub: "bob" }))).toBe(true);
    expect(store.get()).toMatchObject({ user: "bob", via: "cosec" });
    // The single sign-on: another console of this browser is signed in too.
    expect(new CallerStore(localStorage, new TokenStorage()).get()?.user).toBe(
      "bob",
    );
    store.set(null);
    expect(store.get()).toBeNull();
    expect(new CallerStore(localStorage, new TokenStorage()).get()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it("refuses a token that is not one, or has expired, and keeps the caller", () => {
    const store = new CallerStore(null, new TokenStorage());
    store.set(ALICE);
    expect(store.signIn("not-a-token")).toBe(false);
    expect(
      store.signIn(accessToken({ sub: "bob" }, Date.now() / 1000 - 60)),
    ).toBe(false);
    expect(store.get()).toEqual(ALICE);
    expect(new CallerStore(null).signIn(accessToken({ sub: "bob" }))).toBe(
      false,
    );
    expect(new CallerStore(null).signsIn).toBe(false);
  });
});
