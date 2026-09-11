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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { aggregation, SortDirection } from '@ahoo-wang/fetcher-wow';
import { AnalysisTable } from '../src/analysis/AnalysisTable.js';
import { formatAnalysisValue } from '../src/analysis/analysisFormatting.js';
vi.mock('../src/analysis/analysisFormatting.js', { spy: true });
import type { AnalysisPlan } from '../src/analysis/analysisModel.js';
afterEach(cleanup);
const plan: AnalysisPlan = {
  query: {
    groupBy: [aggregation.terms('state', 'state')],
    metrics: [aggregation.count('orders')],
  },
  schema: [
    {
      id: 's',
      alias: 'state',
      title: '状态',
      role: 'dimension',
      valueType: 'string',
      nullable: true,
    },
    {
      id: 'c',
      alias: 'orders',
      title: '订单数',
      role: 'metric',
      valueType: 'number',
      nullable: false,
      format: 'count',
    },
  ],
};
it('shows all aliases, zero and null distinctly, and empty results', () => {
  const view = render(
    <AnalysisTable plan={plan} rows={[{ state: null, orders: 0 }]} sort={[]} />,
  );
  expect(screen.getByText('0')).toBeTruthy();
  expect(screen.getByText('无值')).toBeTruthy();
  expect(screen.getByRole('columnheader', { name: /订单数/ })).toBeTruthy();
  view.rerender(<AnalysisTable plan={plan} rows={[]} sort={[]} />);
  expect(screen.getByText('没有符合条件的分析结果')).toBeTruthy();
});
it('cycles explicit sort and guards old or pending result sorting', () => {
  const onSortChange = vi.fn();
  const view = render(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[]}
      onSortChange={onSortChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序订单数' }));
  expect(onSortChange).toHaveBeenLastCalledWith([
    { alias: 'orders', direction: SortDirection.ASC },
  ]);
  view.rerender(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[{ alias: 'orders', direction: SortDirection.DESC }]}
      onSortChange={onSortChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序订单数' }));
  expect(onSortChange).toHaveBeenLastCalledWith([]);
  view.rerender(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[]}
      onSortChange={onSortChange}
      stale
    />,
  );
  expect(
    (screen.getByRole('button', { name: '排序订单数' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
it('keeps multi-sort priority when changing an existing direction', () => {
  const change = vi.fn();
  render(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[
        { alias: 'orders', direction: SortDirection.ASC },
        { alias: 'state', direction: SortDirection.DESC },
      ]}
      onSortChange={change}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序订单数' }));
  expect(change).toHaveBeenLastCalledWith([
    { alias: 'orders', direction: SortDirection.DESC },
    { alias: 'state', direction: SortDirection.DESC },
  ]);
});
it('restores planned column widths and display order', () => {
  render(
    <AnalysisTable
      plan={{
        ...plan,
        schema: [{ ...plan.schema[1], width: 240 }, plan.schema[0]],
      }}
      rows={[{ state: 'ready', orders: 2 }]}
      sort={[]}
    />,
  );
  const headers = screen.getAllByRole('columnheader');
  expect(headers.map(header => header.textContent)).toEqual(['订单数', '状态']);
  expect(headers[0].style.width).toBe('240px');
});

it('pages only the returned rows in groups of 100 without dispatching sort or queries', () => {
  const rows = Array.from({ length: 201 }, (_, i) => ({
    state: `group-${i}`,
    orders: i,
  }));
  const onSortChange = vi.fn();
  const view = render(
    <AnalysisTable
      plan={plan}
      rows={rows}
      sort={[]}
      onSortChange={onSortChange}
    />,
  );
  expect(screen.getAllByRole('row')).toHaveLength(101);
  expect(screen.getByText(/已返回结果.*201/)).toBeTruthy();
  expect(screen.queryByText('group-100')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(screen.getByText('group-100')).toBeTruthy();
  expect(screen.queryByText('group-0')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(screen.getAllByRole('row')).toHaveLength(2);
  expect(screen.getByText('group-200')).toBeTruthy();
  expect(
    (screen.getByRole('button', { name: '下一页' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(onSortChange).not.toHaveBeenCalled();
  // A refresh keeps the current page, even with fresh objects and changed values.
  view.rerender(
    <AnalysisTable
      plan={structuredClone(plan)}
      rows={rows.map(row => ({ ...row, orders: row.orders + 1 }))}
      sort={[]}
    />,
  );
  expect(screen.getByText('group-200')).toBeTruthy();
  view.rerender(
    <AnalysisTable plan={plan} rows={rows.slice(0, 150)} sort={[]} />,
  );
  expect(screen.getByText('group-100')).toBeTruthy();
  view.rerender(
    <AnalysisTable plan={structuredClone(plan)} rows={rows} sort={[]} />,
  );
  expect(screen.getByText('group-100')).toBeTruthy();
  view.rerender(
    <AnalysisTable
      plan={{ ...plan, query: { ...plan.query, limit: 200 } }}
      rows={rows}
      sort={[]}
    />,
  );
  expect(screen.getByText('group-0')).toBeTruthy();
  view.rerender(
    <AnalysisTable plan={plan} rows={rows.slice(0, 100)} sort={[]} />,
  );
  expect(screen.queryByRole('button', { name: '下一页' })).toBeNull();
});
it('formats display labels and count grouping while retaining raw values in titles', () => {
  const formatted = {
    ...plan,
    schema: [
      { ...plan.schema[0], options: [{ value: 'FAILED', label: '失败' }] },
      { ...plan.schema[1], aggregation: 'COUNT' as const },
    ],
  };
  render(
    <AnalysisTable
      plan={formatted}
      rows={[{ state: 'FAILED', orders: 581234 }]}
      sort={[]}
    />,
  );
  expect(screen.getByText('失败').getAttribute('title')).toBe('FAILED');
  expect(screen.getByText('581,234').getAttribute('title')).toBe('581234');
});

it('reuses unchanged result cells while query status changes and refreshes replaced rows', () => {
  const rows = [{ state: 'paid', orders: 2 }];
  const view = render(<AnalysisTable plan={plan} rows={rows} sort={[]} />);
  vi.mocked(formatAnalysisValue).mockClear();
  view.rerender(
    <AnalysisTable plan={plan} rows={rows} sort={[]} stale querying />,
  );
  expect(screen.getByText(/配置已修改/)).toBeTruthy();
  expect(formatAnalysisValue).not.toHaveBeenCalled();
  view.rerender(
    <AnalysisTable
      plan={plan}
      rows={[{ state: 'paid', orders: 3 }]}
      sort={[]}
    />,
  );
  expect(screen.getByText('3')).toBeTruthy();
  expect(formatAnalysisValue).toHaveBeenCalled();
});

it('reserves implicit dimension sort capacity while retaining existing sort actions', () => {
  const change = vi.fn();
  const view = render(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[]}
      maxSort={1}
      onSortChange={change}
    />,
  );
  const metric = screen.getByRole('button', {
    name: '排序订单数',
  }) as HTMLButtonElement;
  expect(metric.disabled).toBe(true);
  fireEvent.click(metric);
  expect(change).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '排序状态' }));
  expect(change).toHaveBeenLastCalledWith([
    { alias: 'state', direction: SortDirection.ASC },
  ]);
  view.rerender(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[{ alias: 'orders', direction: SortDirection.ASC }]}
      maxSort={2}
      onSortChange={change}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序订单数' }));
  expect(change).toHaveBeenLastCalledWith([
    { alias: 'orders', direction: SortDirection.DESC },
  ]);
  view.rerender(
    <AnalysisTable
      plan={plan}
      rows={[]}
      sort={[{ alias: 'orders', direction: SortDirection.DESC }]}
      maxSort={2}
      onSortChange={change}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '排序订单数' }));
  expect(change).toHaveBeenLastCalledWith([]);
});
