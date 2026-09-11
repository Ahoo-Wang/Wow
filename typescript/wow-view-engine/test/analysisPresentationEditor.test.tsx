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

import { useState } from 'react';
import type { AnalysisPresentation } from '../src/analysis/analysisPresentation.js';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { AnalysisPlan } from '../src/analysis/analysisModel.js';
import { AnalysisPresentationEditor } from '../src/analysis/AnalysisPresentationEditor.js';
afterEach(cleanup);
const plan: AnalysisPlan = {
  query: { metrics: [] },
  schema: [
    {
      id: 'x',
      alias: 'x',
      title: '时间',
      role: 'dimension',
      valueType: 'datetime',
      nullable: false,
    },
    {
      id: 'region',
      alias: 'region',
      title: '地区',
      role: 'dimension',
      valueType: 'string',
      nullable: false,
    },
    {
      id: 'm',
      alias: 'm',
      title: '数量',
      role: 'metric',
      valueType: 'number',
      nullable: false,
      aggregation: 'COUNT',
    },
    {
      id: 'any',
      alias: 'any',
      title: '样本',
      role: 'metric',
      valueType: 'number',
      nullable: false,
      aggregation: 'ANY',
    },
  ],
};
it('changes layout through the sole onChange output and preserves valid aliases and column widths', async () => {
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      value={{
        layout: 'bar',
        columns: [{ alias: 'm', width: 220 }],
        x: 'x',
        series: 'region',
        metrics: ['m', 'gone'],
        orientation: 'horizontal',
        stacked: true,
      }}
      plan={plan}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
  const option = await screen.findByRole('option', { name: '折线图' });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  expect(onChange).toHaveBeenCalledExactlyOnceWith({
    layout: 'line',
    orientation: 'horizontal',
    stacked: true,
    columns: [{ alias: 'm', width: 220 }],
    x: 'x',
    series: 'region',
    metrics: ['m', 'gone'],
  });
});
it('chooses numeric metric defaults and exposes repairable compatibility issues', () => {
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      value={{ layout: 'metric', columns: [] }}
      plan={plan}
      onChange={onChange}
    />,
  );
  expect(
    screen.getByRole('checkbox', { name: '数量' }).getAttribute('aria-checked'),
  ).toBe('true');
  expect(screen.queryByRole('checkbox', { name: '样本' })).toBeNull();
  expect(screen.getByRole('status').textContent).toMatch(/分组/);
  fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
  expect(onChange.mock.calls[0][0].metrics).toEqual([]);
});
it('disables configuration without an executed plan or when stale', () => {
  const onChange = vi.fn();
  const view = render(
    <AnalysisPresentationEditor
      value={{ layout: 'table', columns: [] }}
      onChange={onChange}
    />,
  );
  expect(
    screen.getByRole('combobox', { name: '图表类型' }).hasAttribute('disabled'),
  ).toBe(true);
  view.rerender(
    <AnalysisPresentationEditor
      value={{ layout: 'bar', columns: [] }}
      plan={plan}
      disabled
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
  expect(onChange).not.toHaveBeenCalled();
});

it('preserves stale aliases on type changes and repairs them only on explicit request', async () => {
  const onChange = vi.fn();
  const view = render(
    <AnalysisPresentationEditor
      value={{
        layout: 'bar',
        columns: [
          { alias: 'gone', width: 100 },
          { alias: 'm', width: 200 },
        ],
        x: 'gone',
        series: 'gone',
        metrics: ['gone'],
      }}
      plan={plan}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
  const option = await screen.findByRole('option', { name: '饼图' });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  expect(onChange.mock.calls[0][0]).toMatchObject({
    layout: 'pie',
    x: 'gone',
    metrics: ['gone'],
  });
  view.rerender(
    <AnalysisPresentationEditor
      value={onChange.mock.calls[0][0]}
      plan={plan}
      onChange={onChange}
    />,
  );
  fireEvent.click(
    screen.getByRole('button', { name: '按当前结果修复失效映射' }),
  );
  expect(onChange.mock.lastCall![0]).toMatchObject({
    columns: [{ alias: 'm', width: 200 }],
    x: '',
    metrics: [],
  });
});

it('keeps chart choices when visiting the data table and returning', async () => {
  const original: AnalysisPresentation = {
    layout: 'bar',
    columns: [],
    x: 'x',
    series: 'region',
    metrics: ['m'],
    orientation: 'horizontal',
    stacked: true,
  };
  let latest = original;
  function Example() {
    const [value, setValue] = useState(original);
    return (
      <AnalysisPresentationEditor
        value={value}
        plan={plan}
        onChange={next => {
          latest = next;
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  for (const label of ['数据表', '柱状图']) {
    fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
    const option = await screen.findByRole('option', { name: label });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
  }
  expect(latest).toMatchObject(original);
});
it('removes invisible stale metric references when selecting an available metric', () => {
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      value={{ layout: 'bar', columns: [], x: 'x', metrics: ['deleted'] }}
      plan={plan}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '数量' }));
  expect(onChange.mock.calls[0][0].metrics).toEqual(['m']);
});

it('preserves multiple selected metrics and explicit empty drafts across pie round trips', async () => {
  const extended: AnalysisPlan = {
    ...plan,
    schema: [
      plan.schema[0],
      { ...plan.schema[2], aggregation: 'SUM', unit: 'CNY' },
      {
        ...plan.schema[2],
        id: 'cost',
        alias: 'cost',
        title: '成本',
        aggregation: 'SUM',
        unit: 'CNY',
      },
    ],
  };
  const original: AnalysisPresentation = {
    layout: 'bar',
    columns: [],
    x: 'x',
    metrics: ['m', 'cost'],
    orientation: 'horizontal',
  };
  let latest = original;
  function Example() {
    const [value, setValue] = useState(original);
    return (
      <AnalysisPresentationEditor
        value={value}
        plan={extended}
        onChange={next => {
          latest = next;
          setValue(next);
        }}
      />
    );
  }
  const view = render(<Example />);
  for (const label of ['饼图', '柱状图']) {
    fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
    const option = await screen.findByRole('option', { name: label });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
  }
  expect(latest.metrics).toEqual(['m', 'cost']);
  view.unmount();
  const changed = vi.fn();
  render(
    <AnalysisPresentationEditor
      value={{ ...original, metrics: [] }}
      plan={extended}
      onChange={changed}
    />,
  );
  fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
  const option = await screen.findByRole('option', { name: '折线图' });
  fireEvent.pointerDown(option, { pointerType: 'mouse' });
  fireEvent.click(option);
  expect(changed.mock.calls[0][0].metrics).toEqual([]);
});
it('can leave compatibility notices to the surrounding result view', () => {
  render(
    <AnalysisPresentationEditor
      value={{ layout: 'metric', columns: [] }}
      plan={plan}
      onChange={() => {}}
      showIssues={false}
    />,
  );
  expect(screen.queryByRole('status')).toBeNull();
  expect(screen.getByRole('checkbox', { name: '数量' })).toBeTruthy();
});
it('does not materialize defaults when returning to an unchanged layout', async () => {
  const original: AnalysisPresentation = { layout: 'bar', columns: [] };
  let latest = original;
  function Example() {
    const [value, setValue] = useState(original);
    return (
      <AnalysisPresentationEditor
        value={value}
        plan={{
          ...plan,
          schema: plan.schema.filter(column => column.alias !== 'region'),
        }}
        onChange={next => {
          latest = next;
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  for (const label of ['数据表', '柱状图']) {
    fireEvent.click(screen.getByRole('combobox', { name: '图表类型' }));
    const option = await screen.findByRole('option', { name: label });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
  }
  expect(JSON.parse(JSON.stringify(latest))).toEqual(original);
});

it('updates axes, series, orientation and donut settings without losing metric selection', async () => {
  let latest: AnalysisPresentation = {
    layout: 'bar',
    columns: [],
    x: 'x',
    series: 'region',
    metrics: ['m'],
  };
  function Example() {
    const [value, setValue] = useState(latest);
    return (
      <AnalysisPresentationEditor
        value={value}
        plan={{
          ...plan,
          schema: [
            ...plan.schema,
            {
              ...plan.schema[1],
              id: 'channel',
              alias: 'channel',
              title: '渠道',
            },
          ],
        }}
        onChange={next => {
          latest = next;
          setValue(next);
        }}
      />
    );
  }
  async function select(label: string, optionLabel: string) {
    fireEvent.click(screen.getByRole('combobox', { name: label }));
    const option = await screen.findByRole('option', { name: optionLabel });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
  }
  render(<Example />);
  await select('横轴维度', '地区');
  expect(latest.x).toBe('region');
  expect(latest.series).toBeUndefined();
  await select('系列维度', '渠道');
  expect(latest.series).toBe('channel');
  await select('柱状图方向', '横向');
  expect(latest.orientation).toBe('horizontal');
  fireEvent.click(screen.getByRole('checkbox', { name: '堆叠' }));
  expect(latest.stacked).toBe(true);
  await select('图表类型', '饼图');
  fireEvent.click(screen.getByRole('checkbox', { name: '环形' }));
  expect(latest.donut).toBe(true);
  expect(latest.metrics).toEqual(['m']);
});

it('shows all five visualization cards with reasons, and selects before exposing mappings', () => {
  const onChange = vi.fn();
  const { rerender } = render(
    <AnalysisPresentationEditor
      chartOnly
      value={{ layout: 'table', columns: [] }}
      plan={plan}
      rows={[{ x: 0, region: 'A', m: 1, any: 0 }]}
      onChange={onChange}
    />,
  );
  expect(screen.queryByRole('combobox', { name: '图表类型' })).toBeNull();
  expect(screen.getAllByRole('radio')).toHaveLength(5);
  expect(screen.getByRole('radio', { name: '指标卡' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(screen.getByText(/指标卡需要无分组/)).toBeTruthy();
  fireEvent.click(screen.getByRole('radio', { name: '柱状图' }));
  const next = onChange.mock.calls[0][0];
  expect(next).toMatchObject({ layout: 'bar', x: '', metrics: ['m'] });
  rerender(
    <AnalysisPresentationEditor
      chartOnly
      value={next}
      plan={plan}
      rows={[{ x: 0, region: 'A', m: 1, any: 0 }]}
      onChange={onChange}
    />,
  );
  expect(screen.getByText('数据映射')).toBeTruthy();
  expect(screen.getByText('样式设置').closest('details')?.open).toBe(false);
});

it('does not alter an unavailable saved type or mappings when result data changes', () => {
  const onChange = vi.fn();
  const value: AnalysisPresentation = {
    layout: 'pie',
    columns: [],
    x: 'x',
    metrics: ['m'],
  };
  render(
    <AnalysisPresentationEditor
      chartOnly
      value={value}
      plan={plan}
      rows={[]}
      onChange={onChange}
    />,
  );
  expect(screen.getByRole('radio', { name: '饼图' })).toHaveProperty(
    'checked',
    true,
  );
  expect(screen.getByRole('radio', { name: '饼图' })).toHaveProperty(
    'disabled',
    true,
  );
  expect(onChange).not.toHaveBeenCalled();
});

it('keeps a selected incompatible measure named and removable without clearing other intent', () => {
  const resultPlan: AnalysisPlan = {
    ...plan,
    schema: [
      plan.schema[1],
      plan.schema[2],
      {
        ...plan.schema[2],
        id: 'avg',
        alias: 'avg',
        title: '平均额',
        aggregation: 'AVG',
      },
    ],
  };
  const value: AnalysisPresentation = {
    layout: 'pie',
    columns: [],
    x: 'region',
    metrics: ['m', 'avg'],
  };
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      chartOnly
      value={value}
      plan={resultPlan}
      rows={[{ region: 'A', m: 2, avg: 3 }]}
      onChange={onChange}
    />,
  );
  const selected = screen.getByRole('checkbox', {
    name: '平均额',
    exact: true,
  });
  expect(selected.getAttribute('aria-invalid')).toBe('true');
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(selected);
  expect(onChange.mock.calls[0][0]).toEqual({ ...value, metrics: ['m'] });
});

it('shows the same implicit measures as the renderer and explains how to repair a pie', () => {
  const resultPlan: AnalysisPlan = {
    ...plan,
    schema: [
      plan.schema[1],
      { ...plan.schema[2], aggregation: 'SUM' },
      {
        ...plan.schema[2],
        id: 'avg',
        alias: 'avg',
        title: '平均额',
        aggregation: 'AVG',
      },
    ],
  };
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      chartOnly
      value={{ layout: 'pie', columns: [], x: 'region' }}
      plan={resultPlan}
      rows={[{ region: 'A', m: 2, avg: 3 }]}
      onChange={onChange}
    />,
  );
  expect(
    screen
      .getByRole('checkbox', { name: '平均额', exact: true })
      .getAttribute('aria-checked'),
  ).toBe('true');
  expect(screen.getByText(/当前选择了 2 个/)).toBeTruthy();
  expect(screen.getByText(/平均额（AVG）不能用于占比/)).toBeTruthy();
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole('checkbox', { name: '平均额', exact: true }),
  );
  expect(onChange.mock.calls[0][0].metrics).toEqual(['m']);
});

it('repairs stale mappings without replacing an unknown chart type or crashing', () => {
  const onChange = vi.fn();
  render(
    <AnalysisPresentationEditor
      chartOnly
      value={{
        layout: 'unknown' as never,
        columns: [{ alias: 'gone' }],
        x: 'gone',
        metrics: ['gone'],
      }}
      plan={plan}
      onChange={onChange}
    />,
  );
  fireEvent.click(
    screen.getByRole('button', { name: '按当前结果修复失效映射' }),
  );
  expect(onChange).toHaveBeenCalledOnce();
  expect(onChange.mock.lastCall![0]).toMatchObject({
    layout: 'unknown',
    columns: [],
  });
});
