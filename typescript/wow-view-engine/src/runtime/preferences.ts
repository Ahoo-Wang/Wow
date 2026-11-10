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
 * Personal preferences per definition: the order of the list, and which view
 * opens by default.
 *
 * A preference write submits the full state, so it needs the state it is
 * changing. Re-reading the store before every reorder would race with the
 * write that just confirmed, so the last value read or confirmed is kept
 * here and a write starts from it.
 */

import type { ViewInstanceSummary, ViewPreferences } from '../model/index.js';
import type {
  ReadOptions,
  ViewPermissions,
  ViewStore,
} from '../store/ViewStore.js';
import { Generations } from './generations.js';
import type { PermissionGuard } from './permissions.js';
import type { WritePayload } from './write.js';

export class PreferenceCache {
  private readonly store: ViewStore;
  private readonly cache = new Map<string, ViewPreferences>();
  /** Which read of a definition's preferences may still be applied (`Generations`). */
  private readonly generations = new Generations<string>();

  constructor(store: ViewStore) {
    this.store = store;
  }

  /**
   * Reads through to the store, and remembers what came back — unless a
   * write confirmed newer preferences while the read was on its way, or a
   * read issued after it landed first: the read answers an older state, so
   * it answers the cached one instead, and the next reorder does not start
   * from a revision already gone.
   */
  read(
    definitionId: string,
    options: ReadOptions = {},
  ): Promise<ViewPreferences> {
    return this.generations.run(definitionId, async ticket => {
      const preferences = await this.store.getPreferences(
        definitionId,
        options,
      );
      const newer = this.cache.get(definitionId);
      if (!this.generations.admit(ticket) && newer) return newer;
      this.cache.set(definitionId, preferences);
      return preferences;
    });
  }

  /** Remembers preferences a write has just confirmed. */
  note(definitionId: string, preferences: ViewPreferences): void {
    this.cache.set(definitionId, preferences);
    this.generations.confirm(definitionId);
  }

  /** What a write starts from: the last value known, else a read. */
  async current(definitionId: string): Promise<ViewPreferences> {
    return this.cache.get(definitionId) ?? (await this.read(definitionId));
  }
}

/**
 * Preferred order first, then whatever the server returned. An id that no
 * longer exists is ignored rather than removed: the next write cleans it up.
 */
export function orderSummaries(
  summaries: readonly ViewInstanceSummary[],
  preferences: ViewPreferences,
): ViewInstanceSummary[] {
  const byId = new Map(summaries.map(summary => [summary.id, summary]));
  const ordered = preferences.order.flatMap(id => {
    const summary = byId.get(id);
    if (summary) byId.delete(id);
    return summary ? [summary] : [];
  });
  return [...ordered, ...byId.values()];
}

/**
 * The effective default: an explicit id, else the stored one when it still
 * exists, else the first of the ordered list, which is usually the first
 * system view.
 */
export function resolveDefault(
  summaries: readonly ViewInstanceSummary[],
  preferences: ViewPreferences,
  explicit?: string,
): string | null {
  const ids = new Set(summaries.map(summary => summary.id));
  if (explicit && ids.has(explicit)) return explicit;
  if (preferences.defaultInstanceId && ids.has(preferences.defaultInstanceId))
    return preferences.defaultInstanceId;
  return orderSummaries(summaries, preferences)[0]?.id ?? null;
}

/** What `PreferenceCommands` writes with: the engine's own parts. */
export interface PreferenceCommandsParts {
  readonly cache: PreferenceCache;
  readonly guard: PermissionGuard;
  /** The definition's permissions, as `ViewEngine.permissions` answers. */
  permissions(definitionId: string): ViewPermissions;
  /** Hands a write to the engine's ledger, which settles its outcome. */
  dispatch(payload: WritePayload): Promise<unknown>;
}

/**
 * The commands that change a reader's preferences for a definition: the
 * order of the list, the default view, whether analyses run on their own.
 * Each writes the whole state over the last one read or confirmed
 * (`PreferenceCache.current`), through the ledger. `ViewEngine.reorder`,
 * `setDefault` and `setAutoRun` hand on to them.
 */
export class PreferenceCommands {
  constructor(private readonly parts: PreferenceCommandsParts) {}

  /** See `ViewEngine.reorder`. */
  async reorder(
    definitionId: string,
    order: string[],
  ): Promise<ViewPreferences> {
    this.parts.guard.require(
      this.parts.permissions(definitionId).reorder,
      'view.preferences.reorder-forbidden',
    );
    const current = await this.parts.cache.current(definitionId);
    return this.write(definitionId, { ...current, order });
  }

  /** See `ViewEngine.setDefault`. */
  async setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences> {
    this.parts.guard.require(
      this.parts.permissions(definitionId).setDefault,
      'view.preferences.default-forbidden',
    );
    const current = await this.parts.cache.current(definitionId);
    return this.write(definitionId, {
      ...current,
      defaultInstanceId: instanceId,
    });
  }

  /** See `ViewEngine.setAutoRun`. */
  async setAutoRun(
    definitionId: string,
    autoRun: boolean,
  ): Promise<ViewPreferences> {
    const current = await this.parts.cache.current(definitionId);
    return this.write(definitionId, { ...current, autoRun });
  }

  /** One preferences write, dispatched through the ledger. */
  async write(
    definitionId: string,
    next: ViewPreferences,
  ): Promise<ViewPreferences> {
    const payload: WritePayload = { action: 'preferences', definitionId, next };
    return (await this.parts.dispatch(payload)) as ViewPreferences;
  }
}
