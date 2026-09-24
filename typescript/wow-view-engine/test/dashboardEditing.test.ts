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
 * Building a board, in the runtime (D22 A–E, batch B1): every edit goes
 * into the draft and onto the screen at once — the panels run on it — and
 * nothing is written until the board is saved; a stored 12-column board
 * opens in the 24-column form without turning dirty; a view the board owns
 * runs in a child of its own; a panel's override of how it looks is laid
 * over its view; and a board is saved past its panels' own troubles.
 */

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  isViewCommandError,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type ViewInstance,
  type ViewScope,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { stopsSave } from '../src/runtime/dashboard/panels.js';
import { useSaveCommands } from '../src/react/index.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  preCDashboardConfig,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

const pending: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending orders',
  scope: 'shared',
  revision: 'r1',
  config: recordConfig(),
};

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
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

function owned(id: string, config = analysisConfig()): DashboardPanel {
  return {
    id,
    kind: 'view',
    owned: { definitionId: 'orders', config },
    bindings: [],
    layout: { x: 0, y: 0, w: 12, h: 4 },
  } as DashboardPanel;
}

/** A write elsewhere read back with these panels, and applied. */
function adopt(runtime: DashboardViewRuntime, panels: DashboardPanel[]): void {
  const saved = runtime.getSnapshot().saved;
  if (!saved) throw new Error('expected a saved board');
  runtime.adoptSaved({
    ...saved,
    config: { ...runtime.getSnapshot().draft, panels },
  });
  runtime.apply();
}

function harness(scope: ViewScope = 'personal') {
  const source = testSource();
  const store = new MemoryViewStore({ instances: [pending, byWarehouse] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: testEnvironment().environment,
  });
  return {
    engine,
    store,
    source,
    async open(config: DashboardViewConfig) {
      const instance = await store.create(
        { definitionId: 'overview', title: 'Overview', scope, config },
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

describe('a board stored in the 12-column grid', () => {
  const legacy = {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'dashboard',
    fields: [],
    panels: [
      saved('a', { x: 0, y: 0, w: 7, h: 4 }),
      saved('b', { x: 7, y: 0, w: 5, h: 4 }),
    ],
  } as unknown as DashboardViewConfig;

  it('opens in 24 columns, clean, with nothing moved but the numbers', async () => {
    const board = harness();
    const runtime = await board.open(legacy);
    const state = runtime.getSnapshot();

    expect(state.draft.columns).toBe(24);
    expect(state.draft.panels.map(panel => panel.layout)).toEqual([
      { x: 0, y: 0, w: 14, h: 4 },
      { x: 14, y: 0, w: 10, h: 4 },
    ]);
    expect(state.saved?.config).toEqual(state.draft);
    expect(state.dirty).toBe(false);
    expect(state.issues).toEqual([]);
  });

  it('is written in the new form the first time it is saved', async () => {
    const board = harness();
    const runtime = await board.open(legacy);
    runtime.renamePanel('a', 'Pending');

    const written = await board.engine.save(runtime);

    expect(written.config).toMatchObject({ columns: 24, tabs: [] });
    expect((written.config as DashboardViewConfig).panels[1].layout.x).toBe(14);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });

  it('is read the same way when a write brings it back', async () => {
    // The store's answers pass the engine's read boundary (`readStored`):
    // a save it answers in the old form, and the view a conflict carries,
    // which 「重新加载」 takes as the draft.
    const board = harness();
    const runtime = await board.open(legacy);
    const stored = runtime.getSnapshot().saved!;

    vi.spyOn(board.store, 'save').mockResolvedValueOnce({
      ...stored,
      revision: 'r9',
      config: legacy,
    });
    await board.engine.save(runtime);
    expect(runtime.getSnapshot().saved?.config).toMatchObject({ columns: 24 });
    expect(runtime.getSnapshot().dirty).toBe(false);

    runtime.renamePanel('a', 'Pending');
    vi.spyOn(board.store, 'save').mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved on', {
        instance: { ...stored, revision: 'r10', config: legacy },
      }),
    );
    await board.engine.save(runtime).catch(() => undefined);
    expect(runtime.getSnapshot().write?.kind).toBe('conflict');

    await board.engine.resolveConflict(runtime, 'reload');
    expect(runtime.getSnapshot().draft.panels[1].layout.x).toBe(14);
    expect(runtime.getSnapshot().dirty).toBe(false);
  });
});

/**
 * A-02 end to end (D26 Q31): a board stored before batch C is read once, at
 * the engine's read boundary, and saved with its fixed scope; an author who
 * then changes a filter's settings — here 「可多选」, which would let that
 * filter hold the leaf — does not have it read again on the next opening.
 */
describe('a pre-C board read once, through save and reopen', () => {
  const region = { name: 'region', label: 'Region', kind: 'string' } as const;
  const leaf = { field: 'region', operator: 'IN', value: ['EU', 'CN'] };

  it('keeps its fixed scope fixed after the author changes a filter', async () => {
    const board = harness();
    const runtime = await board.open(
      preCDashboardConfig({ op: 'and', children: [leaf] } as FilterTree, {
        fields: [region],
        panels: [saved('a')],
      }),
    );
    expect(runtime.getSnapshot().draft.fixed.children).toEqual([leaf]);
    expect(runtime.getSnapshot().dirty).toBe(false);

    runtime.setBuilding(true);
    runtime.setFilterMultiple('region', true);
    const written = await board.engine.save(runtime);
    runtime.setBuilding(false);
    expect(written.config).toMatchObject({
      fixed: { op: 'and', children: [leaf] },
    });
    // Written in the form it was read into: no condition of its own (D27).
    expect(written.config).not.toHaveProperty('filter');
    expect(written.config).not.toHaveProperty('filterMode');

    // A new page over the same store: the board is read as it was saved.
    const reopened = await new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: board.store,
      resolveSource: () => board.source,
      environment: testEnvironment().environment,
    }).open(written.id);
    await nextTask();
    const draft = reopened.getSnapshot().draft as DashboardViewConfig;
    expect(draft.fixed.children).toEqual([leaf]);
    expect(draft.fields).toEqual([{ ...region, multiple: true }]);
    expect(reopened.getSnapshot().dirty).toBe(false);
  });
});

