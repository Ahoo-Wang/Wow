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
 * Whose auto-refresh interval a board keeps, and when its one timer waits
 * (D26 Q35, Q39): a reader's pick is theirs for this opening, never in the
 * draft, so the board does not turn 「已修改」; an author's pick while the
 * board is built is the board's and is saved with it; and while it is built
 * the timer re-runs nothing, resuming once the building ends — saved or
 * cancelled.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending orders',
  scope: 'shared',
  revision: 'r1',
  config: recordConfig(),
};

const onePanel = {
  id: 'orders',
  kind: 'view',
  instanceId: 'pending',
  bindings: [],
  layout: { x: 0, y: 0, w: 12, h: 4 },
} as DashboardPanel;

async function openBoard(interval: number | null = 60) {
  const clock = testEnvironment();
  const source = testSource();
  const store = new MemoryViewStore({ instances: [pending] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  const config: DashboardViewConfig = dashboardConfig({
    panels: [onePanel],
    refresh: { interval },
  });
  const instance = await store.create(
    { definitionId: 'overview', title: 'Overview', scope: 'personal', config },
    { requestId: 'r' },
  );
  const runtime = await engine.open(instance.id);
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  /** How many pages the panel has asked for so far. */
  const asked = () => vi.mocked(source.paged).mock.calls.length;
  return { clock, engine, store, runtime, asked };
}

describe('a reader’s refresh interval (D26 Q35)', () => {
  it('is in force at once, beside the draft, and leaves the board unmodified', async () => {
    const { clock, runtime, asked } = await openBoard(60);
    const before = runtime.getSnapshot();

    runtime.setRefreshInterval(30);

    const state = runtime.getSnapshot();
    expect(state.readerRefresh).toEqual({ interval: 30 });
    expect(state.draft).toBe(before.draft);
    expect(state.draft.refresh).toEqual({ interval: 60 });
    expect(state.dirty).toBe(false);
    // The timer keeps the reader's interval, not the board's.
    const first = asked();
    clock.advance(30_000);
    await nextTask();
    expect(asked()).toBe(first + 1);
  });

  it('turns the timer off for this reader alone, and ignores what the limits refuse', async () => {
    const { clock, runtime } = await openBoard(60);

    runtime.setRefreshInterval(null);
    expect(clock.timers).toBe(0);
    expect(runtime.getSnapshot().dirty).toBe(false);

    runtime.setRefreshInterval(1.5);
    expect(runtime.getSnapshot().readerRefresh).toEqual({ interval: null });
  });

  it('is the board’s own while it is built, and saved with it', async () => {
    const { engine, store, runtime } = await openBoard(60);
    runtime.setRefreshInterval(30);

    runtime.setBuilding(true);
    // Building lets the reader's pick go: the board's own is on screen.
    expect(runtime.getSnapshot().readerRefresh).toBeNull();
    runtime.setRefreshInterval(120);
    expect(runtime.getSnapshot().draft.refresh).toEqual({ interval: 120 });
    expect(runtime.getSnapshot().dirty).toBe(true);

    const written = await engine.save(runtime);
    runtime.setBuilding(false);

    expect(written.config.refresh).toEqual({ interval: 120 });
    const kept = await store.get(written.id);
    expect(kept.config.refresh).toEqual({ interval: 120 });
    expect(runtime.getSnapshot().dirty).toBe(false);
  });
});

describe('building a board holds its auto refresh (D26 Q39)', () => {
  it('re-runs nothing while built, and resumes once saved', async () => {
    const { clock, engine, runtime, asked } = await openBoard(60);
    const first = asked();

    runtime.setBuilding(true);
    expect(runtime.getSnapshot().building).toBe(true);
    expect(clock.timers).toBe(0);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    clock.advance(180_000);
    await nextTask();
    expect(asked()).toBe(first);

    runtime.renamePanel('orders', 'Orders');
    await engine.save(runtime);
    runtime.setBuilding(false);

    expect(clock.timers).toBe(1);
    clock.advance(60_000);
    await nextTask();
    expect(asked()).toBe(first + 1);
  });

  it('resumes once the building is cancelled', async () => {
    const { clock, runtime, asked } = await openBoard(60);

    runtime.setBuilding(true);
    runtime.renamePanel('orders', 'Orders');
    runtime.revert();
    runtime.setBuilding(false);
    await nextTask();
    const first = asked();

    expect(runtime.getSnapshot().building).toBe(false);
    expect(runtime.getSnapshot().dirty).toBe(false);
    clock.advance(60_000);
    await nextTask();
    expect(asked()).toBe(first + 1);
  });

  it('is this opening’s alone, and a second press changes nothing', async () => {
    const { runtime } = await openBoard(60);
    const listener = vi.fn();
    runtime.subscribe(listener);

    runtime.setBuilding(false);
    expect(listener).not.toHaveBeenCalled();
    runtime.setBuilding(true);
    runtime.setBuilding(true);
    expect(listener).toHaveBeenCalledTimes(1);

    runtime.dispose();
    runtime.setBuilding(false);
    runtime.setRefreshInterval(30);
    expect(runtime.getSnapshot().building).toBe(true);
    expect(runtime.getSnapshot().readerRefresh).toBeNull();
  });
});
