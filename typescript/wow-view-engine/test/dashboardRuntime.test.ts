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

import { FilterOperator, type FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  DashboardViewRuntime,
  DataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  isViewCommandError,
  MemoryViewStore,
  RequestRunner,
  ViewEngine,
  emptyDashboardConfig,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type PanelReference,
  type RuntimeLimits,
  type ViewInstance,
  type ViewScope,
  type ViewSource,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testEnvironment,
  testSource,
  type TestEnvironment,
} from './fixtures.js';

function flush(): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, 0));
}

const REGION_FIELD = { name: 'region', label: 'Region', kind: 'string' };

const REGION_FILTER: FilterTree = {
  op: 'and',
  children: [{ field: 'region', operator: 'EQ', value: 'CN' }],
};

function pending(overrides: Partial<ViewInstance> = {}): ViewInstance {
  return {
    id: 'pending',
    definitionId: 'orders',
    title: 'Pending orders',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
    ...overrides,
  };
}

function panel(overrides: Partial<DashboardPanel> = {}): DashboardPanel {
  return {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...overrides,
  } as DashboardPanel;
}

/** A dashboard whose one panel carries the global `region` onto `warehouse`. */
function boundConfig(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return dashboardConfig({
    fields: [REGION_FIELD],
    filter: REGION_FILTER,
    panels: [
      panel({ bindings: [{ globalField: 'region', panelField: 'warehouse' }] }),
    ],
    ...overrides,
  });
}

interface Harness {
  engine: ViewEngine;
  source: ViewSource;
  clock: TestEnvironment;
  open(
    config?: DashboardViewConfig,
    scopeFilter?: FilterTree,
  ): Promise<DashboardViewRuntime>;
}

function harness(
  options: {
    instances?: ViewInstance[];
    scope?: ViewScope;
    source?: ViewSource;
    limits?: Partial<RuntimeLimits>;
  } = {},
): Harness {
  const clock = testEnvironment();
  const source = options.source ?? testSource();
  const store = new MemoryViewStore({
    instances: options.instances ?? [pending()],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
    limits: { ...DEFAULT_RUNTIME_LIMITS, ...options.limits },
  });

  return {
    engine,
    source,
    clock,
    async open(config = boundConfig(), scopeFilter?: FilterTree) {
      const instance = await store.create(
        {
          definitionId: 'overview',
          title: 'Overview',
          scope: options.scope ?? 'personal',
          config,
        },
        { requestId: 'r' },
      );
      const runtime = await engine.open(instance.id, { scopeFilter });
      await flush();
      // `open` narrows to the `DashboardRuntime` contract; the tests below
      // also drive the write commands the engine calls on the class.
      if (!(runtime instanceof DashboardViewRuntime))
        throw new Error(`expected a dashboard, got ${runtime.kind}`);
      return runtime;
    },
  };
}

/** Every paged query the source was asked for, newest last. */
function pagedQueries(source: ViewSource): FilterPagedQuery[] {
  return vi
    .mocked(source.paged)
    .mock.calls.map(call => call[0] as FilterPagedQuery);
}

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

describe('DashboardViewRuntime panels', () => {
  it('gives every data panel a child runtime that has run', async () => {
    const board = await harness();
    const runtime = await board.open();
    const [state] = [runtime.getSnapshot()];

    expect(state.panels).toHaveLength(1);
    expect(state.panels[0].id).toBe('orders');
    expect(state.panels[0].runtime?.getSnapshot().query.status).toBe('success');
    // The dashboard itself queries nothing; each panel reports its own state.
    expect(state.query.status).toBe('idle');
    expect(state.result).toBeNull();
  });

  it('gives a content panel no runtime and no query', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [panel({ id: 'note', kind: 'markdown', content: '# Hi' })],
      }),
    );

    expect(runtime.getSnapshot().panels[0].runtime).toBeNull();
    expect(board.source.paged).not.toHaveBeenCalled();
  });

  it('maps the global filter onto the panel field', async () => {
    const board = await harness();
    await board.open();

    const [query] = pagedQueries(board.source);

    expect(query.filter).toMatchObject({
      field: 'warehouse',
      op: FilterOperator.EQ,
      value: 'CN',
    });
  });

  it('never makes the referenced view dirty or changes its saved config', async () => {
    const board = await harness();
    const runtime = await board.open();
    const child = runtime.getSnapshot().panels[0].runtime;
    const state = child?.getSnapshot();

    expect(state?.dirty).toBe(false);
    expect(state?.draft.filter).toEqual({ op: 'and', children: [] });
    expect(state?.saved?.config).toEqual(recordConfig());
    // The condition that ran is recorded with the result instead.
    expect(state?.result?.config.filter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
  });

  it('works with an analysis panel as well', async () => {
    const board = await harness({
      instances: [pending({ config: analysisConfig() })],
    });
    const runtime = await board.open();

    expect(board.source.aggregate).toHaveBeenCalled();
    expect(runtime.getSnapshot().panels[0].runtime?.kind).toBe('analysis');
  });
});

