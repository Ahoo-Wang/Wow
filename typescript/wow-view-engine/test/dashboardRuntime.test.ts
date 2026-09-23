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
  dataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  isViewCommandError,
  MemoryViewStore,
  RequestRunner,
  ViewEngine,
  emptyDashboardConfig,
  withFieldKinds,
  type DashboardPanel,
  type FieldKind,
  type FieldKindRegistry,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type PanelReference,
  type RuntimeLimits,
  type ViewDefinition,
  type ViewInstance,
  type ViewScope,
  type ViewSource,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  NOW,
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
    definitions?: ViewDefinition[];
    scope?: ViewScope;
    source?: ViewSource;
    resolveSource?: (key: string) => ViewSource;
    limits?: Partial<RuntimeLimits>;
    kinds?: FieldKindRegistry;
  } = {},
): Harness {
  const clock = testEnvironment();
  const source = options.source ?? testSource();
  const store = new MemoryViewStore({
    instances: options.instances ?? [pending()],
  });
  const engine = new ViewEngine({
    definitions: options.definitions ?? [
      ordersDefinition(),
      overviewDefinition(),
    ],
    store,
    resolveSource: options.resolveSource ?? (() => source),
    environment: clock.environment,
    limits: { ...DEFAULT_RUNTIME_LIMITS, ...options.limits },
    kinds: options.kinds,
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

/** The newest paged query the source was asked for. */
function lastQuery(source: ViewSource): FilterPagedQuery {
  const queries = pagedQueries(source);
  return queries[queries.length - 1];
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

  it('gives a panel exactly the tree admission judged, an "any of" root included', async () => {
    const board = await harness();
    const runtime = await board.open(
      boundConfig({
        filter: {
          op: 'or',
          children: [{ field: 'region', operator: 'EQ', value: 'CN' }],
        },
      }),
    );

    // The global filter reaches the panel as one nested group, with no
    // wrapper in between: the same shape `validateDashboard` merged.
    const child = runtime.getSnapshot().panels[0].runtime;
    expect(child?.getSnapshot().result?.config.filter).toEqual({
      op: 'and',
      children: [
        {
          op: 'or',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      ],
    });
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
      children: [
        {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      ],
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

  /**
   * A child admits its own saved config and can warn about it. The panel ran,
   * but the warning went nowhere: `syncPanel` reported only the dashboard's
   * findings for a child it kept. It rides on the panel now, and survives a
   * re-sync that leaves the scope unchanged, where `setScopeFilter` has
   * nothing to report.
   */
  it('carries a running child warning on the panel', async () => {
    const board = await harness({
      instances: [
        pending({
          config: recordConfig({
            filterMode: 'simple',
            filter: {
              op: 'or',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
            },
          }),
        }),
      ],
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const first = () => runtime.getSnapshot().panels[0];

    expect(first().runtime).not.toBeNull();
    expect(first().issues).toEqual([
      {
        code: 'config.filterMode.not-simple',
        severity: 'warning',
        path: ['panels', 0, 'filterMode'],
      },
    ]);

    runtime.edit({ panels: [panel({ layout: { x: 1, y: 0, w: 6, h: 4 } })] });
    runtime.apply();
    await flush();

    expect(first().runtime).not.toBeNull();
    expect(codes(first().issues)).toEqual(['config.filterMode.not-simple']);
  });

  /**
   * The dashboard re-validates the merged filter against the child's fields
   * and the child admits the same merged tree, so a kind that warns about a
   * value was heard twice, and the panel read the same sentence twice.
   */
  it('reports a warning the dashboard and the child both raise once', async () => {
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, path }) =>
        typeof value === 'number' && !Number.isInteger(value)
          ? [{ code: 'filter.value.rounded', severity: 'warning', path }]
          : [],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => ({
        text: `${field.label} = ${String(leaf.value)}`,
        value: { kind: 'text', value: String(leaf.value) },
      }),
    };
    const orders = ordersDefinition();
    const board = await harness({
      definitions: [
        {
          ...orders,
          fields: [
            ...orders.fields,
            { name: 'weight', label: 'Weight', kind: 'rounded' },
          ],
        },
        overviewDefinition(),
      ],
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });
    const runtime = await board.open(
      dashboardConfig({
        fields: [{ name: 'weight', label: 'Weight', kind: 'rounded' }],
        filter: {
          op: 'and',
          children: [{ field: 'weight', operator: 'EQ', value: 2.5 }],
        },
        panels: [
          panel({
            bindings: [{ globalField: 'weight', panelField: 'weight' }],
          }),
        ],
      }),
    );
    const first = runtime.getSnapshot().panels[0];

    expect(first.runtime).not.toBeNull();
    expect(codes(first.issues)).toEqual(['filter.value.rounded']);
  });

  /**
   * `panelRuntime` is the host's handle on one panel, and driving it changes
   * what the child has to say. The panel's issues were copied at sync time
   * only, so a marker over the panel lagged the body under it until some
   * unrelated apply on the dashboard.
   */
  it('follows a child warning the host raises or clears through the panel runtime', async () => {
    const board = await harness();
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const child = runtime.panelRuntime('orders');
    const first = () => runtime.getSnapshot().panels[0];
    expect(child).not.toBeNull();
    expect(first().issues).toEqual([]);

    child?.edit({
      filterMode: 'simple',
      filter: {
        op: 'or',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    });

    expect(first().issues).toEqual([
      {
        code: 'config.filterMode.not-simple',
        severity: 'warning',
        path: ['panels', 0, 'filterMode'],
      },
    ]);

    child?.edit({ filterMode: 'advanced' });

    expect(first().issues).toEqual([]);
  });

  it('clears the issue once the reference has arrived', async () => {
    const board = await harness();
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    const state = runtime.getSnapshot();

    expect(state.issues).toEqual([]);
    expect(state.resolving).toBe(false);
  });

  it('reports a panel added by an edit that cannot be put to work', async () => {
    const source = testSource();
    const board = await harness({
      definitions: [
        ordersDefinition(),
        ordersDefinition({ id: 'broken', source: 'missing' }),
        overviewDefinition(),
      ],
      instances: [
        pending(),
        pending({ id: 'broken-1', definitionId: 'broken' }),
      ],
      // The host resolves the definition's source key, and this one is not a
      // key it knows.
      resolveSource: key => {
        if (key === 'missing') throw new Error(`no source: ${key}`);
        return source;
      },
      source,
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));

    runtime.edit({
      panels: [
        panel(),
        panel({
          id: 'broken',
          instanceId: 'broken-1',
          layout: { x: 6, y: 0, w: 6, h: 4 },
        }),
      ],
    });
    runtime.apply();
    await flush();
    const state = runtime.getSnapshot();

    // Nothing awaits a load an edit starts, so the failure is reported here
    // rather than escaping as an unhandled rejection.
    expect(codes(state.panels[1].issues)).toEqual(['dashboard.panel.failed']);
    expect(state.panels[1].issues[0].params).toMatchObject({
      reason: 'no source: missing',
    });
    expect(state.panels[1].runtime).toBeNull();
    expect(state.panels[0].runtime?.getSnapshot().query.status).toBe('success');
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

describe('DashboardViewRuntime child results', () => {
  /**
   * What a child's last result has to say travels to the panel as its
   * config caveats do: both workbenches and `EmbeddedView` say these, and
   * the same truncated pie must not fall silent because it hangs in a
   * dashboard. The panel's issues are rebuilt on the child's notification,
   * which a result landing is, so nothing waits for a dashboard sync.
   */
  it('carries a child result warning on the panel: summaries fell back to the page', async () => {
    const board = await harness({
      instances: [
        pending({
          config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
        }),
      ],
      source: testSource({
        aggregate: vi.fn(() => Promise.reject(new Error('offline'))),
      }),
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    await flush();

    const first = runtime.getSnapshot().panels[0];
    expect(first.runtime?.getSnapshot().query.status).toBe('success');
    expect(first.issues).toEqual([
      expect.objectContaining({
        code: 'runtime.summary.page-only',
        severity: 'warning',
        path: ['panels', 0, 'summaries'],
      }),
    ]);
  });

  it('carries a child result note on the panel: more groups than shown', async () => {
    const board = await harness({
      instances: [pending({ config: analysisConfig({ limit: 1 }) })],
      // One group asked for, so the query asks for two — and two come back,
      // which is the probe row saying there is a second group.
      source: testSource({
        aggregate: vi.fn(() =>
          Promise.resolve([
            { warehouse: 'CN', orders: 2 },
            { warehouse: 'JP', orders: 1 },
          ]),
        ),
      }),
    });
    const runtime = await board.open(dashboardConfig({ panels: [panel()] }));
    await flush();

    const first = runtime.getSnapshot().panels[0];
    expect(codes(first.issues)).toEqual(['analysis.result.more-groups']);
    expect(first.issues[0].path).toEqual(['panels', 0, 'limit']);
    // A table's rows are whole whatever the limit left out: a note.
    expect(first.issues[0].severity).toBe('note');
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

  it('opens a config whose panels are not a list as a view to be fixed', async () => {
    const board = await harness();
    const runtime = await board.open({
      ...dashboardConfig(),
      panels: 'x' as never,
    });

    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels'] },
    ]);
    expect(runtime.getSnapshot().panels).toEqual([]);
    expect(() => runtime.edit({ refresh: { interval: null } })).not.toThrow();
  });

  it('hands an editor only the fields that are fields', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({ fields: [null as never, REGION_FIELD] }),
    );

    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['fields', 0] },
    ]);
    // The filter editor maps these by name; the entry that is no field is
    // admission's to report, not the editor's to trip over.
    expect(runtime.fields.map(field => field.name)).toEqual(['region']);
  });

  it('notifies subscribers once when disposed', async () => {
    const board = await harness();
    const runtime = await board.open();
    const listener = vi.fn();
    runtime.subscribe(listener);

    runtime.dispose();
    runtime.dispose();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(runtime.disposed).toBe(true);
  });

  it('opens a config with an entry that is no panel, and runs the rest', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [null as never, panel()] }),
    );

    expect(runtime.getSnapshot().issues).toMatchObject([
      { code: 'dashboard.shape.invalid', path: ['panels', 0] },
    ]);
    // The entry has no id to stand under; the panel beside it still runs.
    expect(runtime.getSnapshot().panels).toHaveLength(1);
    expect(runtime.getSnapshot().panels[0].runtime).not.toBeNull();
    expect(board.source.paged).toHaveBeenCalledTimes(1);
  });

  /**
   * A condition this board's own fields cannot take is the host's to fix and
   * not the board's (D17-5): it is left out, said out loud, and the panels
   * run on what their author saved rather than on nothing at all.
   */
  it('leaves out an injected condition it refuses, and runs without it', async () => {
    const board = await harness();
    const runtime = await board.open(boundConfig(), {
      op: 'and',
      children: [{ field: 'unbound', operator: 'EQ', value: 'x' }],
    });

    expect(codes(runtime.getSnapshot().issues)).not.toContain(
      'filter.field.unknown',
    );
    expect(codes(runtime.refusedScope)).toContain('filter.field.unknown');
    expect(runtime.scopeFilter).toBeNull();
    expect(board.source.paged).toHaveBeenCalledTimes(1);
  });

  it('keeps judging the draft with the injected condition after an edit', async () => {
    const board = await harness();
    // The board's own filter is empty, so what reaches the panels is the
    // injected condition alone — and it is admitted, because `region` is
    // bound.
    const runtime = await board.open(
      boundConfig({ filter: { op: 'and', children: [] } }),
      REGION_FILTER,
    );
    expect(runtime.scopeFilter).toEqual(REGION_FILTER);

    // The binding it rides on is taken away. Only a judgement that still
    // holds the scope sees that the panel can no longer carry it.
    runtime.edit({ panels: [panel({ bindings: [] })] });
    runtime.apply();
    await flush();

    expect(codes(runtime.getSnapshot().issues)).toContain(
      'dashboard.binding.missing',
    );
    expect(board.source.paged).toHaveBeenCalledTimes(1);
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
      fields: ordersDefinition().fields,
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
        dataViewRuntime({
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
      {
        code: 'filter.field.unknown',
        path: ['panels', 0, 'children', 0, 'children', 0],
      },
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

  /**
   * The board's one timer publishes its due time as a data view's does, so
   * the countdown in the title bar counts to the timer the panels are
   * holding up rather than to one of its own.
   */
  it('publishes when the whole board is next due', async () => {
    const board = await harness();
    const runtime = await board.open(
      boundConfig({ refresh: { interval: 60 } }),
    );

    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 60_000);

    board.clock.setVisible(false);
    expect(runtime.getSnapshot().nextRefreshAt).toBeNull();
    board.clock.setVisible(true);
    expect(runtime.getSnapshot().nextRefreshAt).toBe(NOW.getTime() + 60_000);

    board.clock.advance(60_000);
    await flush();
    expect(runtime.getSnapshot().nextRefreshAt).toBe(
      NOW.getTime() + 60_000 + 60_000,
    );
  });

  /**
   * A panel's query is not a state change of the dashboard, and the grid is
   * deliberately not re-rendered for one — but the due time moving is the
   * one thing about a panel's query the board above it has to hear, or the
   * countdown goes on counting to a timer that is no longer armed.
   */
  it('notifies when a panel query moves the due time, and no more often', async () => {
    const board = await harness();
    const runtime = await board.open(
      boundConfig({ refresh: { interval: 60 } }),
    );
    const listener = vi.fn();
    runtime.subscribe(listener);

    board.clock.advance(60_000);
    await flush();

    // Out and back: nothing due while the panels query, due again once they
    // have all answered.
    expect(listener).toHaveBeenCalledTimes(2);
    expect(runtime.getSnapshot().nextRefreshAt).toBe(
      NOW.getTime() + 60_000 + 60_000,
    );
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

  it('reverts the draft to the saved config and re-applies it', async () => {
    const board = await harness();
    const runtime = await board.open();

    runtime.edit({ filter: { op: 'and', children: [] } });
    runtime.apply();
    expect(runtime.getSnapshot().dirty).toBe(true);

    runtime.revert();
    await flush();

    const state = runtime.getSnapshot();
    expect(state.dirty).toBe(false);
    expect(state.draft.filter).toEqual(REGION_FILTER);
    expect(state.applied.filter).toEqual(REGION_FILTER);
    // The panels come back with it: the global filter reaches them as a
    // scope, so reverting the dashboard has to re-push it.
    expect(state.panels).toHaveLength(1);
  });

  it('does not revert a dashboard that was never saved', () => {
    const board = harness();
    const runtime = board.engine.create('overview', {
      title: 'Scratch',
      scope: 'personal',
      config: boundConfig(),
    });

    runtime.edit({ filter: { op: 'and', children: [] } });
    runtime.revert();

    expect(runtime.getSnapshot().draft.filter).toEqual({
      op: 'and',
      children: [],
    });
    expect(runtime.getSnapshot().dirty).toBe(true);
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
      payload: {
        action: 'delete',
        id: 'overview-1',
        definitionId: 'overview',
        revision: 'r1',
      },
    });
    expect(runtime.getSnapshot().write?.kind).toBe('unknown');

    runtime.setWrite(null);
    expect(runtime.getSnapshot().write).toBeNull();
  });
});

const EU_FILTER: FilterTree = {
  op: 'and',
  children: [{ field: 'region', operator: 'EQ', value: 'EU' }],
};

/**
 * A board whose second panel binds the global field to a field its view does
 * not have: an error of that panel's own, at `['panels', 1, …]`.
 */
function withMisbound(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return boundConfig({
    panels: [
      panel({ bindings: [{ globalField: 'region', panelField: 'warehouse' }] }),
      panel({
        id: 'misbound',
        bindings: [{ globalField: 'region', panelField: 'nope' }],
        layout: { x: 6, y: 0, w: 6, h: 4 },
      }),
    ],
    ...overrides,
  });
}

/**
 * R1: a panel's own error stops that panel and nothing else. It used to stop
 * the board's apply as a whole — a drag snapped back and the global filter
 * never reached the panels that could carry it — because `apply` refused on
 * any error, where `sync` already told the board's errors from a panel's.
 */
describe('DashboardViewRuntime a panel in error', () => {
  it('applies the global filter to the panels that can carry it', async () => {
    const board = await harness();
    const runtime = await board.open(withMisbound());
    expect(codes(runtime.getSnapshot().panels[1].issues)).toContain(
      'dashboard.binding.panel-unknown',
    );

    runtime.edit({ filter: EU_FILTER });
    runtime.apply();
    await flush();
    const state = runtime.getSnapshot();

    expect(state.applied.filter).toEqual(EU_FILTER);
    expect(lastQuery(board.source).filter).toMatchObject({
      field: 'warehouse',
      value: 'EU',
    });
    // The broken panel stays out, and still says why.
    expect(state.panels[1].runtime).toBeNull();
    expect(codes(state.panels[1].issues)).toContain(
      'dashboard.binding.panel-unknown',
    );
  });

  it('applies a layout edit', async () => {
    const board = await harness();
    const runtime = await board.open(withMisbound());

    runtime.edit({
      panels: runtime
        .getSnapshot()
        .draft.panels.map(entry =>
          entry.id === 'orders'
            ? { ...entry, layout: { x: 0, y: 4, w: 6, h: 4 } }
            : entry,
        ),
    });
    runtime.apply();

    expect(runtime.getSnapshot().applied.panels[0].layout).toEqual({
      x: 0,
      y: 4,
      w: 6,
      h: 4,
    });
  });

  it('places a panel', async () => {
    const board = await harness();
    const runtime = await board.open(withMisbound());

    runtime.place('orders', { x: 0, y: 4, w: 6, h: 4 });

    expect(runtime.getSnapshot().applied.panels[0].layout.y).toBe(4);
  });

  it('keeps the board timer running for the others', async () => {
    const board = await harness();
    await board.open(withMisbound({ refresh: { interval: 60 } }));

    expect(board.clock.timers).toBe(1);
    board.clock.advance(60_000);
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(2);
  });

  it('re-applies what was saved on revert', async () => {
    const board = await harness();
    const runtime = await board.open(withMisbound());
    runtime.edit({ filter: EU_FILTER });
    runtime.apply();

    runtime.revert();
    await flush();

    expect(runtime.getSnapshot().applied.filter).toEqual(REGION_FILTER);
  });
});

/**
 * R2 and R6: a placement lands at once, but it is not an apply of the whole
 * draft, and the panels it lands on move out of its way.
 */
describe('DashboardViewRuntime placing', () => {
  it('applies the placement alone and leaves a pending filter pending', async () => {
    const board = await harness();
    const runtime = await board.open();
    const child = runtime.getSnapshot().panels[0].runtime;

    runtime.edit({ filter: EU_FILTER });
    runtime.place('orders', { x: 6, y: 1, w: 6, h: 4 });
    await flush();
    const state = runtime.getSnapshot();

    expect(state.applied.panels[0].layout).toEqual({ x: 6, y: 1, w: 6, h: 4 });
    expect(state.draft.panels[0].layout).toEqual({ x: 6, y: 1, w: 6, h: 4 });
    // Half a draft used to run here: the filter being composed went out with
    // the nudge.
    expect(state.applied.filter).toEqual(REGION_FILTER);
    expect(state.draft.filter).toEqual(EU_FILTER);
    expect(state.dirty).toBe(true);
    // Geometry asks nothing of the source, and the child keeps its data.
    expect(pagedQueries(board.source)).toHaveLength(1);
    expect(state.panels[0].runtime).toBe(child);

    // The filter still applies when asked, under the new geometry.
    runtime.apply();
    await flush();
    expect(lastQuery(board.source).filter).toMatchObject({
      value: 'EU',
    });
    expect(runtime.getSnapshot().applied.panels[0].layout.x).toBe(6);
  });

  it('pushes the panels it lands on down, and the ones they land on in turn', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          panel({ id: 'a', layout: { x: 0, y: 0, w: 6, h: 4 } }),
          panel({ id: 'b', layout: { x: 0, y: 4, w: 6, h: 4 } }),
          panel({ id: 'c', layout: { x: 0, y: 8, w: 6, h: 2 } }),
          panel({ id: 'aside', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );

    runtime.place('c', { x: 0, y: 2, w: 6, h: 2 });
    const layouts = Object.fromEntries(
      runtime
        .getSnapshot()
        .applied.panels.map(entry => [entry.id, entry.layout]),
    );

    expect(layouts).toEqual({
      c: { x: 0, y: 2, w: 6, h: 2 },
      a: { x: 0, y: 4, w: 6, h: 4 },
      b: { x: 0, y: 8, w: 6, h: 4 },
      // Beside the column, so nothing to move out of the way of.
      aside: { x: 6, y: 0, w: 6, h: 4 },
    });
    expect(runtime.getSnapshot().draft.panels).toEqual(
      runtime.getSnapshot().applied.panels,
    );
  });

  it('ignores a placement the grid does not admit, and a panel there is none of', async () => {
    const board = await harness();
    const runtime = await board.open();
    const listener = vi.fn();
    runtime.subscribe(listener);
    const before = runtime.getSnapshot();

    runtime.place('orders', { x: 10, y: 0, w: 6, h: 4 });
    runtime.place('orders', { x: -1, y: 0, w: 6, h: 4 });
    runtime.place('ghost', { x: 0, y: 0, w: 1, h: 1 });
    runtime.place('orders', { x: 0, y: 0, w: 6, h: 4 });

    expect(runtime.getSnapshot()).toBe(before);
    expect(listener).not.toHaveBeenCalled();
  });

  it('does nothing once disposed', async () => {
    const board = await harness();
    const runtime = await board.open();
    runtime.dispose();

    runtime.place('orders', { x: 6, y: 0, w: 6, h: 4 });
    runtime.refreshPanel('orders');

    expect(runtime.getSnapshot().applied.panels[0].layout.x).toBe(0);
    expect(pagedQueries(board.source)).toHaveLength(1);
  });
});

/** U4: one panel re-run on its own — the retry a failed panel offers. */
describe('DashboardViewRuntime refreshing one panel', () => {
  it('re-runs that panel and leaves the others alone', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({
        panels: [
          panel({ id: 'first' }),
          panel({ id: 'second', layout: { x: 6, y: 0, w: 6, h: 4 } }),
        ],
      }),
    );
    const second = runtime.panelRuntime('second')?.getSnapshot().result;
    expect(pagedQueries(board.source)).toHaveLength(2);

    runtime.refreshPanel('first');
    await flush();

    expect(pagedQueries(board.source)).toHaveLength(3);
    expect(runtime.panelRuntime('second')?.getSnapshot().result).toBe(second);
  });

  it('does nothing for a panel with nothing to run', async () => {
    const board = await harness();
    const runtime = await board.open(
      dashboardConfig({ panels: [panel({ instanceId: 'deleted' })] }),
    );

    runtime.refreshPanel('orders');
    runtime.refreshPanel('ghost');
    await flush();

    expect(board.source.paged).not.toHaveBeenCalled();
  });
});