describe('editing a board', () => {
  it('puts a new panel into the draft and on screen at once, and runs it', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    const id = runtime.addPanel({ kind: 'view', instanceId: 'by-warehouse' });
    await nextTask();
    const state = runtime.getSnapshot();

    expect(id).toBe('panel-1');
    expect(state.draft.panels).toBe(state.applied.panels);
    expect(state.panels.map(panel => panel.id)).toEqual(['a', 'panel-1']);
    expect(state.panels[1].runtime?.getSnapshot().query.status).toBe('success');
    expect(state.draft.panels[1].layout).toEqual({
      x: 12,
      y: 0,
      w: 24 - 12,
      h: 4,
    });
    expect(state.dirty).toBe(true);
  });

  it('sizes a saved view it already knows by what it shows', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    runtime.addPanel({ kind: 'view', instanceId: 'pending' });

    // A record view is a table, and a table takes the full width.
    expect(runtime.getSnapshot().draft.panels[1].layout).toEqual({
      x: 0,
      y: 4,
      w: 24,
      h: 5,
    });
  });

  it('leaves a global filter being composed pending while it builds', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      }),
    );
    const filter = {
      op: 'and' as const,
      children: [{ field: 'region', operator: 'EQ' as const, value: 'EU' }],
    };

    runtime.edit({ fixed: filter });
    runtime.addPanel({ kind: 'heading', content: 'Stock' });
    const state = runtime.getSnapshot();

    expect(state.applied.panels).toHaveLength(1);
    expect(state.applied.fixed.children).toEqual([]);
    expect(state.draft.fixed).toEqual(filter);
  });

  it('is undone whole by revert, and written whole by save', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    runtime.addPanel({ kind: 'markdown', content: 'Read me' });
    runtime.renamePanel('a', 'Pending');
    runtime.revert();
    await nextTask();
    expect(runtime.getSnapshot().applied.panels).toEqual([saved('a')]);
    expect(runtime.getSnapshot().dirty).toBe(false);

    runtime.addPanel({ kind: 'markdown', content: 'Read me' });
    const written = await board.engine.save(runtime);
    expect((written.config as DashboardViewConfig).panels).toHaveLength(2);
  });

  it('removes, duplicates, renames, replaces and edits panels', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          saved('a'),
          {
            id: 'n',
            kind: 'markdown',
            content: '',
            layout: { x: 0, y: 4, w: 24, h: 2 },
          },
        ],
      }),
    );

    expect(runtime.duplicatePanel('a')).toBe('panel-1');
    runtime.renamePanel('a', 'Pending');
    runtime.replacePanelView('panel-1', 'by-warehouse');
    runtime.editPanelContent('n', { content: 'Hello' });
    await nextTask();
    let state = runtime.getSnapshot();
    expect(state.draft.panels.map(panel => [panel.id, panel.title])).toEqual([
      ['a', 'Pending'],
      ['n', undefined],
      ['panel-1', undefined],
    ]);
    expect(state.panels[2].runtime?.getSnapshot().saved?.id).toBe(
      'by-warehouse',
    );
    expect(state.draft.panels[1]).toMatchObject({ content: 'Hello' });

    runtime.removePanel('a');
    state = runtime.getSnapshot();
    expect(state.panels.map(panel => panel.id)).toEqual(['n', 'panel-1']);
    // The note rose into the room the removed panel left.
    expect(state.draft.panels[0].layout.y).toBe(4);
    expect(runtime.duplicatePanel('ghost')).toBeNull();
  });

  it('builds tabs and moves panels between them', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));

    const second = runtime.addTab('Stock', 'Sales');
    runtime.movePanelToTab('a', second!);
    runtime.renameTab('tab-1', 'Revenue');
    runtime.moveTab(second!, 0);
    expect(runtime.getSnapshot().draft.tabs).toEqual([
      { id: 'tab-2', title: 'Stock' },
      { id: 'tab-1', title: 'Revenue' },
    ]);
    expect(runtime.getSnapshot().draft.panels[0].tab).toBe('tab-2');
    expect(runtime.addTab(' ', 'x')).toBeNull();

    runtime.removeTab('tab-2');
    expect(runtime.getSnapshot().draft.panels).toEqual([]);
    expect(runtime.getSnapshot().panels).toEqual([]);
  });

  it('adds nothing past the most panels a board may hold', async () => {
    const board = harness();
    const full = Array.from(
      { length: board.engine.limits.maxDashboardPanels },
      (_, n) =>
        ({
          id: `n${n}`,
          kind: 'markdown',
          content: '',
          layout: { x: 0, y: n, w: 1, h: 1 },
        }) as DashboardPanel,
    );
    const runtime = await board.open(dashboardConfig({ panels: full }));

    expect(runtime.addPanel({ kind: 'markdown', content: '' })).toBeNull();
    expect(runtime.duplicatePanel('n0')).toBeNull();
  });

  it('does nothing once disposed', async () => {
    const board = harness();
    const runtime = await board.open(dashboardConfig({ panels: [saved('a')] }));
    runtime.dispose();

    expect(runtime.addPanel({ kind: 'markdown', content: '' })).toBeNull();
    expect(runtime.duplicatePanel('a')).toBeNull();
    expect(runtime.addTab('A', 'B')).toBeNull();
    runtime.removePanel('a');
    runtime.referToSaved('a', pending);
    expect(runtime.getSnapshot().draft.panels).toHaveLength(1);
  });
});

