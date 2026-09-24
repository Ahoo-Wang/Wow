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

import { describe, expect, it, vi } from 'vitest';
import type {
  Issue,
  RecordViewConfig,
  ViewInstance,
  ViewRuntimeState,
} from '../src/index.js';
import {
  RuntimeStore,
  hasError,
  type RuntimeStoreOptions,
} from '../src/runtime/runtimeStore.js';
import {
  NOW,
  recordConfig,
  savedInstance,
  testEnvironment,
} from './fixtures.js';

/**
 * The store half of both runtimes, on its own. The two classes' own rules —
 * what a query is, what a panel is — are covered by test/runtime.test.ts and
 * test/dashboardRuntime.test.ts; here it is the rules they share.
 */

const BROKEN: Issue = { code: 'test.broken', severity: 'error', path: [] };
const NOTED: Issue = { code: 'test.noted', severity: 'warning', path: [] };

type State = ViewRuntimeState<RecordViewConfig>;

function initialState(overrides: Partial<State> = {}): State {
  return {
    saved: null,
    title: 'Pending orders',
    scope: 'personal',
    draft: recordConfig(),
    applied: recordConfig(),
    issues: [],
    dirty: true,
    query: { status: 'idle' },
    result: null,
    selection: [],
    write: null,
    editing: false,
    autoApply: false,
    nextRefreshAt: null,
    ...overrides,
  };
}

/**
 * A store with every host hook a spy, plus the snapshots its subscriber read —
 * one per notification, which is how these tests count them.
 */
function harness(
  options: Partial<RuntimeStoreOptions<State>> = {},
  state: Partial<State> = {},
) {
  const clock = testEnvironment();
  const seen: State[] = [];
  const host = {
    admit: vi.fn<(draft: RecordViewConfig) => Issue[]>(() => []),
    apply: vi.fn(),
    refresh: vi.fn(),
    holding: () => false,
    release: vi.fn(),
    restored: vi.fn(),
    ...options,
  };
  const store = new RuntimeStore<State>({
    ...host,
    state: initialState(state),
    environment: clock.environment,
  });
  store.subscribe(() => seen.push(store.getSnapshot()));
  return { ...host, store, clock, seen };
}