describe('DashboardViewRuntime unavailable references', () => {
  it('reports the panel and keeps the others running', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          panel({ id: 'gone', instanceId: 'deleted' }),
          panel({ id: 'here', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );
    const state = runtime.getSnapshot();

    expect(codes(state.panels[0].issues)).toEqual([
      'dashboard.panel.unavailable',
    ]);
    expect(state.panels[0].runtime).toBeNull();
    expect(state.panels[1].runtime?.getSnapshot().query.status).toBe('success');
  });

  it('leaves an unavailable panel as a warning, so the dashboard still applies', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [panel({ instanceId: 'deleted' })] }),
    );
    const state = runtime.getSnapshot();

    expect(state.panels[0].issues[0].severity).toBe('warning');
    expect(state.applied.panels).toHaveLength(1);
    // Resolution is over, and what it learned reached the dashboard's issues.
    expect(state.resolving).toBe(false);
    expect(codes(state.issues)).toEqual(['dashboard.panel.unavailable']);
  });

  it('clears the issue once the reference has arrived', async () => {
    const board = await harness();
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const state = runtime.getSnapshot();

    expect(state.issues).toEqual([]);
    expect(state.resolving).toBe(false);
  });

  it('does not run a panel whose own config is in error', async () => {
    const board = await harness({
      instances: [pending({ scope: 'personal' })],
      scope: 'shared',
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const state = runtime.getSnapshot();

    expect(codes(state.panels[0].issues)).toEqual([
      'dashboard.panel.scope-too-narrow',
    ]);
    expect(state.panels[0].runtime).toBeNull();
    expect(board.source.paged).not.toHaveBeenCalled();
  });
});