describe('a view the board owns', () => {
  it('runs in a child of its own, with no saved view behind it', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [owned('own')] }),
    );
    const child = runtime.getSnapshot().panels[0].runtime;

    expect(runtime.getSnapshot().panels[0].issues).toEqual([]);
    expect(child?.getSnapshot().saved).toBeNull();
    expect(child?.getSnapshot().query.status).toBe('success');
    expect(board.source.aggregate).toHaveBeenCalled();
  });

  it('is judged like a saved analysis: a question its definition refuses does not run', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          owned(
            'own',
            analysisConfig({
              groups: [{ alias: 'g', field: 'nowhere', type: 'TERMS' }],
            }),
          ),
        ],
      }),
    );
    const panel = runtime.getSnapshot().panels[0];

    expect(panel.runtime).toBeNull();
    expect(panel.issues.some(found => found.severity === 'error')).toBe(true);
    expect(board.source.aggregate).not.toHaveBeenCalled();
  });

  it('names a definition this release declares', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          {
            ...owned('own'),
            owned: { definitionId: 'gone', config: analysisConfig() },
          } as DashboardPanel,
        ],
      }),
    );

    expect(codes(runtime.getSnapshot().panels[0].issues)).toEqual([
      'dashboard.panel.definition-unknown',
    ]);
  });

  it('runs again in the same child when its question is edited', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [owned('own')] }),
    );
    const child = runtime.getSnapshot().panels[0].runtime;
    const asked = vi.mocked(board.source.aggregate).mock.calls.length;

    // Another writer's change read back is how a question the board owns
    // changes under it (`adoptSaved`).
    adopt(runtime, [owned('own', analysisConfig({ limit: 5 }))]);
    await nextTask();

    expect(runtime.getSnapshot().panels[0].runtime).toBe(child);
    expect(child?.getSnapshot().applied).toMatchObject({ limit: 5 });
    expect(vi.mocked(board.source.aggregate).mock.calls.length).toBeGreaterThan(
      asked,
    );
  });

  it('stops its child when an edited question is refused', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [owned('own')] }),
    );

    adopt(runtime, [
      owned(
        'own',
        analysisConfig({
          groups: [{ alias: 'g', field: 'nowhere', type: 'TERMS' }],
        }),
      ),
    ]);

    expect(runtime.getSnapshot().panels[0].runtime).toBeNull();
  });

  it('is saved as a view of its own, and the panel points at it (「另存为视图」)', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          {
            ...owned('own'),
            title: 'Mine',
            presentation: { layout: 'chart' },
          } as DashboardPanel,
        ],
      }),
    );

    const instance = await board.engine.saveOwnedView(runtime, 'own', {
      title: 'Orders by warehouse',
      scope: 'personal',
    });
    await nextTask();
    const state = runtime.getSnapshot();

    expect(instance.config).toEqual(analysisConfig());
    expect(
      (await board.engine.list('orders')).items.map(item => item.title),
    ).toContain('Orders by warehouse');
    expect(state.draft.panels[0]).toMatchObject({
      instanceId: instance.id,
      title: 'Mine',
      presentation: { layout: 'chart' },
    });
    expect(state.panels[0].runtime?.getSnapshot().saved?.id).toBe(instance.id);
    // The board still has to be saved for the panel to point there for good.
    expect(state.dirty).toBe(true);
  });

  it('refuses to save a panel that owns no view, or one whose question is refused', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          saved('a'),
          owned(
            'bad',
            analysisConfig({
              groups: [{ alias: 'g', field: 'nowhere', type: 'TERMS' }],
            }),
          ),
        ],
      }),
    );

    for (const panelId of ['a', 'ghost']) {
      const refused = await board.engine
        .saveOwnedView(runtime, panelId, { title: 'X', scope: 'personal' })
        .catch((error: unknown) => error);
      expect(isViewCommandError(refused) && refused.issue.code).toBe(
        'dashboard.panel.not-owned',
      );
    }
    const invalid = await board.engine
      .saveOwnedView(runtime, 'bad', { title: 'X', scope: 'personal' })
      .catch((error: unknown) => error);
    expect(isViewCommandError(invalid) && invalid.issue.code).toBe(
      'view.config.invalid',
    );
  });

  /** 「复制为共享视图并替换」 (D22 B) is a panel showing a saved view, copied. */
  it('refuses to copy a panel that shows no saved view this reader has open', async () => {
    const board = harness('shared');
    const runtime = await board.open(
      dashboardConfig({ panels: [owned('own'), saved('a')] }),
    );
    await nextTask();

    for (const panelId of ['own', 'ghost']) {
      const refused = await board.engine
        .copyPanelView(runtime, panelId, { title: 'X', scope: 'shared' })
        .catch((error: unknown) => error);
      expect(isViewCommandError(refused) && refused.issue.code).toBe(
        'dashboard.panel.not-referenced',
      );
    }
    const blank = await board.engine
      .copyPanelView(runtime, 'a', { title: ' ', scope: 'shared' })
      .catch((error: unknown) => error);
    expect(isViewCommandError(blank)).toBe(true);
    // A copy that goes through leaves the board unwritten, and the panel on it.
    const copy = await board.engine.copyPanelView(runtime, 'a', {
      title: 'Shared copy',
      scope: 'shared',
    });
    expect(copy.scope).toBe('shared');
    expect(runtime.getSnapshot().draft.panels[1]).toMatchObject({
      id: 'a',
      instanceId: copy.id,
    });
    expect(runtime.getSnapshot().dirty).toBe(true);
  });
});

