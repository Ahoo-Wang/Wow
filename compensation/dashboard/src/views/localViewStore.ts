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
  localStorageSnapshot,
  MemoryViewStore,
  type MemorySnapshot,
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
 * The view store of the console until stage 6: this browser, this user.
 *
 * The views are kept by the engine's `localStorageSnapshot` under
 * `VIEW_STORE_KEY`, in the format this console has always written there. A
 * write the browser refuses is a failed save rather than a view that is gone
 * on the next load, and two tabs never overwrite each other: a write merges
 * into what the other tab stored, or is a conflict when the other tab has
 * already changed the same view.
 */
export function createLocalViewStore(
  snapshot: MemorySnapshot = localStorageSnapshot(VIEW_STORE_KEY),
): MemoryViewStore {
  return new MemoryViewStore({
    snapshot,
    permissions: localViewPermissions,
  });
}