describe('DashboardViewRuntime editing', () => {
  it('re-runs the panels when the applied global filter changes', async () => {
    const board = await harness();
    const runtime = await board.open();

    const next: FilterTree = {
      op: 'and',
      children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
    };
    runtime.edit({ filter: next });
    expect(runtime.getSnapshot().dirty).toBe(true);
    runtime.apply();
    await flush();

    // The promotion commits with the panels it produced.
    expect(runtime.getSnapshot().applied.filter).toEqual(next);

    const queries = pagedQueries(board.source);
    expect(queries).toHaveLength(2);
    expect(queries[1].filter).toMatchObject({
      field: 'warehouse',
      value: 'EU',
    });
  });

  it('does not re-query when only the layout moved', async () => {
    const board = await harness();
    const runtime = await board.open();
    const before = runtime.getSnapshot().panels[0].runtime;

    runtime.edit({
      panels: [
        panel({
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          layout: { x: 6, y: 0, w: 6, h: 4 },
        }),
      ],
    });
    runtime.apply();
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(1);
    // The same child keeps running, with its data, under the new geometry.
    expect(runtime.getSnapshot().panels[0].runtime).toBe(before);
    expect(runtime.getSnapshot().panels[0].panel.layout.x).toBe(6);
  });

  it('disposes the child of a panel that was removed', async () => {
    const board = await harness();
    const runtime = await board.open();
    const child = runtime.getSnapshot().panels[0].runtime;

    runtime.edit({
      panels: [],
      fields: [],
      filter: { op: 'and', children: [] },
    });
    runtime.apply();

    expect(child?.disposed).toBe(true);
    expect(runtime.getSnapshot().panels).toEqual([]);
  });

  it('replaces the child when the panel points somewhere else', async () => {
    const board = await harness({
      instances: [pending(), pending({ id: 'shipped', title: 'Shipped' })],
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const first = runtime.getSnapshot().panels[0].runtime;

    runtime.edit({ panels: [panel({ instanceId: 'shipped' })] });
    await runtime.ready();
    runtime.apply();
    await flush();

    const second = runtime.getSnapshot().panels[0].runtime;
    expect(first?.disposed).toBe(true);
    expect(second).not.toBe(first);
    expect(second?.getSnapshot().saved?.id).toBe('shipped');
  });

  it('loads a reference added after opening', async () => {
    const board = await harness();
    const runtime = await board.open(emptyDashboardConfig());

    runtime.edit({ panels: [panel()] });
    await runtime.ready();
    runtime.apply();
    await flush();

    expect(runtime.getSnapshot().panels[0].runtime).not.toBeNull();
  });

  it('refuses to apply while the dashboard itself is in error', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.edit({ fields: [{ name: '', label: 'Broken', kind: 'string' }] });
    runtime.apply();

    expect(runtime.getSnapshot().applied.fields).toEqual([REGION_FIELD]);
  });
});

describe('DashboardViewRuntime scope filter', () => {
  it('admits an outer condition and pushes it onto the panels', async () => {
    const board = await harness();
    const runtime = await board.open();

    const issues = runtime.setScopeFilter({
      op: 'and',
      children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
    });
    await flush();

    expect(issues).toEqual([]);
    const [, second] = pagedQueries(board.source);
    // Both the dashboard's own condition and the injected one arrive mapped.
    expect(second.filter).toMatchObject({
      op: FilterOperator.AND,
      operands: [
        { field: 'warehouse', value: 'CN' },
        { field: 'warehouse', value: 'EU' },
      ],
    });
  });

  it('refuses one the panels cannot carry, and changes nothing', async () => {
    const board = await harness();
    const runtime = await board.open();

    const issues = runtime.setScopeFilter({
      op: 'and',
      children: [{ field: 'unbound', operator: 'EQ', value: 'x' }],
    });

    expect(codes(issues)).toContain('filter.field.unknown');
    expect(pagedQueries(board.source)).toHaveLength(1);
  });

  it('ignores a re-injection of the same condition', async () => {
    const board = await harness();
    const runtime = await board.open();
    const tree: FilterTree = {
      op: 'and',
      children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
    };

    runtime.setScopeFilter(tree);
    await flush();
    runtime.setScopeFilter({ ...tree, children: [...tree.children] });
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(2);
  });
});

describe('DashboardViewRuntime admission', () => {
  it('stops every panel when the dashboard holds too many', async () => {
    const board = await harness({ limits: { maxDashboardPanels: 1 } });
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          panel({ id: 'a' }),
          panel({ id: 'b', layout: { x: 0, y: 4, w: 6, h: 4 } }),
        ],
      }),
    );

    // The limit is a rule about the whole, so it holds every panel back, not
    // none of them: it used to sit between "the dashboard" and "a panel".
    expect(codes(runtime.getSnapshot().issues)).toContain(
      'dashboard.panels.too-many',
    );
    expect(runtime.getSnapshot().panels.map(entry => entry.runtime)).toEqual([
      null,
      null,
    ]);
    expect(board.source.paged).not.toHaveBeenCalled();
  });

  it('judges an injected condition with the config from the start', async () => {
    const board = await harness();
    const runtime = await board.open(boundConfig(), {
      op: 'and',
      children: [{ field: 'unbound', operator: 'EQ', value: 'x' }],
    });

    expect(codes(runtime.getSnapshot().issues)).toContain(
      'filter.field.unknown',
    );
    expect(board.source.paged).not.toHaveBeenCalled();
  });

  it('keeps judging the draft with the injected condition after an edit', async () => {
    const board = await harness();
    const runtime = await board.open(boundConfig(), {
      op: 'and',
      children: [{ field: 'unbound', operator: 'EQ', value: 'x' }],
    });

    runtime.edit({ refresh: { interval: null } });
    runtime.apply();
    await flush();

    expect(codes(runtime.getSnapshot().issues)).toContain(
      'filter.field.unknown',
    );
    expect(board.source.paged).not.toHaveBeenCalled();
  });
});

