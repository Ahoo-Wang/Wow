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

import type { ViewStore } from '../../src/index.js';

/** The methods that change what a store holds. */
const WRITES = [
  'create',
  'save',
  'rename',
  'delete',
  'setPreferences',
] as const;

interface WriteLog {
  /** Writes that fulfilled. A refused or hanging write is not a landing. */
  landed: number;
  /** Told whenever a write settles. */
  listeners: Set<() => void>;
}

const LOGS = new WeakMap<ViewStore, WriteLog>();

/**
 * A store that keeps count of its writes, so a test can wait for the one a
 * gesture caused instead of asking the store again every fifty milliseconds
 * until the answer changes.
 *
 * The store is the real one, patched in place: identity, class and every
 * read are untouched, which is what lets a test keep calling `store.get`
 * and `store.list` on what `setup()` hands back.
 */
export function tracked<T extends ViewStore>(store: T): T {
  const log: WriteLog = { landed: 0, listeners: new Set() };
  LOGS.set(store, log);
  for (const name of WRITES) {
    const original = store[name] as (...args: unknown[]) => Promise<unknown>;
    const wrapped = (...args: unknown[]): Promise<unknown> => {
      return original
        .apply(store, args)
        .then(result => {
          log.landed += 1;
          return result;
        })
        .finally(() => {
          for (const listener of log.listeners) listener();
        });
    };
    (store as Record<string, unknown>)[name] = wrapped;
  }
  return store;
}

/**
 * Resolves once a write that had not landed when this was called has. Call
 * it right after the gesture, before any other `await`, so the write it
 * waits for is that gesture's; then read the store once and assert. A write
 * still hanging — the deferred one a retry test leaves behind — does not
 * hold the door: only a landing counts.
 */
export function landed(store: ViewStore, timeout = 4000): Promise<void> {
  const log = LOGS.get(store);
  if (!log) throw new Error('landed() needs a store wrapped with tracked()');
  const before = log.landed;
  return new Promise((resolve, reject) => {
    const check = (): void => {
      if (log.landed <= before) return;
      log.listeners.delete(check);
      clearTimeout(timer);
      resolve();
    };
    const timer = setTimeout(() => {
      log.listeners.delete(check);
      reject(new Error(`no write landed within ${timeout}ms`));
    }, timeout);
    log.listeners.add(check);
    check();
  });
}
