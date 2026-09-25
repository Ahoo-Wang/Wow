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

import { systemInstanceId } from "@ahoo-wang/wow-view-engine";
import { beforeEach, describe, expect, it } from "vitest";
import {
  executionFailedDefinition,
  EXECUTION_FAILED,
} from "./executionFailed.ts";
import {
  createLocalViewStore,
  localStorageSnapshot,
  localViewPermissions,
  VIEW_STORE_KEY,
} from "./localViewStore.ts";

const activeConfig = executionFailedDefinition("en").views![0].config;

describe("localViewStore", () => {
  beforeEach(() => localStorage.clear());

  it("keeps a personal view across stores, as across page loads", async () => {
    const first = createLocalViewStore();
    const created = await first.create(
      {
        definitionId: EXECUTION_FAILED,
        title: "My failures",
        scope: "personal",
        config: activeConfig,
      },
      { requestId: "create-1" },
    );
    expect(localStorage.getItem(VIEW_STORE_KEY)).toContain("My failures");

    const reloaded = createLocalViewStore();
    expect(
      (await reloaded.list(EXECUTION_FAILED)).map(({ title }) => title),
    ).toEqual(["My failures"]);
    expect((await reloaded.get(created.id)).config).toEqual(activeConfig);
  });

  it("starts empty from a missing, unreadable or malformed entry", () => {
    const snapshot = localStorageSnapshot();
    expect(snapshot.load()).toBeUndefined();

    localStorage.setItem(VIEW_STORE_KEY, "{not json");
    expect(snapshot.load()).toBeUndefined();

    localStorage.setItem(VIEW_STORE_KEY, JSON.stringify({ instances: {} }));
    expect(snapshot.load()).toBeUndefined();

    localStorage.setItem(VIEW_STORE_KEY, "null");
    expect(snapshot.load()).toBeUndefined();
  });

  it("works on in memory when the browser refuses storage", async () => {
    const refusing = localStorageSnapshot(() => {
      throw new Error("SecurityError");
    });
    expect(refusing.load()).toBeUndefined();
    expect(() =>
      refusing.save({ instances: [], preferences: {} }),
    ).not.toThrow();

    const store = createLocalViewStore(refusing);
    await store.create(
      {
        definitionId: EXECUTION_FAILED,
        title: "Only in memory",
        scope: "personal",
        config: activeConfig,
      },
      { requestId: "create-2" },
    );
    expect(await store.list(EXECUTION_FAILED)).toHaveLength(1);
  });

  it("offers personal views only, and never writes a system view", () => {
    const permissions = localViewPermissions();
    expect(permissions.createPersonal).toBe(true);
    expect(permissions.createShared).toBe(false);
    expect(
      permissions.instance(systemInstanceId(EXECUTION_FAILED, "active")),
    ).toEqual({ save: false, rename: false, delete: false });
    expect(permissions.instance(`${EXECUTION_FAILED}-1`)).toEqual({
      save: true,
      rename: true,
      delete: true,
    });
  });
});
