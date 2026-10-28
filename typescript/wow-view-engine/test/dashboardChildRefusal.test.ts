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
 * A board's children may be narrower than the reference the board judges
 * (they know no `status`): what the board admits, a child may still refuse,
 * and the panel reports it rather than hiding it.
 */

import { FilterOperator } from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterTree,
  type Issue,
  type PanelReference,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { dataViewRuntime } from '../src/runtime/recordRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import { recordSource } from '../src/runtime/source.js';
import {
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  panelReference,
  testEnvironment,
  testLabels,
  testSource,
} from './fixtures.js';

const REGION_FIELD = { name: 'region', label: 'Region', kind: 'string' };

const REGION_FILTER: FilterTree = {
  op: 'and',
  children: [{ field: 'region', operator: FilterOperator.NE, value: 'CN' }],
};

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

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

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
    const reference: PanelReference = panelReference();
    const runtime = new DashboardViewRuntime({
      id: 'dashboard-1',
      definition: overviewDefinition(),
      config,
      title: 'Overview',
      scope: 'personal',
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: clock.environment,
      labels: testLabels(),
      resolve: () => Promise.resolve(reference),
      definitions: () => null,
      createPanelRuntime: (found, scopeFilter) =>
        dataViewRuntime({
          id: 'child',
          definition: ordersDefinition({
            fields: ordersDefinition().fields.filter(
              field => field.name !== 'status',
            ),
          }),
          config: found.config as never,
          title: found.title,
          scope: found.scope,
          saved: found.instance,
          kinds: builtinFieldKinds,
          limits: DEFAULT_RUNTIME_LIMITS,
          environment: clock.environment,
          source: recordSource(source),
          runner: new RequestRunner(),
          labels: testLabels(),
          scopeFilter,
          autoRefresh: false,
        }),
    });
    return { runtime, source };
  }

  const stateFilter: FilterTree = {
    op: 'and',
    children: [{ field: 'state', operator: FilterOperator.NE, value: 'PAID' }],
  };

  it('reports a refused scope on the panel and does not run it', async () => {
    const { runtime, source } = narrow(
      dashboardConfig({
        fields: [REGION_FIELD, STATE_FIELD],
        fixed: stateFilter,
        panels: [bound],
      }),
    );
    await runtime.ready();
    runtime.apply();
    await nextTask();

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
        fixed: REGION_FILTER,
        panels: [bound],
      }),
    );
    await runtime.ready();
    runtime.apply();
    await nextTask();
    const child = runtime.panelRuntime('orders');
    expect(child).not.toBeNull();
    expect(source.paged).toHaveBeenCalledTimes(1);

    runtime.edit({ fixed: stateFilter });
    runtime.apply();
    await nextTask();

    expect(child?.disposed).toBe(true);
    expect(runtime.panelRuntime('orders')).toBeNull();
    expect(codes(runtime.getSnapshot().panels[0].issues)).toContain(
      'filter.field.unknown',
    );
    expect(source.paged).toHaveBeenCalledTimes(1);
  });
});
