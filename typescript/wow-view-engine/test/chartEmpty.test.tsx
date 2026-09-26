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

import {
  act,
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DashboardRuntime,
} from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import {
  AnalysisChart,
  DashboardGrid,
  ViewSurface,
  defaultMessages,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

afterEach(cleanup);

const EMPTY = defaultMessages['label.analysis.empty'];

/**
 * A result with no groups is the empty state wherever a chart would stand,
 * never a drawing of nothing. The console's recoverability pie on an empty
 * store drew a grey ring and a legend naming only its measure (W12).
 */
describe('a chart of no groups', () => {
  it('is the empty state for a pie drawn on its own', () => {
    render(
      <ViewSurface>
        <AnalysisChart
          data={{ type: 'pie', slices: [] }}
          spec={{
            type: 'pie',
            pie: { category: 'warehouse', value: 'orders', donut: true },
          }}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(EMPTY)).toBeTruthy();
    expect(document.querySelector('[data-slot="chart"]')).toBeNull();
  });

  async function board(config: AnalysisViewConfig) {
    const source = testSource({ aggregate: vi.fn(() => Promise.resolve([])) });
    const store = new MemoryViewStore({ instances: [{ ...pending, config }] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => source,
      environment: testEnvironment().environment,
    });
    const instance = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({ panels: [panel()] }),
      },
      { requestId: 'r' },
    );
    const runtime = (await engine.open(instance.id)) as DashboardRuntime;
    const view = renderHook(() => useDashboard(runtime));
    await act(async () => {
      await Promise.resolve();
    });
    render(<DashboardGrid dashboard={view.result.current} />);
  }

  it.each<[string, AnalysisViewConfig['chart']]>([
    ['pie', { type: 'pie', pie: { category: 'warehouse', value: 'orders' } }],
    [
      'donut',
      {
        type: 'pie',
        pie: { category: 'warehouse', value: 'orders', donut: true },
      },
    ],
    [
      'bar',
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
    ],
  ])('is the empty state for a %s on a board', async (_name, chart) => {
    await board(analysisConfig({ layout: 'chart', chart }));

    await waitFor(() => expect(screen.getByText(EMPTY)).toBeTruthy());
    expect(document.querySelector('[data-slot="chart"]')).toBeNull();
  });

  it('keeps a metric card a card: no records is an answer', async () => {
    await board(
      analysisConfig({
        layout: 'chart',
        groups: [],
        chart: { type: 'metric', metric: { metric: 'orders' } },
      }),
    );

    await waitFor(() =>
      expect(document.querySelector('[data-slot="metric-card"]')).toBeTruthy(),
    );
    expect(screen.queryByText(EMPTY)).toBeNull();
  });
});
