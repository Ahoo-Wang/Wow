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
 * The history of building a board (batch B2): every edit command is one
 * step that `undo` takes back — the members of the board it changed, on the
 * draft and on screen alike, and nothing else — and `redo` makes again;
 * a burst of one naming or setting on one thing is one step; the history
 * starts again on a revert, a save and a board read anew. And the
 * one-column reading's reorder, written back onto the grid, as one step.
 */

import { describe, expect, it } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import {
  EDIT_HISTORY_DEPTH,
  EditHistory,
} from '../src/runtime/dashboard/history.js';
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

function saved(id: string, layout = { x: 0, y: 0, w: 12, h: 4 }) {
  return {
    id,
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout,
  } as DashboardPanel;
}

function note(id: string, layout = { x: 0, y: 0, w: 24, h: 2 }) {
  return { id, kind: 'markdown', content: id, layout } as DashboardPanel;
}

function harness(scope: 'personal' | 'shared' = 'personal') {
  const store = new MemoryViewStore({ instances: [pending] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => testSource(),
    environment: testEnvironment().environment,
  });
  return {
    engine,
    store,
    async open(config: DashboardViewConfig) {
      const instance = await store.create(
        {
          definitionId: 'overview',
          title: 'Overview',
          scope,
          config,
        },
        { requestId: 'r' },
      );
      const runtime = await engine.open(instance.id);
      await nextTask();
      if (!(runtime instanceof DashboardViewRuntime))
        throw new Error('expected a dashboard');
      // Every board here is opened to be built: an edit is refused while
      // a board is only read (Q-02, test/dashboardBuildingGate.test.ts).
      runtime.setBuilding(true);
      return runtime;
    },
  };
}