describe('RuntimeStore', () => {
  describe('the snapshot and its subscribers', () => {
    it('hands out the state it was opened on', () => {
      const { store } = harness({}, { title: 'Everything' });
      expect(store.getSnapshot().title).toBe('Everything');
      expect(store.state).toBe(store.getSnapshot());
      expect(store.disposed).toBe(false);
    });

    it('commits the patch before it notifies', () => {
      const { store, seen } = harness();
      store.setState({ title: 'Renamed' });
      expect(seen).toHaveLength(1);
      expect(seen[0].title).toBe('Renamed');
      expect(store.getSnapshot().title).toBe('Renamed');
    });

    it('returns a new object per patch and keeps the rest of it', () => {
      const { store } = harness();
      const before = store.getSnapshot();
      store.setState({ editing: true });
      expect(store.getSnapshot()).not.toBe(before);
      expect(store.getSnapshot().draft).toBe(before.draft);
    });

    it('stops telling a listener that unsubscribed', () => {
      const { store } = harness();
      const listener = vi.fn();
      const stop = store.subscribe(listener);
      store.setState({ editing: true });
      stop();
      store.setState({ editing: false });
      expect(listener).toHaveBeenCalledTimes(1);
    });
  });

  describe('dirty against saved', () => {
    it('counts a view that was never saved as dirty', () => {
      const { store } = harness();
      expect(store.isDirty(recordConfig(), null)).toBe(true);
    });

    it('is the equality against the saved config, not against the object', () => {
      const { store } = harness();
      const saved = savedInstance();
      expect(store.isDirty(recordConfig(), saved)).toBe(false);
      expect(store.isDirty(recordConfig({ pageSize: 50 }), saved)).toBe(true);
    });
  });

  describe('a refused scope', () => {
    it('opens on what the caller settled and notifies nobody for it', () => {
      const { store, seen } = harness({ refusedScope: [BROKEN] });
      expect(store.refusedScope).toEqual([BROKEN]);
      expect(seen).toHaveLength(0);
    });

    it('notifies when the answer changed, and hands the new one back', () => {
      const { store, seen } = harness();
      expect(store.refuse([BROKEN])).toEqual([BROKEN]);
      expect(store.refusedScope).toEqual([BROKEN]);
      expect(seen).toHaveLength(1);
    });

    it('keeps the array it has while the answer says the same thing', () => {
      const { store, seen } = harness({ refusedScope: [BROKEN] });
      const held = store.refusedScope;
      expect(store.refuse([{ ...BROKEN }])).toBe(held);
      expect(seen).toHaveLength(0);
    });
  });

  describe('revert', () => {
    it('is a no-op for a view that was never saved', () => {
      const { store, apply, seen } = harness();
      store.revert();
      expect(apply).not.toHaveBeenCalled();
      expect(seen).toHaveLength(0);
    });

    it('restores the draft, re-judges it and puts it in force again', () => {
      const saved: ViewInstance = savedInstance({
        config: recordConfig({ pageSize: 50 }),
      });
      const { store, admit, apply, restored, seen } = harness(
        { admit: vi.fn(() => [NOTED]) },
        { saved, draft: recordConfig({ pageSize: 10 }), dirty: true },
      );
      store.revert();
      expect(store.getSnapshot().draft).toEqual(saved.config);
      expect(store.getSnapshot().issues).toEqual([NOTED]);
      expect(store.getSnapshot().dirty).toBe(false);
      expect(admit).toHaveBeenCalledWith(saved.config);
      // Committed before the host is asked to do anything with it.
      expect(seen[0].draft).toEqual(saved.config);
      expect(restored).toHaveBeenCalledWith(saved.config);
      expect(apply).toHaveBeenCalledTimes(1);
    });

    it('restores without re-applying what is already what ran', () => {
      const saved = savedInstance();
      const { store, apply, restored } = harness(
        {},
        { saved, draft: recordConfig({ pageSize: 10 }) },
      );
      store.revert();
      expect(store.getSnapshot().draft).toEqual(saved.config);
      // The rows on screen already answer this config; `restored` still runs,
      // because a dashboard's panels may not be loaded for it.
      expect(restored).toHaveBeenCalledTimes(1);
      expect(apply).not.toHaveBeenCalled();
    });

    it('restores a config it cannot admit and leaves it to be fixed', () => {
      const saved = savedInstance({ config: recordConfig({ pageSize: 50 }) });
      const { store, apply } = harness(
        { admit: vi.fn(() => [BROKEN]) },
        { saved, draft: recordConfig({ pageSize: 10 }) },
      );
      store.revert();
      expect(store.getSnapshot().draft).toEqual(saved.config);
      expect(hasError(store.getSnapshot().issues)).toBe(true);
      expect(apply).not.toHaveBeenCalled();
    });

    it('is a no-op once disposed', () => {
      const { store, apply } = harness({}, { saved: savedInstance() });
      store.dispose();
      store.revert();
      expect(apply).not.toHaveBeenCalled();
    });
  });

  describe('the refresh timer', () => {
    const every = { refresh: { interval: 30 } };

    it('arms on the applied interval and publishes when it comes due', () => {
      const { store, clock, refresh } = harness(
        {},
        { applied: recordConfig(every) },
      );
      store.retime();
      expect(store.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);
      clock.advance(30_000);
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('keeps the timer and the due time through an unrelated patch', () => {
      const { store, clock } = harness({}, { applied: recordConfig(every) });
      store.retime();
      const due = store.getSnapshot().nextRefreshAt;
      clock.advance(10_000);
      store.setState({ selection: ['o-1'] });
      expect(store.getSnapshot().nextRefreshAt).toBe(due);
      expect(clock.timers).toBe(1);
    });

    const reasons: [string, Partial<State>][] = [
      ['an editor with focus', { editing: true }],
      ['an invalid draft', { issues: [BROKEN] }],
    ];

    it.each(reasons)('holds it for %s, and says so as a null', (_, patch) => {
      const { store, clock } = harness({}, { applied: recordConfig(every) });
      store.retime();
      expect(store.getSnapshot().nextRefreshAt).not.toBeNull();
      store.setState(patch);
      expect(store.getSnapshot().nextRefreshAt).toBeNull();
      expect(clock.timers).toBe(0);
    });

    it("holds it for whatever the runtime's own reason is", () => {
      let running = true;
      const { store } = harness(
        { holding: () => running },
        { applied: recordConfig(every) },
      );
      store.retime();
      expect(store.getSnapshot().nextRefreshAt).toBeNull();
      running = false;
      store.retime();
      expect(store.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);
    });

    it('holds it while the page is hidden, and re-arms when it comes back', () => {
      const { store, clock, seen } = harness(
        {},
        { applied: recordConfig(every) },
      );
      store.retime();
      expect(seen).toHaveLength(1);
      clock.setVisible(false);
      expect(store.getSnapshot().nextRefreshAt).toBeNull();
      // Visibility is no state change of the runtime's, so the only reason to
      // notify is that the due time moved — which it did, twice.
      expect(seen).toHaveLength(2);
      clock.setVisible(true);
      expect(store.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 30_000);
      expect(seen).toHaveLength(3);
    });

    it('says nothing when a re-sync leaves the due time where it was', () => {
      const { store, clock, seen } = harness();
      clock.setVisible(false);
      store.retime();
      expect(store.getSnapshot().nextRefreshAt).toBeNull();
      expect(seen).toHaveLength(0);
    });
  });

  describe('dispose', () => {
    it('stops the timer, lets the host go, and notifies last', () => {
      const { store, clock, release, seen } = harness(
        {},
        { applied: recordConfig({ refresh: { interval: 30 } }) },
      );
      store.retime();
      expect(clock.timers).toBe(1);
      store.dispose();
      expect(store.disposed).toBe(true);
      expect(clock.timers).toBe(0);
      expect(release).toHaveBeenCalledTimes(1);
      // The last snapshot a subscriber reads has the timer stopped, and it is
      // read while `disposed` is already true.
      expect(seen[seen.length - 1].nextRefreshAt).toBeNull();
      expect(seen).toHaveLength(2);
    });

    it('leaves no listener behind, and is idempotent', () => {
      const { store, release, seen } = harness();
      store.dispose();
      expect(seen).toHaveLength(1);
      store.setState({ editing: true });
      store.dispose();
      expect(seen).toHaveLength(1);
      expect(release).toHaveBeenCalledTimes(1);
    });

    it('stops re-timing for a visibility change after it', () => {
      const { store, clock, seen } = harness();
      store.dispose();
      clock.setVisible(false);
      expect(seen).toHaveLength(1);
    });
  });
});
