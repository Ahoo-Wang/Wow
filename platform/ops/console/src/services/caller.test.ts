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
import { describe, expect, it, vi } from "vitest";
import {
  CallerHeaders,
  CallerStore,
  DEFAULT_TENANT,
  isOperator,
  parseRoles,
  readCaller,
  type OpsCaller,
} from "./caller.ts";

const ALICE: OpsCaller = {
  user: "alice",
  tenant: "(0)",
  roles: ["viewer", "ops.operator"],
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
    expect(parseRoles(" viewer, ops.operator ,,")).toEqual([
      "viewer",
      "ops.operator",
    ]);
  });

  it("is an operator with ops.operator only", () => {
    expect(isOperator(ALICE)).toBe(true);
    expect(isOperator({ ...ALICE, roles: ["viewer"] })).toBe(false);
    expect(isOperator(null)).toBe(false);
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

  it("is sent as the headers the ops service reads, and nothing without one", () => {
    let caller: OpsCaller | null = ALICE;
    const interceptor = new CallerHeaders(() => caller);
    const sent = exchange();
    interceptor.intercept(sent);
    expect(sent.headers).toEqual({
      "X-User": "alice",
      "X-Tenant": "(0)",
      "X-Role": "viewer,ops.operator",
    });
    caller = { ...ALICE, roles: [] };
    const noRoles = exchange();
    interceptor.intercept(noRoles);
    expect(noRoles.headers).not.toHaveProperty("X-Role");
    caller = null;
    const anonymous = exchange();
    interceptor.intercept(anonymous);
    expect(anonymous.headers).toEqual({});
  });
});
