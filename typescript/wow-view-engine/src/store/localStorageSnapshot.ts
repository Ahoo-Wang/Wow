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

import type { ViewInstance } from '../model/index.js';
import type { MemorySnapshot, MemoryState } from './MemoryViewStore.js';

/**
 * Where a `localStorageSnapshot` reads and writes. Both are structural, so
 * this layer names no DOM type, and a test hands in a storage of its own.
 */
export interface LocalStorageSnapshotOptions {
  /**
   * The storage, asked for at each read and write rather than once, so a
   * browser that refuses it (private mode, blocked site data) is a refused
   * read or write rather than a failure to make the store. Defaults to
   * `globalThis.localStorage`.
   */
  storage?: () => {
    getItem(key: string): string | null;
    setItem(key: string, value: string): void;
  };
  /**
   * Where another tab's change is announced as a `storage` event. Defaults to
   * `globalThis` — the window — when it has `addEventListener`; `null` listens
   * nowhere, and a write still re-reads what is stored first.
   */
  events?: {
    addEventListener(
      type: 'storage',
      listener: (event: { key: string | null; storageArea?: unknown }) => void,
    ): void;
    removeEventListener(
      type: 'storage',
      listener: (event: { key: string | null; storageArea?: unknown }) => void,
    ): void;
  } | null;
}

type KeptStorage = ReturnType<
  NonNullable<LocalStorageSnapshotOptions['storage']>
>;

/**
 * A `MemoryViewStore` snapshot kept in `localStorage` under `key`: the whole
 * store as one JSON document, `{ instances, preferences }`. For development
 * and single-user hosts until a real backend holds the views (phase 6); it
 * is one browser's, not a second store.
 *
 * Two things a browser does to such a document are the store's to answer,
 * and this is the design (decided 2026-09-28):
 *
 * - **A write storage refuses is a failed write.** `save` throws what
 *   `setItem` threw (a full quota, blocked storage), and the store undoes the
 *   write and rejects it as `UNAVAILABLE` — which the engine reports to the
 *   environment's `onError` as a `store` failure and the UI shows as a save
 *   whose outcome needs a retry, the error marked `storage` so the line
 *   says the browser's storage is full or turned off rather than that the
 *   result never came back. It never pretends a view was kept.
 * - **Tabs do not overwrite each other.** The stamps are the revisions the
 *   stored state already carries, one per instance and one per definition's
 *   preferences. The store re-reads this document before every write and
 *   checks the write's revision against it, so a write merges instance by
 *   instance into what another tab stored, and one based on a revision that
 *   tab has moved past is a `CONFLICT`, the same flow a stale write in one
 *   tab already takes. A `storage` event for `key` reloads the store, so
 *   what another tab saved is listed without waiting for a write.
 *
 * A missing, unreadable or malformed document reads as nothing stored — the
 * store starts empty rather than the page breaking — and the next write
 * replaces it. The format is the one the compensation console has always
 * written under `wow-compensation-dashboard:views`, so what it stored loads
 * as it is.
 */
export function localStorageSnapshot(
  key: string,
  options: LocalStorageSnapshotOptions = {},
): MemorySnapshot {
  const storage = options.storage ?? ambientStorage;
  const events =
    options.events === undefined ? ambientEvents() : options.events;
  return {
    load() {
      try {
        return parseState(storage().getItem(key));
      } catch {
        return undefined;
      }
    },
    save(state) {
      storage().setItem(key, JSON.stringify(state));
    },
    subscribe(listener) {
      if (!events) return () => {};
      const onStorage = (event: {
        key: string | null;
        storageArea?: unknown;
      }) => {
        // `null` is a `clear()`; another area (sessionStorage) is not ours.
        if (event.key !== null && event.key !== key) return;
        if (event.storageArea && event.storageArea !== readable(storage))
          return;
        listener();
      };
      events.addEventListener('storage', onStorage);
      return () => events.removeEventListener('storage', onStorage);
    },
  };
}

function ambientStorage(): KeptStorage {
  const storage = (globalThis as { localStorage?: KeptStorage }).localStorage;
  if (!storage) throw new Error('localStorage is not available');
  return storage;
}

function ambientEvents(): LocalStorageSnapshotOptions['events'] {
  const target = globalThis as Partial<
    NonNullable<LocalStorageSnapshotOptions['events']>
  >;
  return typeof target.addEventListener === 'function'
    ? (target as NonNullable<LocalStorageSnapshotOptions['events']>)
    : null;
}

function readable(storage: () => KeptStorage): KeptStorage | undefined {
  try {
    return storage();
  } catch {
    return undefined;
  }
}

/**
 * The stored document, or `undefined` when it is not one. Only its outline
 * is checked here — an instance needs an id to be held by it; each config is
 * checked, and migrated, where every view out of a store is read.
 */
function parseState(saved: string | null): MemoryState | undefined {
  if (!saved) return undefined;
  const state = JSON.parse(saved) as Partial<MemoryState> | null;
  if (
    !state ||
    typeof state !== 'object' ||
    !Array.isArray(state.instances) ||
    typeof state.preferences !== 'object' ||
    state.preferences === null ||
    Array.isArray(state.preferences)
  )
    return undefined;
  return {
    instances: state.instances.filter(isHeld),
    preferences: state.preferences,
  };
}

function isHeld(instance: unknown): instance is ViewInstance {
  return (
    typeof instance === 'object' &&
    instance !== null &&
    typeof (instance as { id?: unknown }).id === 'string'
  );
}