describe("a panel's override of how it looks", () => {
  const chart = {
    layout: 'chart' as const,
    chart: {
      type: 'pie' as const,
      pie: { category: 'warehouse', value: 'orders' },
    },
  };

  function onAnalysis(presentation?: unknown): DashboardPanel {
    return {
      id: 'p',
      kind: 'view',
      instanceId: 'by-warehouse',
      bindings: [],
      layout: { x: 0, y: 0, w: 12, h: 4 },
      ...(presentation ? { presentation } : {}),
    } as DashboardPanel;
  }

  it('is laid over the view it shows, never saved back into it', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [onAnalysis(chart)] }),
    );
    const child = runtime.getSnapshot().panels[0].runtime;

    expect(child?.getSnapshot().applied).toMatchObject(chart);
    expect(child?.getSnapshot().saved?.config).toMatchObject({
      layout: 'table',
    });
    expect(runtime.getSnapshot().panels[0].issues).toEqual([]);
  });

  it('changes in the same child, redrawn and never asked again, and is reset to the view’s own look', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [onAnalysis()] }),
    );
    const child = runtime.getSnapshot().panels[0].runtime;
    const asked = vi.mocked(board.source.aggregate).mock.calls.length;

    runtime.setPresentation('p', chart);
    await nextTask();
    expect(runtime.getSnapshot().panels[0].runtime).toBe(child);
    // Presentation never asks the source (D20): the child's draft carries
    // the new look, the panel draws it over the rows it has.
    expect(child?.getSnapshot().draft).toMatchObject(chart);
    expect(child?.getSnapshot().applied.layout).toBe('table');
    expect(board.source.aggregate).toHaveBeenCalledTimes(asked);

    runtime.setPresentation('p', null);
    await nextTask();
    expect(child?.getSnapshot().draft.layout).toBe('table');
    expect(board.source.aggregate).toHaveBeenCalledTimes(asked);
    expect(runtime.getSnapshot().draft.panels[0]).not.toHaveProperty(
      'presentation',
    );
  });

  it('runs again for a totals row, which is a query of its own', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [onAnalysis()] }),
    );
    const child = runtime.getSnapshot().panels[0].runtime;
    const asked = vi.mocked(board.source.aggregate).mock.calls.length;

    runtime.setPresentation('p', { table: { columns: [], totals: true } });
    await nextTask();
    expect(runtime.getSnapshot().panels[0].runtime).toBe(child);
    expect(child?.getSnapshot().applied).toMatchObject({
      table: { totals: true },
    });
    expect(vi.mocked(board.source.aggregate).mock.calls.length).toBeGreaterThan(
      asked,
    );
  });

  it('is dropped with a note when it no longer fits the view', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          onAnalysis({
            layout: 'chart',
            chart: {
              type: 'bar',
              cartesian: { x: 'gone', series: [{ metric: 'orders' }] },
            },
          }),
          // A chart over a record view: not a member a record view has.
          {
            ...saved('r', { x: 12, y: 0, w: 12, h: 4 }),
            presentation: { chart: chart.chart },
          } as DashboardPanel,
        ],
      }),
    );
    const [analysis, record] = runtime.getSnapshot().panels;

    for (const panel of [analysis, record]) {
      expect(panel.runtime).not.toBeNull();
      expect(panel.issues).toEqual([
        expect.objectContaining({
          code: 'dashboard.panel.presentation-dropped',
          severity: 'warning',
        }),
      ]);
    }
    expect(analysis.runtime?.getSnapshot().applied.layout).toBe('table');
  });

  it('lays a record view out as cards', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          { ...saved('r'), presentation: { layout: 'card' } } as DashboardPanel,
        ],
      }),
    );

    expect(
      runtime.getSnapshot().panels[0].runtime?.getSnapshot().applied.layout,
    ).toBe('card');
  });
});

