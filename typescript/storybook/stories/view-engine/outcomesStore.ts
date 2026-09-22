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
  MemoryViewStore,
  ViewStoreError,
  type MemoryViewStoreOptions,
  type RecordViewConfig,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPreferences,
  type ViewStore,
  type WriteContext,
} from '@ahoo-wang/fetcher-view-engine';
import { recordConfig } from './fixtures.js';

/**
 * The three write outcomes a story can stage, in the words `WriteState` uses.
 *
 * They are the three ends of a write that is not a success, and until now
 * only the unit suites ever walked them: `conflict` is somebody else writing
 * first, `unknown` is a request that left with no answer, and `rejected` is a
 * store that took a look and refused.
 */
export type StagedOutcome = 'conflict' | 'unknown' | 'rejected';

/** What the store says when it refuses, and when it never answers. */
const REASON = {
  unknown: '网关超时，写入结果未知',
  rejected: '这个视图由运维托管，不接受修改',
} as const;

/**
 * The other user's way of looking at the same data.
 *
 * A conflict asks the reader to choose between two configs, and `ConfigSide`
 * summarises each with `describeConfig` — page size, layout, how many columns
 * and how many sorts. A racer that saved the *same* config would make that
 * side-by-side two identical sentences, and the choice would be a coin toss.
 * So this one differs in three of the four: every column of the definition,
 * fifty rows a page, and nothing ordered.
 */
export function competingConfig(): RecordViewConfig {
  return recordConfig({
    // The same condition, so what changed is how it is *looked at* rather
    // than which orders are in it: a conflict over a way of looking is the
    // scene, and a different result would hide it behind different rows.
    filter: {
      op: 'and',
      children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
    },
    pageSize: 50,
    sort: [],
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'warehouse' },
        { field: 'status' },
        { field: 'amount' },
        { field: 'createdAt' },
      ],
    },
  });
}

/**
 * A `ViewStore` that can be told what to make of the next write.
 *
 * Reads go straight through; a staged outcome is spent by the first write
 * that can carry it, and everything after it behaves normally again. That
 * one-shot rule is what makes the recoveries real rather than a loop: the
 * conflict's "Keep mine" actually overwrites, the unknown's "Retry" actually
 * lands, and the story shows the way *out* of the outcome as well as the
 * outcome. Re-mount the story to stage it again.
 *
 * A conflict is not faked. The store races a competing write ahead of the
 * user's — the same thing a colleague with the same view open would do — so
 * the `CONFLICT` comes from `MemoryViewStore`'s own optimistic check, the
 * remote instance is the one really stored, and an overwrite at that
 * revision really lands.
 */
export class OutcomeViewStore implements ViewStore {
  private readonly inner: MemoryViewStore;
  private staged: StagedOutcome | null = null;
  /** Keeps each racing write's idempotency key its own. */
  private races = 0;

  constructor(options: MemoryViewStoreOptions = {}) {
    this.inner = new MemoryViewStore(options);
  }

  /** What the next write that can carry it comes to. */
  stage(outcome: StagedOutcome | null): void {
    this.staged = outcome;
  }

  // Reads go straight through; the in-memory store answers from a map and
  // has nothing to abort, so it takes no signal.
  list(definitionId: string): Promise<ViewInstanceSummary[]> {
    return this.inner.list(definitionId);
  }

  get(id: string): Promise<ViewInstance> {
    return this.inner.get(id);
  }

  getPreferences(definitionId: string): Promise<ViewPreferences> {
    return this.inner.getPreferences(definitionId);
  }

  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteContext,
  ): Promise<ViewInstance> {
    // A create carries no revision, so there is nothing for a racer to get
    // ahead of: a staged conflict waits for a write that has one. That is
    // what lets "Save my copy" land while the conflict it came out of is
    // still on screen.
    return this.through(null, () => this.inner.create(input, context));
  }

  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.through(
      () => this.race(id),
      () => this.inner.save(id, config, revision, context),
    );
  }

  rename(
    id: string,
    title: string,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.through(
      () => this.race(id),
      () => this.inner.rename(id, title, revision, context),
    );
  }

  delete(id: string, revision: string, context: WriteContext): Promise<void> {
    return this.through(
      () => this.race(id),
      () => this.inner.delete(id, revision, context),
    );
  }

  setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteContext,
  ): Promise<ViewPreferences> {
    return this.through(
      () => this.racePreferences(definitionId),
      () => this.inner.setPreferences(definitionId, preferences, context),
    );
  }

  /**
   * One write, through whatever is staged for it.
   *
   * `race` is absent for a write with no revision to lose, and a conflict
   * staged in front of one is left standing rather than spent on a write it
   * could never have happened to.
   */
  private async through<T>(
    race: (() => Promise<void>) | null,
    run: () => Promise<T>,
  ): Promise<T> {
    const staged = this.staged;
    if (staged === null) return run();
    if (staged === 'conflict') {
      if (race === null) return run();
      this.staged = null;
      await race();
      return run();
    }
    this.staged = null;
    if (staged === 'unknown')
      // The one code the ledger reads as "it left, and nobody knows".
      throw new ViewStoreError('UNAVAILABLE', REASON.unknown);
    throw new ViewStoreError('INVALID', REASON.rejected);
  }

  /** Somebody else saving this view a moment before the user's write lands. */
  private async race(id: string): Promise<void> {
    const current = await this.inner.get(id);
    this.races += 1;
    await this.inner.save(id, competingConfig(), current.revision, {
      requestId: `someone-else-${this.races}`,
    });
  }

  /** The same, for the list's own record: order and default view. */
  private async racePreferences(definitionId: string): Promise<void> {
    const current = await this.inner.getPreferences(definitionId);
    this.races += 1;
    await this.inner.setPreferences(
      definitionId,
      { ...current, order: [...current.order].reverse() },
      { requestId: `someone-else-${this.races}` },
    );
  }
}

/**
 * The store the write-outcome stories build on, for a play to read.
 *
 * The screen keeps the draft whether or not anything landed, so what a
 * recovery *wrote* can only be read from the store — and a play cannot
 * otherwise reach the one its story built. Published here rather than from
 * the stories module, where every export is taken for a story.
 */
export const outcomesStore: { current: OutcomeViewStore | null } = {
  current: null,
};
