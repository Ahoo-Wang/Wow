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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { RecordTable } from '../src/record/RecordTable.js';
import { cleanupTable, instance, props } from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('distinguishes successful empty results, loading and query failures and focuses results before retrying', () => {
  const onQueryRetry = vi.fn(() => {
    expect(document.activeElement).toBe(screen.getByLabelText('记录结果'));
  });
  const value = props({ rows: [], onQueryRetry });
  const view = render(<RecordTable {...value} />);
  expect(screen.getByRole('img', { name: '暂无记录' })).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByRole('status')).toBeNull();

  view.rerender(<RecordTable {...value} querying queryError="先前查询失败" />);
  expect(screen.getByRole('status', { name: '正在加载记录' })).toBeTruthy();
  expect(screen.getByLabelText('记录结果').getAttribute('aria-busy')).toBe(
    'true',
  );
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByRole('img', { name: '暂无记录' })).toBeNull();
  expect(screen.queryByRole('button', { name: '重试查询' })).toBeNull();

  view.rerender(<RecordTable {...value} queryError="查询服务不可用" />);
  const failure = screen.getByRole('alert', { name: '查询失败' });
  expect(within(failure).getByText('查询服务不可用')).toBeTruthy();
  expect(screen.queryByRole('status')).toBeNull();
  expect(screen.queryByRole('img', { name: '暂无记录' })).toBeNull();
  expect(screen.queryByText('显示上次查询结果')).toBeNull();
  expect(screen.getByLabelText('记录结果').hasAttribute('aria-busy')).toBe(
    false,
  );
  fireEvent.click(within(failure).getByRole('button', { name: '重试查询' }));
  expect(onQueryRetry).toHaveBeenCalledOnce();

  view.rerender(<RecordTable {...value} />);
  expect(screen.getByRole('img', { name: '暂无记录' })).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
});

it('keeps prior rows and page metrics visible while query and all-record summaries retry independently', async () => {
  const onQueryRetry = vi.fn();
  const onSummaryRetry = vi.fn();
  const value = props({
    instance: {
      ...instance,
      config: {
        ...instance.config,
        presentation: {
          layout: 'table',
          table: {
            columns: [
              { id: 'name', kind: 'field', field: 'name' },
              {
                id: 'amount',
                kind: 'field',
                field: 'amount',
                summary: ['SUM'],
              },
            ],
          },
        },
      },
    },
    queryError: '查询服务不可用',
    onQueryRetry,
    pageSummary: {
      status: 'success',
      values: { amount: { SUM: 10 } },
      error: null,
    },
    allSummary: { status: 'error', values: {}, error: '聚合服务不可用' },
    onSummaryRetry,
  });
  const view = render(<RecordTable {...value} />);
  expect(screen.getAllByRole('alert')).toHaveLength(2);
  expect(screen.getByRole('cell', { name: 'Zulu' })).toBeTruthy();
  expect(screen.getByRole('row', { name: '本页汇总' }).textContent).toContain(
    '合计10',
  );
  const query = within(screen.getByRole('alert', { name: '查询失败' }));
  expect(query.getByText('显示上次查询结果')).toBeTruthy();
  fireEvent.click(query.getByRole('button', { name: '重试查询' }));
  expect(onQueryRetry).toHaveBeenCalledOnce();
  expect(onSummaryRetry).not.toHaveBeenCalled();

  fireEvent.click(
    screen.getByRole('button', { name: '所有汇总失败，查看详情' }),
  );
  const summary = within(
    await screen.findByRole('dialog', { name: '所有汇总失败' }),
  );
  expect(summary.getByText('聚合服务不可用')).toBeTruthy();
  fireEvent.click(summary.getByRole('button', { name: '重试汇总' }));
  expect(onSummaryRetry).toHaveBeenCalledOnce();
  expect(onQueryRetry).toHaveBeenCalledOnce();

  view.rerender(<RecordTable {...value} querying />);
  expect(screen.queryByRole('alert', { name: '查询失败' })).toBeNull();
  expect(screen.getByRole('cell', { name: 'Zulu' })).toBeTruthy();
  expect(screen.queryByRole('status', { name: '正在加载记录' })).toBeNull();
  expect(screen.getByRole('row', { name: '本页汇总' }).textContent).toContain(
    '合计10',
  );
});
