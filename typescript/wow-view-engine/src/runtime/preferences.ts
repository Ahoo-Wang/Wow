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
import type { ViewStore } from '../store/ViewStore.js';

export class PreferenceCache {
  private readonly store: ViewStore;
  private readonly cache = new Map<string, ViewPreferences>();
  /**
   * How many times each definition's preferences were confirmed by a write
   * (`note`). Revisions are opaque, so which of two is newer cannot be read
   * off them; what can be told is that a write confirmed while a read was
   * on its way, and the read then answers the state before it.
   */
  private readonly notes = new Map<string, number>();

  constructor(store: ViewStore) {
    this.store = store;
  }

  /**
   * Reads through to the store, and remembers what came back — unless a
   * write confirmed newer preferences while the read was on its way: the
   * read started before that write, so it answers the cached ones instead,
   * and the next reorder does not start from a revision already gone.
   */
  async read(definitionId: string): Promise<ViewPreferences> {
    const before = this.notes.get(definitionId) ?? 0;
    const preferences = await this.store.getPreferences(definitionId);
    const newer = this.cache.get(definitionId);
    if (newer && (this.notes.get(definitionId) ?? 0) !== before) return newer;
    this.cache.set(definitionId, preferences);
    return preferences;
  }

  /** Remembers preferences a write has just confirmed. */
  note(definitionId: string, preferences: ViewPreferences): void {
    this.cache.set(definitionId, preferences);
    this.notes.set(definitionId, (this.notes.get(definitionId) ?? 0) + 1);
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