describe('DashboardViewRuntime child refusal', () => {
  const STATE_FIELD = { name: 'state', label: 'State', kind: 'string' };
  const bound = panel({
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'state', panelField: 'status' },
    ],
  });

  /**
   * A dashboard whose children are narrower than the reference the dashboard
   * judges: they know no `status`. What the dashboard admits, they may still
   * refuse, which is the case the panel has to report rather than hide.
   */
  function narrow(config: DashboardViewConfig) {
    const clock = testEnvironment();
    const source = testSource();
    const reference: PanelReference = {
      instance: pending(),
      definition: ordersDefinition(),
    };
    const runtime = new DashboardViewRuntime({
      id: 'dashboard-1',
      definition: overviewDefinition(),
      config,
      title: 'Overview',
      scope: 'personal',
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: clock.environment,
      resolve: () => Promise.resolve(reference),
      createPanelRuntime: (found, scopeFilter) =>
        new DataViewRuntime({
          id: 'child',
          definition: ordersDefinition({
            fields: ordersDefinition().fields.filter(
              field => field.name !== 'status',
            ),
          }),
          config: found.instance.config as never,
          title: found.instance.title,
          scope: found.instance.scope,
          saved: found.instance,
          kinds: builtinFieldKinds,
          limits: DEFAULT_RUNTIME_LIMITS,
          environment: clock.environment,
          source,
          runner: new RequestRunner(),
          scopeFilter,
          autoRefresh: false,
        }),
    });
    return { runtime, source };
  }

  const stateFilter: FilterTree = {
    op: 'and',
    children: [{ field: 'state', operator: 'EQ', value: 'PAID' }],
  };

  it('reports a refused scope on the panel and does not run it', async () => {
    const { runtime, source } = narrow(
      dashboardConfig({
        fields: [REGION_FIELD, STATE_FIELD],
        filter: stateFilter,
        panels: [bound],
      }),
    );
    await runtime.ready();
    runtime.apply();
    await flush();

    const [state] = runtime.getSnapshot().panels;
    expect(state.runtime).toBeNull();
    expect(state.issues).toMatchObject([
      { code: 'filter.field.unknown', path: ['panels', 0, 'children', 0] },
    ]);
    expect(source.paged).not.toHaveBeenCalled();
  });

  it('stops a running child that refuses a new scope, rather than keeping the old one', async () => {
    const { runtime, source } = narrow(
      dashboardConfig({
        fields: [REGION_FIELD, STATE_FIELD],
        filter: REGION_FILTER,
        panels: [bound],
      }),
    );
    await runtime.ready();
    runtime.apply();
    await flush();
    const child = runtime.panelRuntime('orders');
    expect(child).not.toBeNull();
    expect(source.paged).toHaveBeenCalledTimes(1);

    runtime.edit({ filter: stateFilter });
    runtime.apply();
    await flush();

    expect(child?.disposed).toBe(true);
    expect(runtime.panelRuntime('orders')).toBeNull();
    expect(codes(runtime.getSnapshot().panels[0].issues)).toContain(
      'filter.field.unknown',
    );
    expect(source.paged).toHaveBeenCalledTimes(1);
  });
});

describe('DashboardViewRuntime refreshing', () => {
  it('refreshes every panel on one timer of its own', async () => {
    const board = await harness();
    await board.open(boundConfig({ refresh: { interval: 60 } }));

    // One timer for the dashboard, and none in the panel below it.
    expect(board.clock.timers).toBe(1);
    board.clock.advance(60_000);
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(2);
  });

  it('ignores the referenced view own interval', async () => {
    const board = await harness({
      instances: [
        pending({ config: recordConfig({ refresh: { interval: 5 } }) }),
      ],
    });
    await board.open(dashboardConfig({ panels: [panel()] }));

    expect(board.clock.timers).toBe(0);
  });

  it('holds the timer while the page is hidden and while editing', async () => {
    const board = await harness();
    const runtime = await board.open(
      boundConfig({ refresh: { interval: 60 } }),
    );

    board.clock.setVisible(false);
    expect(board.clock.timers).toBe(0);
    board.clock.setVisible(true);
    expect(board.clock.timers).toBe(1);

    runtime.setEditing(true);
    expect(board.clock.timers).toBe(0);
    runtime.setEditing(false);
    expect(board.clock.timers).toBe(1);
  });

  it('refreshes on demand as well', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.refresh();
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(2);
  });
});

