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

import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { AggregationGroupType, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { AnalysisView } from '../src/analysis/AnalysisView.js';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { createFilterConfiguration } from '../src/filter/filterConfiguration.js';
import type {
  AnalysisViewInstance,
  ViewDefinition,
} from '../src/contracts/viewModel.js';

const chartState = vi.hoisted(() => ({ failed: false }));
vi.mock('../src/analysis/AnalysisChart.js', () => ({
  AnalysisChart: () => {
    if (chartState.failed) throw new Error('Chart rendering failed');
    return null;
  },
}));
afterEach(() => {
  cleanup();
  chartState.failed = false;
  vi.restoreAllMocks();
});

it('keeps one paginated data table when the chart fails after inspecting results', async () => {
  const definition: ViewDefinition = {
    id: 'orders',
    title: '订单分析',
    sourceId: 'orders',
    fields: [{ field: 'region', label: '地区', type: 'string' }],
    analysis: {
      count: true,
      fields: [
        {
          field: 'region',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
      ],
    },
  };
  const instance: AnalysisViewInstance = {
    id: 'regions',
    definitionId: 'orders',
    kind: 'analysis',
    title: '地区订单数',
    scope: { type: 'personal' },
    revision: '1',
    config: {
      filters: createFilterConfiguration({
        id: 'all',
        component: { name: 'builtin' },
        operator: FilterOperator.MATCH_ALL,
        props: {},
      }),
      dimensions: [
        {
          id: 'region',
          component: { name: 'terms' },
          field: 'region',
          alias: 'region',
          title: '地区',
          props: {},
        },
      ],
      metrics: [
        {
          id: 'count',
          component: { name: 'count' },
          alias: 'orders',
          title: '订单数',
          props: {},
        },
      ],
      sort: [],
      limit: 101,
      presentation: { layout: 'bar', columns: [] },
    },
  };
  const aggregate = vi.fn().mockResolvedValue(
    Array.from({ length: 101 }, (_, index) => ({
      region: `地区-${index + 1}`,
      orders: index + 1,
    })),
  );
  const engine = new ViewEngine({
    definitionId: 'orders',
    definition,
    instances: { instances: [instance], defaultInstanceId: 'regions' },
    host: { resolveSource: () => ({ aggregate }) },
  });
  vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    await engine.load();
    render(<AnalysisView engine={engine} />);
    fireEvent.click(screen.getByRole('tab', { name: '数据表', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    expect(screen.getByRole('cell', { name: '地区-101' })).toBeTruthy();

    chartState.failed = true;
    fireEvent.click(screen.getByRole('tab', { name: '分析', exact: true }));
    await screen.findByRole('alert');
    const analysis = screen.getByRole('tabpanel', {
      name: '分析',
      exact: true,
    });
    expect.soft(within(analysis).queryAllByRole('table').length).toBe(0);

    fireEvent.click(screen.getByRole('tab', { name: '数据表', exact: true }));
    expect(screen.getByRole('cell', { name: '地区-101' })).toBeTruthy();
    expect(aggregate).toHaveBeenCalledTimes(1);
  } finally {
    engine.dispose();
  }
});
