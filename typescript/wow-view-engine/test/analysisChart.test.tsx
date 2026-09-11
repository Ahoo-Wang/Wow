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

import { useState, type ComponentProps } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ChartContainer } from '../src/components/ui/chart.js';
import { afterEach, expect, it, vi } from 'vitest';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { AnalysisChart } from '../src/analysis/AnalysisChart.js';
import { AnalysisResultTabs as ControlledResultTabs } from '../src/analysis/AnalysisResultTabs.js';
import { AnalysisTable } from '../src/analysis/AnalysisTable.js';
import type { AnalysisPlan } from '../src/analysis/analysisModel.js';
function AnalysisResultTabs(
  props: Omit<
    ComponentProps<typeof ControlledResultTabs>,
    'value' | 'onValueChange'
  >,
) {
  const [mode, setMode] = useState<'analysis' | 'table'>('analysis');
  return (
    <ControlledResultTabs {...props} value={mode} onValueChange={setMode} />
  );
}
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const plan: AnalysisPlan = {
  query: { metrics: [] },
  schema: [
    {
      id: 'm',
      alias: 'm',
      title: '金额',
      role: 'metric',
      valueType: 'number',
      nullable: true,
      unit: 'CNY',
      aggregation: 'SUM',
    },
  ],
};
it('renders metric zero and null distinctly without claiming grouped totals', () => {
  const view = render(
    <AnalysisChart
      plan={plan}
      rows={[{ m: 0 }]}
      presentation={{ layout: 'metric', columns: [] }}
    />,
  );
  expect(screen.getByText('0')).toBeTruthy();
  view.rerender(
    <AnalysisChart
      plan={plan}
      rows={[{ m: null }]}
      presentation={{ layout: 'metric', columns: [] }}
    />,
  );
  expect(screen.getByText('无值')).toBeTruthy();
});
it('shows actionable fallback and full table for incompatible charts', () => {
  render(
    <AnalysisChart
      plan={plan}
      rows={[{ m: 3 }]}
      presentation={{ layout: 'line', columns: [] }}
    />,
  );
  expect(screen.getByText(/图表需要一个横轴维度/).getAttribute('role')).toBe(
    'status',
  );
  expect(screen.getByRole('table')).toBeTruthy();
});

it.each(['bar', 'line', 'area', 'pie'] as const)(
  'renders %s using an accessible chart and a keyboard reachable table',
  async layout => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 640,
      height: 320,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 320,
      right: 640,
      toJSON() {},
    });
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    const grouped: AnalysisPlan = {
      ...plan,
      schema: [
        {
          id: 'x',
          alias: 'x',
          title: '分类',
          role: 'dimension',
          valueType: 'number',
          nullable: false,
        },
        ...plan.schema,
      ],
    };
    const rows = [
      { x: 1, m: 3 },
      { x: 2, m: 5 },
    ];
    render(
      <AnalysisResultTabs
        table={<AnalysisTable plan={grouped} rows={rows} sort={[]} />}
      >
        <AnalysisChart
          plan={grouped}
          rows={rows}
          presentation={{ layout, columns: [] }}
        />
      </AnalysisResultTabs>,
    );
    expect(screen.getByRole('application')).toBeTruthy();
    expect(document.querySelector('[data-slot="chart"]')).toBeTruthy();
    fireEvent.focus(screen.getByRole('application'));
    fireEvent.keyDown(screen.getByRole('application'), { key: 'ArrowRight' });
    await waitFor(() =>
      expect(
        document.querySelector('.recharts-tooltip-wrapper span[title="5"]'),
      ).not.toBeNull(),
    );
    fireEvent.click(screen.getByRole('tab', { name: '数据表', exact: true }));
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.queryByRole('application')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: '分析', exact: true }));
    await waitFor(() => expect(screen.getByRole('application')).toBeTruthy());
    expect(screen.queryByRole('table')).toBeNull();
  },
);

it('retains the local data page when switching away from the table and back', () => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
    new DOMRect(0, 0, 640, 320),
  );
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  const grouped: AnalysisPlan = {
    ...plan,
    schema: [
      {
        id: 'x',
        alias: 'x',
        title: '分类',
        role: 'dimension',
        valueType: 'string',
        nullable: false,
      },
      ...plan.schema,
    ],
  };
  const rows = Array.from({ length: 201 }, (_, index) => ({
    x: `分组-${index + 1}`,
    m: index + 1,
  }));
  render(
    <AnalysisResultTabs
      table={<AnalysisTable plan={grouped} rows={rows} sort={[]} />}
    >
      <AnalysisChart
        plan={grouped}
        rows={rows}
        presentation={{ layout: 'bar', columns: [] }}
      />
    </AnalysisResultTabs>,
  );
  fireEvent.click(screen.getByRole('tab', { name: '数据表', exact: true }));
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(screen.getByRole('cell', { name: '分组-101' })).toBeTruthy();
  fireEvent.click(screen.getByRole('tab', { name: '分析', exact: true }));
  fireEvent.click(screen.getByRole('tab', { name: '数据表', exact: true }));
  expect(screen.getByRole('cell', { name: '分组-101' })).toBeTruthy();
});
it('formats metric fractions while retaining the admitted raw value as a title', () => {
  render(
    <AnalysisChart
      plan={{
        ...plan,
        schema: [
          { ...plan.schema[0], numberFormat: { maximumFractionDigits: 2 } },
        ],
      }}
      rows={[{ m: 1.234567890123456 }]}
      presentation={{ layout: 'metric', columns: [] }}
    />,
  );
  expect(screen.getByText('1.23').getAttribute('title')).toBe(
    '1.234567890123456',
  );
});

it('exposes pie values and returned-group shares without overflowing their total', () => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  render(
    <AnalysisChart
      plan={{
        ...plan,
        schema: [
          {
            id: 'x',
            alias: 'x',
            title: '地区',
            role: 'dimension',
            valueType: 'string',
            nullable: false,
          },
          ...plan.schema,
        ],
      }}
      rows={[
        { x: '华东', m: 1e308 },
        { x: '华南', m: 1e308 },
        { x: '华北', m: 0 },
      ]}
      presentation={{ layout: 'pie', columns: [], donut: true }}
    />,
  );
  const legend = screen.getByRole('list', { name: '分组数值与占比' });
  expect(legend.textContent).toContain('华东');
  expect(legend.textContent).toContain('华南');
  expect(legend.textContent?.match(/50%/g)).toHaveLength(2);
  expect(legend.textContent).toContain('0%');
  expect(screen.getByText(/占比仅基于已返回分组/)).toBeTruthy();
  expect(legend.querySelector('[title="1e+308"]')).toBeTruthy();
});

it('keeps chart colors in escaped style attributes instead of injected style HTML', () => {
  const html = renderToStaticMarkup(
    <ChartContainer
      config={{ s0: { color: 'red</style><script>alert(1)</script>' } }}
    >
      <div />
    </ChartContainer>,
  );
  expect(html).not.toContain('<script>');
  expect(html).not.toContain('<style');
  expect(html).toContain('--color-s0:');
});
