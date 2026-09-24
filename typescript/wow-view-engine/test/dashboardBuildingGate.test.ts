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
 * What building a board lets through, and what it keeps (R3): an edit is
 * the building's, so a gesture that outlived it lands nowhere (Q-02); and a
 * plain `edit` of the board cannot reach what the history keeps, so an undo
 * never puts back a list that an edit changed underneath it (A-10).
 */

import { describe, expect, it } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
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

function view(id: string, title: string): ViewInstance {
  return {
    id,
    definitionId: 'orders',
    title,
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  };
}

function panel(id: string, title: string, y = 0): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y, w: 12, h: 4 },
  } as DashboardPanel;
}

/** A store whose `late` view is read only once the test lets it go. */
class SlowStore extends MemoryViewStore {
  release: () => void = () => {};
  private readonly gate = new Promise<void>(resolve => {
    this.release = resolve;
  });

  override async get(id: string): Promise<ViewInstance> {
    if (id === 'late') await this.gate;
    return super.get(id);
  }
}

async function open(config: DashboardViewConfig) {
  const store = new SlowStore({
    instances: [view('pending', 'Pending'), view('late', 'Late')],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => testSource(),
    environment: testEnvironment().environment,
  });
  const instance = await store.create(
    { definitionId: 'overview', title: 'Overview', scope: 'shared', config },
    { requestId: 'r' },
  );
  const runtime = await engine.open(instance.id);
  await nextTask();
  if (!(runtime instanceof DashboardViewRuntime))
    throw new Error('expected a dashboard');
  return { runtime, store };
}

const ids = (runtime: DashboardViewRuntime) =>
  runtime.getSnapshot().draft.panels.map(entry => entry.id);

describe('an edit is the building’s (Q-02)', () => {
  /**
   * 「添加」 of a saved view waits for it to load first, so it is added at
   * the size of what it shows (`preload`). Pressing 「取消」 in that window
   * used to leave the board read — and then the view landed on it anyway,
   * in the draft and on screen, turning a board just reverted dirty again.
   */
  it('adds nothing once 「取消」 ended the building while a view loaded', async () => {
    const { runtime, store } = await open(
      dashboardConfig({ panels: [panel('a', 'A')] }),
    );
    runtime.setBuilding(true);

    const loading = runtime.preload('late');
    // 「取消」: the draft reverted, the building over.
    runtime.revert();
    runtime.setBuilding(false);
    store.release();
    await loading;

    expect(runtime.addPanel({ kind: 'view', instanceId: 'late' })).toBeNull();
    expect(ids(runtime)).toEqual(['a']);
    expect(runtime.getSnapshot().applied.panels.map(entry => entry.id)).toEqual(
      ['a'],
    );
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });
  });

  it('refuses every edit while the board is read, and answers as a refused one does', async () => {
    const { runtime } = await open(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        panels: [panel('a', 'A')],
      }),
    );
    const before = runtime.getSnapshot();

    expect(runtime.addPanel({ kind: 'markdown', content: 'x' })).toBeNull();
    expect(runtime.duplicatePanel('a')).toBeNull();
    expect(runtime.addTab('Two', 'One')).toBeNull();
    expect(runtime.addFilter({ type: 'text', label: 'Status' })).toBeNull();
    expect(runtime.bindPanel('region', 'a', 'warehouse')).toEqual([]);
    runtime.renamePanel('a', 'Renamed');
    runtime.removePanel('a');
    runtime.setFilterDefault('region', ['EU']);
    runtime.removeFilter('region');
    runtime.setTimeGrouping({ units: ['DAY', 'MONTH'], default: 'DAY' });
    expect(runtime.undo()).toBeNull();
    expect(runtime.redo()).toBeNull();

    const after = runtime.getSnapshot();
    expect(after.draft).toBe(before.draft);
    expect(after.applied).toBe(before.applied);
    expect(after.filters).toEqual(before.filters);
    expect(after.history).toBe(before.history);
  });

  it('takes the same edits while it is built, and none once the building ends', async () => {
    const { runtime } = await open(
      dashboardConfig({ panels: [panel('a', 'A')] }),
    );

    runtime.setBuilding(true);
    runtime.renamePanel('a', 'Renamed');
    expect(runtime.getSnapshot().draft.panels[0].title).toBe('Renamed');
    expect(runtime.getSnapshot().history.undo).toEqual({
      command: 'renamePanel',
      subject: 'a',
    });

    // The history is the building's: ending it forgets every step.
    runtime.setBuilding(false);
    expect(runtime.getSnapshot().history).toEqual({ undo: null, redo: null });
    expect(runtime.undo()).toBeNull();
    expect(runtime.getSnapshot().draft.panels[0].title).toBe('Renamed');
  });
});

describe('a plain edit of a board and its history (A-10)', () => {
  /**
   * The history keeps whole members: a step that renamed panel `a` holds
   * the `panels` list before and after it. An `edit({ panels })` that then
   * retitled panel `b` went round the history, and the undo of the rename
   * put back the list as it was before it — `b`'s new title with it.
   */
  it('leaves what building writes to the building commands, so an undo takes back its own step alone', async () => {
    const { runtime } = await open(
      dashboardConfig({ panels: [panel('a', 'A'), panel('b', 'B', 4)] }),
    );
    runtime.setBuilding(true);
    runtime.renamePanel('a', 'A2');

    runtime.edit({
      panels: [panel('a', 'A2'), panel('b', 'B2', 4)],
      tabs: [{ id: 't', title: 'T' }],
      fields: [{ name: 'x', label: 'X', kind: 'string' }],
      timeGrouping: { units: ['DAY', 'MONTH'], default: 'DAY' },
    });
    const draft = runtime.getSnapshot().draft;
    expect(draft.panels.map(entry => entry.title)).toEqual(['A2', 'B']);
    expect(draft.tabs).toEqual([]);
    expect(draft.fields).toEqual([]);
    expect(draft.timeGrouping).toBeUndefined();

    runtime.undo();
    expect(
      runtime.getSnapshot().draft.panels.map(entry => entry.title),
    ).toEqual(['A', 'B']);
  });

  it('takes the rest, a member given as undefined taken out, and an undo leaves it be', async () => {
    const condition: FilterTree = {
      op: 'and',
      children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
    };
    const { runtime } = await open(
      dashboardConfig({
        panels: [panel('a', 'A')],
        timeGrouping: undefined,
      }),
    );
    runtime.setBuilding(true);
    runtime.renamePanel('a', 'A2');

    runtime.edit({ filter: condition, refresh: { interval: 60_000 } });
    runtime.undo();

    const draft = runtime.getSnapshot().draft;
    expect(draft.filter).toEqual(condition);
    expect(draft.refresh).toEqual({ interval: 60_000 });
    expect(draft.panels[0].title).toBe('A');

    runtime.edit({ refresh: undefined });
    expect('refresh' in runtime.getSnapshot().draft).toBe(false);
  });
});