describe('DashboardViewRuntime lifecycle', () => {
  it('disposes its panels with itself and then ignores every command', async () => {
    const board = await harness();
    const runtime = await board.open();
    const child = runtime.getSnapshot().panels[0].runtime;
    const before = runtime.getSnapshot();

    runtime.dispose();
    runtime.edit({ fields: [] });
    runtime.apply();
    runtime.refresh();
    runtime.setEditing(true);

    expect(child?.disposed).toBe(true);
    expect(runtime.disposed).toBe(true);
    expect(runtime.getSnapshot()).toBe(before);
    expect(runtime.setScopeFilter(null)).toEqual([]);
  });

  it('notifies subscribers and keeps the panel array stable across a no-op sync', async () => {
    const board = await harness();
    const runtime = await board.open();
    const listener = vi.fn();
    const unsubscribe = runtime.subscribe(listener);
    const panels = runtime.getSnapshot().panels;

    runtime.apply();

    expect(listener).toHaveBeenCalled();
    expect(runtime.getSnapshot().panels).toBe(panels);
    unsubscribe();
    runtime.apply();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('hands out a panel runtime by id', async () => {
    const board = await harness();
    const runtime = await board.open();

    expect(runtime.panelRuntime('orders')).toBe(
      runtime.getSnapshot().panels[0].runtime,
    );
    expect(runtime.panelRuntime('missing')).toBeNull();
  });

  it('edits the dashboard own fields, not a definition', async () => {
    const board = await harness();
    const runtime = await board.open();

    expect(runtime.fields).toEqual([REGION_FIELD]);
    runtime.edit({ fields: [] });
    expect(runtime.fields).toEqual([]);
  });
});

describe('DashboardViewRuntime saving', () => {
  it('refuses to share a dashboard that stands on a personal view', async () => {
    const board = await harness({
      instances: [pending({ scope: 'personal' })],
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));

    // Valid where it is: a personal dashboard may reference anything its
    // owner can read. Sharing it would leave the panel blank for everyone else.
    expect(runtime.getSnapshot().issues).toEqual([]);
    const refused = await board.engine
      .saveAs(runtime, { title: 'Shared', scope: 'shared' })
      .catch((error: unknown) => error);

    expect(isViewCommandError(refused) && refused.issue.code).toBe(
      'view.config.invalid',
    );
  });

  it('advances the baseline and drops the dirty flag', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.edit({ filter: { op: 'and', children: [] } });
    expect(runtime.getSnapshot().dirty).toBe(true);
    await board.engine.save(runtime);

    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(runtime.getSnapshot().saved?.config).toEqual(
      runtime.getSnapshot().draft,
    );
  });

  it('adopts the stored state when a conflict is reloaded', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.adoptSaved({
      id: 'overview-1',
      definitionId: 'overview',
      title: 'Theirs',
      scope: 'personal',
      revision: 'r9',
      config: emptyDashboardConfig(),
    });

    expect(runtime.getSnapshot().title).toBe('Theirs');
    expect(runtime.getSnapshot().dirty).toBe(false);
    expect(runtime.getSnapshot().draft.panels).toEqual([]);
  });

  it('carries a write outcome for the recovery actions', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.setWrite({
      kind: 'unknown',
      requestId: 'req-1',
      payload: { action: 'delete', id: 'overview-1', revision: 'r1' },
    });
    expect(runtime.getSnapshot().write?.kind).toBe('unknown');

    runtime.setWrite(null);
    expect(runtime.getSnapshot().write).toBeNull();
  });
});