describe('the history of building a board', () => {
  it('takes every edit back one step at a time, and makes each again', async () => {
    const runtime = await harness().open(
      dashboardConfig({ panels: [saved('a')] }),
    );
    const start = runtime.getSnapshot().draft;
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });

    const id = runtime.addPanel({ kind: 'markdown', content: 'Read me' });
    runtime.renamePanel('a', 'Pending');
    runtime.place('a', { x: 0, y: 0, w: 24, h: 4 });
    const built = runtime.getSnapshot().draft;
    expect(runtime.getSnapshot().history.undo).toEqual({
      command: 'place',
      subject: 'a',
    });

    expect(runtime.undo()).toEqual({ command: 'place', subject: 'a' });
    expect(runtime.undo()).toEqual({ command: 'renamePanel', subject: 'a' });
    expect(runtime.getSnapshot().draft.panels.map(p => p.title)).toEqual([
      undefined,
      undefined,
    ]);
    expect(runtime.undo()).toEqual({ command: 'addPanel', subject: id });
    let state = runtime.getSnapshot();
    expect(state.draft).toEqual(start);
    expect(state.applied).toEqual(start);
    // Back where it was saved: nothing to save.
    expect(state.dirty).toBe(false);
    expect(state.panels.map(panel => panel.id)).toEqual(['a']);
    expect(runtime.undo()).toBeNull();
    expect(state.history).toEqual({
      undo: null,
      redo: { command: 'addPanel', subject: id },
    });

    runtime.redo();
    runtime.redo();
    expect(runtime.redo()).toEqual({ command: 'place', subject: 'a' });
    expect(runtime.redo()).toBeNull();
    state = runtime.getSnapshot();
    expect(state.draft).toEqual(built);
    expect(state.panels.map(panel => panel.id)).toEqual(['a', id]);
  });

  it('takes back only what the step changed: a global condition composed since stays', async () => {
    const runtime = await harness().open(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      }),
    );
    const filter = {
      op: 'and' as const,
      children: [{ field: 'region', operator: 'EQ' as const, value: 'EU' }],
    };

    runtime.addPanel({ kind: 'heading', content: 'Stock' });
    runtime.edit({ fixed: filter });
    runtime.undo();
    const state = runtime.getSnapshot();

    expect(state.draft.panels).toEqual([]);
    expect(state.draft.fixed).toEqual(filter);
    // Still pending: the undo is no apply.
    expect(state.applied.fixed.children).toEqual([]);
  });

  it('brings a removed panel back, running again', async () => {
    const runtime = await harness().open(
      dashboardConfig({
        panels: [saved('a'), saved('b', { x: 12, y: 0, w: 12, h: 4 })],
      }),
    );

    runtime.removePanel('b');
    expect(runtime.getSnapshot().panels.map(panel => panel.id)).toEqual(['a']);
    runtime.undo();
    await nextTask();
    const back = runtime.getSnapshot().panels.find(panel => panel.id === 'b');
    expect(back?.runtime?.getSnapshot().query.status).toBe('success');
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('makes one step of a burst of one naming on one thing, and none of one that came back', async () => {
    const runtime = await harness().open(
      dashboardConfig({ panels: [saved('a')] }),
    );
    const name = runtime.addFilter({ type: 'text', label: 'Status' })!;

    // Typed a keystroke at a time.
    for (const typed of ['S', 'St', 'Sta', 'Stage'])
      runtime.renameFilter(name, typed);
    expect(runtime.undo()).toEqual({ command: 'renameFilter', subject: name });
    expect(runtime.getSnapshot().draft.fields[0].label).toBe('Status');
    expect(runtime.undo()).toEqual({ command: 'addFilter', subject: name });

    // A look tried on and put back is no step at all.
    runtime.renamePanel('a', 'One');
    runtime.setPresentation('a', { layout: 'card' });
    runtime.setPresentation('a', null);
    expect(runtime.getSnapshot().history.undo).toEqual({
      command: 'renamePanel',
      subject: 'a',
    });
    // Another command in between starts a step of its own.
    runtime.addTab('Stock', 'Sales');
    runtime.renamePanel('a', 'Two');
    expect(runtime.undo()).toEqual({ command: 'renamePanel', subject: 'a' });
    expect(runtime.getSnapshot().draft.panels[0].title).toBe('One');
  });

  it('notes no step for an edit that changed nothing', async () => {
    const runtime = await harness().open(
      dashboardConfig({ panels: [note('n')] }),
    );

    runtime.renamePanel('n', '');
    runtime.removePanel('ghost');
    runtime.editPanelContent('n', { content: 'n' });
    expect(runtime.getSnapshot().history.undo).toBeNull();
  });

  it('writes a content panel’s form, title and all, as one step', async () => {
    const runtime = await harness().open(
      dashboardConfig({
        panels: [{ ...note('n'), title: 'Old' } as DashboardPanel],
      }),
    );

    runtime.editPanelContent('n', { content: 'Hello', title: '  ' });
    expect(runtime.getSnapshot().draft.panels[0]).toEqual({
      ...note('n'),
      content: 'Hello',
    });
    runtime.editPanelContent('n', { title: 'New', href: undefined } as never);
    expect(runtime.getSnapshot().draft.panels[0].title).toBe('New');
    runtime.undo();
    runtime.undo();
    expect(runtime.getSnapshot().draft.panels[0]).toEqual({
      ...note('n'),
      title: 'Old',
    });
  });

  it('takes a copy-and-replace back to the view the panel showed, and keeps the copy', async () => {
    const board = harness('shared');
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    const copy = await board.engine.copyPanelView(runtime, 'a', {
      title: 'Shared copy',
      scope: 'shared',
    });
    expect(runtime.getSnapshot().history.undo).toEqual({
      command: 'referToSaved',
      subject: 'a',
    });
    expect(runtime.undo()).toEqual({ command: 'referToSaved', subject: 'a' });
    expect(runtime.getSnapshot().draft.panels[0]).toMatchObject({
      id: 'a',
      instanceId: 'pending',
    });
    // The board's draft is what undo takes back; the view written stays.
    expect((await board.store.get(copy.id)).title).toBe('Shared copy');
    runtime.redo();
    expect(runtime.getSnapshot().draft.panels[0]).toMatchObject({
      instanceId: copy.id,
    });
  });

  it('drops what it could redo once another edit is made', async () => {
    const runtime = await harness().open(
      dashboardConfig({ panels: [saved('a')] }),
    );

    runtime.renamePanel('a', 'One');
    runtime.undo();
    runtime.addTab('Stock', 'Sales');
    expect(runtime.getSnapshot().history.redo).toBeNull();
    expect(runtime.redo()).toBeNull();
  });

  it('puts a default taken back into what the filter holds, as setting it did', async () => {
    const runtime = await harness().open(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      }),
    );

    runtime.setFilterDefault('region', ['CN']);
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
    runtime.undo();
    expect(runtime.getSnapshot().draft.fields[0].default).toBeUndefined();
    expect(runtime.getSnapshot().filters.values.region).toBeUndefined();
    runtime.redo();
    expect(runtime.getSnapshot().filters.values.region).toEqual(['CN']);
  });

  it('starts again on a revert, a save and a board read anew', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    runtime.renamePanel('a', 'One');
    runtime.revert();
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });
    expect(runtime.undo()).toBeNull();

    runtime.renamePanel('a', 'Two');
    const written = await board.engine.save(runtime);
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });

    runtime.renamePanel('a', 'Three');
    runtime.adoptSaved(written);
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });
    expect(runtime.getSnapshot().draft.panels[0].title).toBe('Two');
  });

  it('does nothing once disposed', async () => {
    const runtime = await harness().open(
      dashboardConfig({ panels: [saved('a')] }),
    );

    runtime.renamePanel('a', 'One');
    runtime.dispose();
    expect(runtime.undo()).toBeNull();
    expect(runtime.redo()).toBeNull();
    runtime.revert();
  });
});

