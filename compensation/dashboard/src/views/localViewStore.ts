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
  isSystemInstanceId,
  MemoryViewStore,
  type MemorySnapshot,
  type MemoryState,
  type ViewPermissions,
} from "@ahoo-wang/wow-view-engine";

/** Where the saved views of this browser live. */
export const VIEW_STORE_KEY = "wow-compensation-dashboard:views";

/**
 * Personal views only: local storage has no "someone else" to share with, so
 * shared views are not offered until the Wow storage backend (stage 6).
 * System views are read-only whatever this says; the engine refuses them.
 */
export function localViewPermissions(): ViewPermissions {
  return {
    createPersonal: true,
    createShared: false,
    reorder: true,
    setDefault: true,
    instance: (id) => {
      const editable = !isSystemInstanceId(id);
      return { save: editable, rename: editable, delete: editable };
    },
  };
}

/**
 * The whole store as one JSON document in `localStorage`
 * (`MemoryViewStore`'s snapshot hook). A missing, blocked or unreadable entry
 * starts empty rather than breaking the page, and a write that storage
 * refuses (quota, private mode) leaves the views in memory for this visit.
 */
export function localStorageSnapshot(
  storage: () => Storage = () => window.localStorage,
  key: string = VIEW_STORE_KEY,
): MemorySnapshot {
  return {
    load() {
      try {
        const saved = storage().getItem(key);
        if (!saved) return undefined;
        const state = JSON.parse(saved) as Partial<MemoryState> | null;
        if (
          !state ||
          !Array.isArray(state.instances) ||
          typeof state.preferences !== "object" ||
          state.preferences === null
        )
          return undefined;
        return { instances: state.instances, preferences: state.preferences };
      } catch {
        return undefined;
      }
    },
    save(state) {
      try {
        storage().setItem(key, JSON.stringify(state));
      } catch {
        // Kept in memory for this visit; nothing else to do in a browser
        // that will not store it.
      }
    },
  };
}

/** The view store of the console until stage 6: this browser, this user. */
export function createLocalViewStore(
  snapshot: MemorySnapshot = localStorageSnapshot(),
): MemoryViewStore {
  return new MemoryViewStore({
    snapshot,
    permissions: localViewPermissions,
  });
}
