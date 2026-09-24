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
 * A board and the views that leave it (D26 Q30, Q32, Q33): it takes no
 * outer condition — a page narrows it filter by filter — and what a panel's
 * view takes off it comes in two parts, what the page holds and what the
 * reader set, with the way back beside them.
 */

import { type FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it, vi } from 'vitest';
import {
  DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type ViewSource,
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

const REGION_FIELD = { name: 'region', label: 'Region', kind: 'string' };

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

/** One panel carrying the global `region` onto `warehouse`. */
function boundConfig(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return dashboardConfig({
    fields: [REGION_FIELD],
    panels: [
      panel({ bindings: [{ globalField: 'region', panelField: 'warehouse' }] }),
    ],
    ...overrides,
  });
}

function harness() {
  const clock = testEnvironment();
  const source = testSource();
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'pending',
        definitionId: 'orders',
        title: 'Pending orders',
        scope: 'shared',
        revision: 'r1',
        config: recordConfig(),
      },
    ],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  return {
    engine,
    source,
    async open(config = boundConfig(), scopeFilter?: FilterTree) {
      const instance = await store.create(
        {
          definitionId: 'overview',
          title: 'Overview',
          scope: 'personal',
          config,
        },
        { requestId: 'r' },
      );
      const runtime = await engine.open(instance.id, { scopeFilter });
      await nextTask();
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

describe('DashboardViewRuntime takes no outer condition (D26 Q32)', () => {
  const OUTER: FilterTree = {
    op: 'and',
    children: [{ field: 'region', operator: 'NE', value: 'EU' }],
  };

  it('refuses one and runs as it was: a board is narrowed filter by filter', async () => {
    const board = await harness();
    const runtime = await board.open();

    expect(codes(runtime.setScopeFilter(OUTER))).toEqual([
      'dashboard.scope.unsupported',
    ]);
    expect(codes(runtime.refusedScope)).toEqual([
      'dashboard.scope.unsupported',
    ]);
    expect(runtime.scopeFilter).toBeNull();
    await nextTask();
    expect(pagedQueries(board.source)).toHaveLength(1);
    // What it has is taken, and the refusal goes with it.
    expect(runtime.setScopeFilter(null)).toEqual([]);
    expect(runtime.refusedScope).toEqual([]);
  });

  it('refuses one asked for as it opens, and runs without it', async () => {
    const board = await harness();
    const runtime = await board.open(boundConfig(), OUTER);

    expect(codes(runtime.refusedScope)).toEqual([
      'dashboard.scope.unsupported',
    ]);
    expect(codes(runtime.getSnapshot().issues)).not.toContain(
      'dashboard.scope.unsupported',
    );
    expect(runtime.scopeFilter).toBeNull();
    expect(board.source.paged).toHaveBeenCalledTimes(1);
  });
});

describe('DashboardViewRuntime hands a panel’s view over (D26 Q30, Q33)', () => {
  const PHASE_FIELD = { name: 'phase', label: 'Phase', kind: 'string' };
  const handing = (overrides: Partial<DashboardViewConfig> = {}) =>
    boundConfig({
      ...overrides,
      fields: [REGION_FIELD, PHASE_FIELD],
      panels: [
        panel({
          bindings: [
            { globalField: 'region', panelField: 'warehouse' },
            { globalField: 'phase', panelField: 'status' },
          ],
        }),
        {
          id: 'note',
          kind: 'markdown',
          content: 'hi',
          layout: { x: 6, y: 0, w: 6, h: 4 },
        },
      ],
    });

  it('parts what the page holds — the scope — from what the reader set', async () => {
    const board = await harness();
    const runtime = await board.open(handing());
    runtime.holdFilters({ values: { region: ['CN'] } });
    runtime.setFilterValue('phase', ['SHIPPED']);

    const handed = runtime.handOver('orders');
    expect(handed?.scopeFilter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'IN', value: ['CN'] }],
    });
    expect(handed?.filter).toEqual({
      op: 'and',
      children: [{ field: 'status', operator: 'IN', value: ['SHIPPED'] }],
    });
    // The way back: this board, on its tab, under what its filters hold.
    expect(handed?.from).toEqual({
      title: 'Overview',
      back: {
        kind: 'dashboard',
        definitionId: 'overview',
        instanceId: runtime.getSnapshot().saved?.id,
        filters: runtime.getSnapshot().filters,
        tab: null,
      },
    });
  });

  it('hands the board’s fixed scope with what the page holds: neither is the reader’s (D26 Q31)', async () => {
    const board = await harness();
    const runtime = await board.open(
      handing({
        fixed: {
          op: 'and',
          children: [{ field: 'region', operator: 'NE', value: 'EU' }],
        },
      }),
    );
    runtime.holdFilters({ values: { region: ['CN'] } });
    runtime.setFilterValue('phase', ['SHIPPED']);

    const handed = runtime.handOver('orders');
    expect(handed?.scopeFilter).toEqual({
      op: 'and',
      children: [
        { field: 'warehouse', operator: 'NE', value: 'EU' },
        { field: 'warehouse', operator: 'IN', value: ['CN'] },
      ],
    });
    expect(JSON.stringify(handed?.filter)).not.toContain('"EU"');
    expect(JSON.stringify(handed?.filter)).toContain('SHIPPED');
  });

  it('hands nothing held when the page holds nothing, and nothing for a panel that is no data panel', async () => {
    const board = await harness();
    const runtime = await board.open(handing());
    runtime.setFilterValue('region', ['EU']);

    const handed = runtime.handOver('orders');
    expect(handed?.scopeFilter).toBeNull();
    expect(JSON.stringify(handed?.filter)).toContain('"EU"');
    expect(runtime.handOver('note')).toBeNull();
    expect(runtime.handOver('gone')).toBeNull();
    runtime.dispose();
    expect(runtime.handOver('orders')).toBeNull();
  });

  it('has no way back for a board never saved', () => {
    const board = harness();
    const runtime = board.engine.create('overview', {
      title: 'New',
      scope: 'personal',
      config: handing(),
    }) as unknown as DashboardViewRuntime;
    expect(runtime.handOver('orders')?.from).toBeUndefined();
  });
});
