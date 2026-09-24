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

/**
 * The one read boundary of AGENTS.md's stored-data exception (D26 Q31,
 * A-06): every view the engine reads out of the host's store goes through
 * `readStored` once, on its way in, and everything past it — a runtime and
 * its baseline, a panel's reference, another board a click opens, the
 * write ledger's answers and the instance a conflict carries — holds the
 * form this engine writes and migrates nothing itself.
 *
 * A view declared in code is not stored data: TypeScript holds it to the
 * config's shape like any other code, so it is not read through here.
 */

import {
  isViewStoreError,
  ViewStoreError,
  type DashboardViewConfig,
  type ViewInstance,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import { migrateDashboardConfig } from '../dashboard/index.js';
import type { ViewStore } from '../store/ViewStore.js';

/**
 * A stored view read into the form this engine writes — for a dashboard,
 * `migrateDashboardConfig`; the same instance when it already is, so a view
 * in this form is never a different object for having been read.
 */
export function readStored(instance: ViewInstance): ViewInstance {
  const config: unknown = instance.config;
  if (!isPlainObject(config) || config.kind !== 'dashboard') return instance;
  const read = migrateDashboardConfig(config as unknown as DashboardViewConfig);
  return read === instance.config ? instance : { ...instance, config: read };
}

/**
 * The host's store as the engine reads it: every view it hands back — read,
 * created, saved or renamed, or carried by a write's conflict — through
 * `readStored`, and every other call passed on as it is. A method is looked
 * up on the host's store as it is called rather than once, so a store
 * swapped or spied on after the engine was built is still the one asked.
 */
export function readingStore(store: ViewStore): ViewStore {
  return {
    list: (definitionId, signal) => store.list(definitionId, signal),
    get: async (id, signal) => readStored(await store.get(id, signal)),
    create: (input, context) => written(() => store.create(input, context)),
    save: (id, config, revision, context) =>
      written(() => store.save(id, config, revision, context)),
    rename: (id, title, revision, context) =>
      written(() => store.rename(id, title, revision, context)),
    delete: (id, revision, context) =>
      written(() => store.delete(id, revision, context)),
    getPreferences: (definitionId, signal) =>
      store.getPreferences(definitionId, signal),
    setPreferences: (definitionId, preferences, context) =>
      store.setPreferences(definitionId, preferences, context),
    ...(store.permissions
      ? { permissions: definitionId => store.permissions!(definitionId) }
      : {}),
  };
}

/**
 * A write's answer read in: the view it returns, or the view the store
 * holds when it refuses the write as a conflict — which the ledger takes
 * as the draft on 「重新加载」, so it is stored data like any other read.
 */
async function written<T extends ViewInstance | void>(
  write: () => Promise<T>,
): Promise<T> {
  try {
    const answer = await write();
    return (answer ? readStored(answer) : answer) as T;
  } catch (error) {
    throw conflictRead(error);
  }
}

/** A conflict with the view it carries read in; anything else as it is. */
function conflictRead(error: unknown): unknown {
  if (!isViewStoreError(error) || !error.instance) return error;
  const instance = readStored(error.instance);
  if (instance === error.instance) return error;
  return new ViewStoreError(error.code, error.message, {
    instance,
    preferences: error.preferences,
  });
}
