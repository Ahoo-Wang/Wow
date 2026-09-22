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
 * What the engine knows about instances it is not holding open: the last
 * summary each one was seen as.
 *
 * A command given only an id needs the revision to write against, the
 * definition whose list the view belongs to, and the scope the permission
 * guard judges by. An open runtime carries all three; a view that was only
 * ever listed does not, and re-reading the store for every rename in a list
 * of forty would be forty round trips for state the list just handed over.
 * So a listing is remembered here, a confirmed write updates it, and a
 * delete drops it — the same shape `preferences.ts` has, for the same
 * reason: a cache is one thing and a command surface is another.
 *
 * It is a cache and not a source of truth. `ViewEngine.locate` asks an open
 * runtime first, this second and the store last, and every write it feeds
 * still carries the revision the store will check.
 */

import {
  toSummary,
  type ViewInstance,
  type ViewInstanceSummary,
} from '../model/index.js';

export class SummaryCache {
  private readonly cache = new Map<string, ViewInstanceSummary>();

  /** Remembers a whole listing, declared system views included. */
  noteAll(summaries: readonly ViewInstanceSummary[]): void {
    for (const summary of summaries) this.cache.set(summary.id, summary);
  }

  /** Remembers the instance a write has just confirmed, as its summary. */
  note(instance: ViewInstance): void {
    this.cache.set(instance.id, toSummary(instance));
  }

  /** Forgets a view that is gone; nothing may be written against it again. */
  drop(id: string): void {
    this.cache.delete(id);
  }

  /** The last summary seen for `id`, or nothing when none ever was. */
  get(id: string): ViewInstanceSummary | undefined {
    return this.cache.get(id);
  }
}
