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
import {
  DEFAULT_RUNTIME_LIMITS,
  isViewWriteError,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import {
  dashboardConfig,
  deferred,
  mine,
  ordersDefinition,
  overviewDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

const board: ViewInstance = {
  id: 'board-1',
  definitionId: 'overview',
  title: 'Daily',
  scope: 'personal',
  revision: 'r1',
  config: dashboardConfig({ panels: [panel()] }),
};

function engineWith(
  maxDashboardPanels = DEFAULT_RUNTIME_LIMITS.maxDashboardPanels,
) {
  const store = new MemoryViewStore({ instances: [mine, pending, board] });
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
      testSource(),
    ),
    store,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxDashboardPanels },
  });
  return { engine, store };
}

const panelsOf = (instance: ViewInstance) =>
  (instance.config as DashboardViewConfig).panels;

describe('放进仪表盘: engine.addToDashboard (R2-98)', () => {
  it('adds the view as a panel at the first free place, as the board’s save', async () => {
    const { engine, store } = engineWith();

    const saved = await engine.addToDashboard('board-1', mine.id);

    expect(saved.revision).not.toBe('r1');
    const added = panelsOf(saved)[panelsOf(saved).length - 1]!;
    expect(added).toMatchObject({ kind: 'view', instanceId: mine.id });
    // Beside the panel already there, at the size a record view starts at.
    expect(added.layout).toEqual({ x: 0, y: 4, w: 24, h: 5 });
    expect(panelsOf(await store.get('board-1'))).toHaveLength(2);
  });

  it('refuses what is no board, and a board already full', async () => {
    await expect(
      engineWith().engine.addToDashboard(mine.id, pending.id),
    ).rejects.toMatchObject({
      issue: { code: 'view.addToDashboard.not-board' },
    });
    await expect(
      engineWith(1).engine.addToDashboard('board-1', mine.id),
    ).rejects.toMatchObject({
      issue: { code: 'view.addToDashboard.full', params: { max: 1 } },
    });
  });

  it('refuses a board open here with edits, and moves one open without', async () => {
    const { engine } = engineWith();
    const open = await engine.open('board-1');

    const saved = await engine.addToDashboard('board-1', mine.id);
    // The open board takes it as it landed: the panel in, nothing unsaved.
    expect(panelsOf({ ...board, config: open.getSnapshot().draft })).toEqual(
      panelsOf(saved),
    );
    expect(open.getSnapshot().dirty).toBe(false);

    open.edit({ refresh: { interval: 300 } });
    expect(open.getSnapshot().dirty).toBe(true);
    await expect(
      engine.addToDashboard('board-1', pending.id),
    ).rejects.toMatchObject({ issue: { code: 'view.addToDashboard.unsaved' } });
  });
});

/**
 * #5447: the board is read and written while it stays open. An edit made in
 * that time is the reader's, and the board's save must not take it away:
 * the open board's baseline moves to the board saved, and the added panel is
 * carried onto the draft it went on editing.
 */