describe('reordering the one-column reading, in the runtime', () => {
  it('moves a panel one place along its tab, on the draft and on screen, as one step', async () => {
    const runtime = await harness().open(
      dashboardConfig({
        panels: [note('top'), note('bottom', { x: 0, y: 2, w: 24, h: 2 })],
      }),
    );

    runtime.reorderPanel('bottom', 'up');
    let state = runtime.getSnapshot();
    expect(state.draft.panels.map(panel => [panel.id, panel.layout.y])).toEqual(
      [
        ['top', 2],
        ['bottom', 0],
      ],
    );
    expect(state.applied.panels).toEqual(state.draft.panels);
    expect(state.history.undo).toEqual({
      command: 'reorderPanel',
      subject: 'bottom',
    });

    // Nowhere further up: no step.
    runtime.reorderPanel('bottom', 'up');
    runtime.undo();
    state = runtime.getSnapshot();
    expect(state.draft.panels.map(panel => panel.layout.y)).toEqual([0, 2]);
    expect(state.dirty).toBe(false);
  });
});

describe('EditHistory', () => {
  const config = (title: string) =>
    dashboardConfig({
      panels: [{ ...note('n'), title } as DashboardPanel],
    });

  it('reaches back as far as its depth, and no further', () => {
    const history = new EditHistory();
    let at = config('0');
    for (let n = 1; n <= EDIT_HISTORY_DEPTH + 5; n += 1) {
      const next = config(String(n));
      history.record(
        { command: 'place', subject: 'n' },
        [at, next],
        [at, next],
      );
      at = next;
    }
    let steps = 0;
    while (history.undo()) steps += 1;
    expect(steps).toBe(EDIT_HISTORY_DEPTH);
  });

  it('takes a member out that the config it came from did not have', () => {
    const history = new EditHistory();
    const before = dashboardConfig({});
    const after = {
      ...before,
      timeGrouping: { default: 'day', units: ['day'] },
    } as unknown as DashboardViewConfig;
    history.record(
      { command: 'setTimeGrouping', subject: null },
      [before, after],
      [before, after],
    );

    const back = history.undo();
    expect(back?.draft).toEqual({ timeGrouping: undefined });
    expect(history.state).toEqual({
      undo: null,
      redo: { command: 'setTimeGrouping', subject: null },
    });
  });
});
