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
  localStorageSnapshot,
  systemInstanceId,
} from "@ahoo-wang/wow-view-engine";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  executionFailedDefinition,
  EXECUTION_FAILED,
} from "./executionFailed.ts";
import {
  createLocalViewStore,
  localViewPermissions,
  VIEW_STORE_KEY,
} from "./localViewStore.ts";

const activeConfig = executionFailedDefinition("en").views![0].config;

describe("localViewStore", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

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

  it("starts empty from a missing, unreadable or malformed entry", async () => {
    expect(await createLocalViewStore().list(EXECUTION_FAILED)).toEqual([]);

    for (const corrupt of [
      "{not json",
      JSON.stringify({ instances: {} }),
      "null",
    ]) {
      localStorage.setItem(VIEW_STORE_KEY, corrupt);
      expect(await createLocalViewStore().list(EXECUTION_FAILED)).toEqual([]);
    }
  });

  it("reads the views this console stored before, as they are", async () => {
    const saved = {
      id: `${EXECUTION_FAILED}-1`,
      definitionId: EXECUTION_FAILED,
      title: "Stored earlier",
      scope: "personal",
      revision: "3",
      config: activeConfig,
    };
    const preferences = {
      [EXECUTION_FAILED]: {
        order: [saved.id],
        defaultInstanceId: saved.id,
        revision: "1",
      },
    };
    localStorage.setItem(
      VIEW_STORE_KEY,
      JSON.stringify({ instances: [saved], preferences }),
    );

    const store = createLocalViewStore();

    expect(await store.get(saved.id)).toEqual(saved);
    expect(await store.getPreferences(EXECUTION_FAILED)).toEqual(
      preferences[EXECUTION_FAILED],
    );
  });

  it("fails a save the browser refuses rather than keeping it for this visit only", async () => {
    const refusing = localStorageSnapshot(VIEW_STORE_KEY, {
      events: null,
      storage: () => {
        throw new DOMException("denied", "SecurityError");
      },
    });

    const store = createLocalViewStore(refusing);
    await expect(
      store.create(
        {
          definitionId: EXECUTION_FAILED,
          title: "Nowhere to keep it",
          scope: "personal",
          config: activeConfig,
        },
        { requestId: "create-2" },
      ),
    ).rejects.toMatchObject({ code: "UNAVAILABLE" });
    expect(await store.list(EXECUTION_FAILED)).toEqual([]);
  });

  it("fails a save past the quota", async () => {
    vi.spyOn(localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });

    await expect(
      createLocalViewStore().create(
        {
          definitionId: EXECUTION_FAILED,
          title: "Too much",
          scope: "personal",
          config: activeConfig,
        },
        { requestId: "create-3" },
      ),
    ).rejects.toMatchObject({ code: "UNAVAILABLE" });
  });

  it("keeps a board saved in one tab when another tab reorders", async () => {
    const tabA = createLocalViewStore();
    const tabB = createLocalViewStore();
    const preferences = await tabB.getPreferences(EXECUTION_FAILED);

    const board = await tabA.create(
      {
        definitionId: EXECUTION_FAILED,
        title: "From tab A",
        scope: "personal",
        config: activeConfig,
      },
      { requestId: "create-a" },
    );
    await tabB.setPreferences(
      EXECUTION_FAILED,
      { ...preferences, order: [] },
      { requestId: "reorder-b" },
    );

    expect(
      (await createLocalViewStore().list(EXECUTION_FAILED)).map(({ id }) => id),
    ).toEqual([board.id]);
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