describe('放进仪表盘 while the open board is edited', () => {
  /** An engine whose read of the view added is held until released. */
  function held() {
    const { engine, store } = engineWith();
    const reading = deferred<void>();
    const release = deferred<void>();
    const get = store.get.bind(store);
    vi.spyOn(store, 'get').mockImplementation(async (id: string) => {
      const found = await get(id);
      if (id === mine.id) {
        reading.resolve();
        await release.promise;
      }
      return found;
    });
    return { engine, store, reading, release };
  }

  async function openBoard(engine: ViewEngine) {
    const open = await engine.open('board-1');
    if (!(open instanceof DashboardViewRuntime))
      throw new Error('expected a board');
    open.setBuilding(true);
    return open;
  }

  /** The reader's edits while the add is out: a rename and a move. */
  function edit(open: DashboardViewRuntime) {
    open.renamePanel('orders', 'Renamed');
    open.place('orders', { x: 6, y: 0, w: 6, h: 4 });
    open.edit({ refresh: { interval: 300 } });
  }

  function expectCarried(open: DashboardViewRuntime, saved: ViewInstance) {
    const snapshot = open.getSnapshot();
    expect(snapshot.saved?.revision).toBe(saved.revision);
    expect(snapshot.dirty).toBe(true);
    expect(snapshot.draft.refresh.interval).toBe(300);
    const [own, added] = snapshot.draft.panels;
    expect(own).toMatchObject({
      id: 'orders',
      title: 'Renamed',
      layout: { x: 6, y: 0, w: 6, h: 4 },
    });
    const savedPanel = panelsOf(saved)[panelsOf(saved).length - 1]!;
    expect(added).toMatchObject({
      id: savedPanel.id,
      kind: 'view',
      instanceId: mine.id,
    });
    expect(snapshot.draft.panels).toHaveLength(2);
  }

  /** The add with `open` edited while it was out, and a second holder that is not. */
  async function addedWhileEdited() {
    const { engine, store, reading, release } = held();
    const open = await openBoard(engine);
    const untouched = await engine.open('board-1');
    const adding = engine.addToDashboard('board-1', mine.id);
    await reading.promise;
    edit(open);
    release.resolve();
    const saved = await adding;
    return { engine, store, open, untouched, saved };
  }

  it('carries the panel onto the edits made while it was out, keeping them', async () => {
    const { open, saved } = await addedWhileEdited();

    expectCarried(open, saved);
  });

  it('has a second holder with an untouched draft adopt the saved board while the edited one carries', async () => {
    const { open, untouched, saved } = await addedWhileEdited();

    expectCarried(open, saved);
    expect(untouched.getSnapshot()).toMatchObject({
      dirty: false,
      saved: { revision: saved.revision },
    });
    expect(
      panelsOf({ ...board, config: untouched.getSnapshot().draft }),
    ).toEqual(panelsOf(saved));
  });

  it('takes off only the carried panel with one undo, the reader’s own edits staying', async () => {
    const { open } = await addedWhileEdited();

    expect(open.undo()).toMatchObject({ command: 'addPanel' });

    // The draft differs from the saved board again: a save now would take
    // the panel off there too.
    const snapshot = open.getSnapshot();
    expect(snapshot.draft.panels.map(entry => entry.id)).toEqual(['orders']);
    expect(snapshot.draft.panels[0]).toMatchObject({
      title: 'Renamed',
      layout: { x: 6, y: 0, w: 6, h: 4 },
    });
    expect(snapshot.draft.refresh.interval).toBe(300);
    expect(snapshot.dirty).toBe(true);
  });

  it('sends the carried panel and the edits in one save, against the saved revision', async () => {
    const { engine, store, open, saved } = await addedWhileEdited();
    const save = vi.spyOn(store, 'save');
    await engine.save(open);
    const [, config, revision] = save.mock.calls[0]!;
    expect(revision).toBe(saved.revision);
    expect(panelsOf({ ...board, config })).toHaveLength(2);
    expect(panelsOf({ ...board, config })[0]).toMatchObject({
      title: 'Renamed',
    });
    expect((config as DashboardViewConfig).refresh.interval).toBe(300);
  });

  // The carry goes by the panel's id: one the draft holds already — the
  // effect run again for the same write — is not added a second time.
  // The store is a host's: what it answers for the board saved need not hold
  // the panel the write added. The write is still confirmed: the baseline
  // moves to the revision answered and nothing is carried.
  it('carries nothing, and moves the baseline, when the board answered lacks the panel added', async () => {
    const { engine, store, reading, release } = held();
    const open = await openBoard(engine);
    const save = store.save.bind(store);
    vi.spyOn(store, 'save').mockImplementation(async (...args) => {
      const saved = await save(...args);
      const config = saved.config as DashboardViewConfig;
      return {
        ...saved,
        config: { ...config, panels: config.panels.slice(0, -1) },
      };
    });

    const adding = engine.addToDashboard('board-1', mine.id);
    await reading.promise;
    edit(open);
    const draft = open.getSnapshot().draft;
    release.resolve();
    const saved = await adding;

    const snapshot = open.getSnapshot();
    expect(snapshot.saved?.revision).toBe(saved.revision);
    expect(snapshot.draft).toBe(draft);
    expect(snapshot.dirty).toBe(true);
  });

  it('carries a panel the draft already holds no second time', async () => {
    const { open, saved } = await addedWhileEdited();
    const added = panelsOf(saved)[panelsOf(saved).length - 1]!;
    const before = open.getSnapshot();

    open.carryPanel(added);

    expect(open.getSnapshot().draft).toBe(before.draft);
    expect(open.getSnapshot().history).toBe(before.history);
  });

  it('ends a retry after an answer lost in the same state', async () => {
    const { engine, store } = engineWith();
    const open = await openBoard(engine);
    const save = store.save.bind(store);
    let lose = true;
    vi.spyOn(store, 'save').mockImplementation(async (...args) => {
      const instance = await save(...args);
      if (lose) {
        lose = false;
        throw new ViewStoreError('UNAVAILABLE', 'Answer lost after commit');
      }
      return instance;
    });

    const failure = await engine
      .addToDashboard('board-1', mine.id)
      .catch((error: unknown) => error);
    if (!isViewWriteError(failure))
      throw new Error('expected an unknown write');
    edit(open);
    const saved = (await engine.retryWrite(failure.handle)) as ViewInstance;

    expectCarried(open, saved);
    expect(engine.pendingWrites().size).toBe(0);
    // Retried again by the same id, nothing is carried twice.
    expect(open.getSnapshot().draft.panels).toHaveLength(2);
  });
});