describe('what stops a save', () => {
  it('is any error for a data view, and only an error of the board for a dashboard', () => {
    const panelError: Issue = {
      code: 'dashboard.binding.global-unknown',
      path: ['panels', 0, 'bindings', 0],
      severity: 'error',
      params: {},
    };
    const boardError: Issue = { ...panelError, path: ['refresh'] };

    expect(stopsSave('record', [panelError])).toBe(true);
    expect(stopsSave('dashboard', [panelError])).toBe(false);
    expect(stopsSave('dashboard', [boardError])).toBe(true);
    expect(
      stopsSave('dashboard', [{ ...boardError, severity: 'warning' }]),
    ).toBe(false);
  });

  it('lets a board with a panel in error be saved from the title bar', async () => {
    const board = harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          saved('a'),
          {
            ...saved('b'),
            bindings: [{ globalField: 'ghost', panelField: 'status' }],
          } as DashboardPanel,
        ],
      }),
    );
    runtime.renamePanel('a', 'Pending');
    const { result } = renderHook(() => useSaveCommands(board.engine, runtime));

    expect(result.current.state.hasErrors).toBe(false);
    expect(result.current.state.blocked).toBe(false);
    await act(async () => {
      await result.current.save();
    });
    expect(runtime.getSnapshot().dirty).toBe(false);

    // An error of the board itself still stops it, here and in the engine.
    act(() => runtime.edit({ refresh: { interval: 0 } }));
    expect(result.current.state.hasErrors).toBe(true);
    const refused = await board.engine
      .save(runtime)
      .catch((error: unknown) => error);
    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.config.invalid',
    );
  });
});
