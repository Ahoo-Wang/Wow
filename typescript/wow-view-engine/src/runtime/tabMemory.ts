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

import type {
  ViewConfig,
  ViewInstance,
  ViewPreferences,
} from '../model/index.js';
import { tabsOf } from '../dashboard/index.js';
import { isViewWriteError, type WriteHandle } from './write.js';

/** What the memory borrows of the engine: the preferences and their writes. */
export interface TabMemoryHost {
  /** The preferences a write starts from (`PreferenceCache.current`). */
  current(definitionId: string): Promise<ViewPreferences>;
  /** One preference write through the ledger. */
  write(definitionId: string, next: ViewPreferences): Promise<ViewPreferences>;
  /** Lets an unsettled write go, so it holds nothing else back. */
  abandon(handle: WriteHandle): void;
}

/**
 * Where each reader last read each dashboard (D22 E): kept in their
 * preferences for the board's definition (`ViewPreferences.lastTabs`), by
 * instance id, and where a board opens when its host names no tab.
 *
 * It is a convenience and nothing more, so it never rejects and never leaves
 * an outcome behind. A burst of tab switches writes the last one, once the
 * write in flight settles — the ledger lets one preference write out at a
 * time. A write that fails is let go: an unsettled preference write would
 * hold back the list's own reorder and default, which matter more than
 * which tab a board opens on.
 */
export class TabMemory {
  /** The tab each board is to be remembered on, by definition and instance. */
  private readonly wanted = new Map<string, string>();
  /** The boards whose write is out. */
  private readonly writing = new Set<string>();

  constructor(private readonly host: TabMemoryHost) {}

  async remember(
    definitionId: string,
    instanceId: string,
    tabId: string,
  ): Promise<void> {
    const key = `${definitionId}\u0000${instanceId}`;
    this.wanted.set(key, tabId);
    if (this.writing.has(key)) return;
    this.writing.add(key);
    try {
      for (;;) {
        const wanted = this.wanted.get(key);
        const current = await this.host.current(definitionId);
        if (wanted === undefined || current.lastTabs?.[instanceId] === wanted)
          return;
        await this.host.write(definitionId, {
          ...current,
          lastTabs: { ...current.lastTabs, [instanceId]: wanted },
        });
      }
    } catch (error) {
      if (isViewWriteError(error)) this.host.abandon(error.handle);
    } finally {
      this.writing.delete(key);
      this.wanted.delete(key);
    }
  }

  /**
   * The tab a board opens on: the one asked for while the board has it,
   * else the one its reader last read it on; `null` for its first, and for
   * any view that is no board.
   */
  async opensOn(
    instance: ViewInstance,
    asked: string | null | undefined,
  ): Promise<string | null> {
    if (instance.config.kind !== 'dashboard') return null;
    if (hasTab(instance.config, asked)) return asked;
    try {
      const preferences = await this.host.current(instance.definitionId);
      const tab: unknown = preferences.lastTabs?.[instance.id];
      return typeof tab === 'string' ? tab : null;
    } catch {
      // The list reports a store that cannot read preferences; a board
      // opens on its first tab meanwhile.
      return null;
    }
  }
}

/** Whether a stored board has the tab named; a config is read as untrusted. */
function hasTab(
  config: ViewConfig,
  tab: string | null | undefined,
): tab is string {
  return (
    typeof tab === 'string' &&
    config.kind === 'dashboard' &&
    tabsOf(config).some(entry => entry.id === tab)
  );
}
