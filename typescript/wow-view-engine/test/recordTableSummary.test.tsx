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
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from '@testing-library/react';
import { RecordTable } from '../src/record/RecordTable.js';
import type { ViewInstance } from '../src/contracts/viewModel.js';
import {
  cleanupTable,
  definition,
  instance,
  props,
} from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('shows one spinner per loading scope instead of repeating loading text for every metric', () => {
  const configured: ViewInstance = {
    ...instance,
    config: {
      ...instance.config,
      presentation: {
        layout: 'table',
        table: {
          columns: [
            {
              id: 'amount',
              kind: 'field',
              field: 'amount',
              summary: ['AVG', 'MIN', 'MAX'],
            },
            {
              id: 'amount-copy',
              kind: 'field',
              field: 'amount',
              summary: ['SUM'],
            },
          ],
        },
      },
    },
  };
  const loading = { status: 'loading' as const, values: {}, error: null };
  const draw = (selectable: boolean, querying = true) => (
    <RecordTable
      {...props({
        instance: configured,
        selectable,
        querying,
        rows: querying ? [] : props().rows,
        pageSummary: querying
          ? loading
          : {
              status: 'success',
              values: { amount: { AVG: 1, MIN: 0, MAX: 2 } },
              error: null,
            },
        allSummary: loading,
      })}
    />
  );
  const view = render(draw(true));
  expect(screen.getAllByRole('status')).toHaveLength(3);
  expect(screen.getByRole('status', { name: '正在加载记录' })).toBeTruthy();
  expect(screen.getByRole('status', { name: '本页汇总加载中' })).toBeTruthy();
  expect(screen.getByRole('status', { name: '所有汇总加载中' })).toBeTruthy();
  expect(screen.queryByText(/统计中|正在加载/)).toBeNull();
  view.rerender(draw(false));
  expect(screen.getAllByRole('status')).toHaveLength(3);
  view.rerender(draw(false, false));
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(screen.getByRole('status', { name: '所有汇总加载中' })).toBeTruthy();
});

it('formats numeric records and ordered metrics consistently with one fixed scope label', () => {
  const numberFormat = { style: 'currency' as const, currency: 'CNY' };
  const formattedDefinition = {
    ...definition,
    fields: definition.fields.map(field =>
      field.field === 'amount' ? { ...field, numberFormat } : field,
    ),
  };
  const value = 2025.3333333333333;
  const configured: ViewInstance = {
    ...instance,
    config: {
      ...instance.config,
      presentation: {
        layout: 'table',
        table: {
          columns: [
            {
              id: 'amount',
              kind: 'field',
              field: 'amount',
              summary: ['MAX', 'AVG', 'SUM'],
            },
          ],
        },
      },
    },
  };
  const drawProps = props({
    definition: formattedDefinition,
    instance: configured,
    selectable: true,
    rows: [{ meta: { id: 'one' }, amount: value }],
    pageSummary: {
      status: 'success',
      values: { amount: { SUM: 6076, AVG: value, MAX: 3600 } },
      error: null,
    },
    allSummary: {
      status: 'success',
      values: { amount: { SUM: 6076, AVG: value, MAX: 3600 } },
      error: null,
    },
  });
  const view = render(<RecordTable {...drawProps} />);
  expect(screen.getByRole('cell', { name: '¥2,025.33' })).toBeTruthy();
  const page = within(screen.getByRole('row', { name: '本页汇总' }));
  expect(
    page.getByRole('rowheader', { name: '本页' }).getAttribute('data-pinned'),
  ).toBe('start');
  expect(screen.getAllByText('本页')).toHaveLength(1);
  expect(screen.getAllByText('所有')).toHaveLength(1);
  expect(
    page.getAllByRole('group').map(node => node.getAttribute('aria-label')),
  ).toEqual(['金额合计', '金额平均值', '金额最大值']);
  expect(page.getByText('¥2,025.33')).toBeTruthy();
  expect(page.queryByText(/本页 ·/)).toBeNull();
  view.rerender(<RecordTable {...drawProps} selectable={false} />);
  const singleColumn = within(screen.getByRole('row', { name: '本页汇总' }));
  expect(singleColumn.getByRole('rowheader', { name: '本页' })).toBeTruthy();
  expect(singleColumn.getByText('¥2,025.33')).toBeTruthy();
});

it('keeps summary failures in their scope and retries from its error details', async () => {
  const configured: ViewInstance = {
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
              summary: ['SUM', 'AVG'],
            },
          ],
        },
      },
    },
  };
  const retry = vi.fn();
  const pageSummary = {
    status: 'success' as const,
    values: { amount: { SUM: 0, AVG: 0 } },
    error: null,
  };
  const view = render(
    <RecordTable
      {...props({
        instance: configured,
        pageSummary,
        allSummary: {
          status: 'success',
          values: { amount: { SUM: null, AVG: null } },
          error: null,
        },
        onSummaryRetry: retry,
      })}
    />,
  );
  const footer = screen.getByRole('row', { name: '所有汇总' });
  expect(screen.getByRole('row', { name: '本页汇总' }).textContent).toContain(
    '合计0',
  );
  expect(footer.textContent).toContain('合计—');
  expect(footer.textContent).toContain('平均值—');
  expect(screen.getByRole('row', { name: '本页汇总' }).textContent).toContain(
    '平均值0',
  );
  expect(screen.queryByRole('combobox', { name: '汇总范围' })).toBeNull();
  expect(screen.queryByText('记录数')).toBeNull();
  expect(screen.getByRole('cell', { name: 'Zulu' })).toBeTruthy();
  view.rerender(
    <RecordTable
      {...props({
        instance: configured,
        pageSummary,
        allSummary: { status: 'error', values: {}, error: '统计失败' },
        onSummaryRetry: retry,
      })}
    />,
  );
  const scope = within(screen.getByRole('row', { name: '所有汇总' }));
  expect(scope.getByRole('alert').textContent).toContain('所有汇总失败');
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(screen.queryByText('统计失败')).toBeNull();
  const error = scope.getByRole('button', { name: '所有汇总失败，查看详情' });
  expect(screen.getByRole('row', { name: '本页汇总' }).textContent).toContain(
    '合计0',
  );
  expect(screen.getByRole('cell', { name: 'Zulu' })).toBeTruthy();
  fireEvent.click(error);
  const details = within(
    await screen.findByRole('dialog', { name: '所有汇总失败' }),
  );
  expect(details.getByText('统计失败')).toBeTruthy();
  fireEvent.click(details.getByRole('button', { name: '重试汇总' }));
  expect(retry).toHaveBeenCalledOnce();
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await waitFor(() => expect(document.activeElement).toBe(error));
});

it.each([true, false])(
  'shows one error per scope with selection %s and keeps page errors separate',
  async selectable => {
    const configured: ViewInstance = {
      ...instance,
      config: {
        ...instance.config,
        presentation: {
          layout: 'table',
          table: {
            columns: [
              {
                id: 'amount',
                kind: 'field',
                field: 'amount',
                summary: ['SUM', 'AVG', 'MIN'],
              },
            ],
          },
        },
      },
    };
    render(
      <RecordTable
        {...props({
          instance: configured,
          selectable,
          pageSummary: {
            status: 'error',
            values: {},
            error: '本页有非数值内容',
          },
          allSummary: { status: 'error', values: {}, error: '聚合服务不可用' },
          onSummaryRetry: vi.fn(),
        })}
      />,
    );
    expect(screen.getAllByRole('alert')).toHaveLength(2);
    for (const label of ['本页', '所有']) {
      const scope = within(screen.getByRole('row', { name: `${label}汇总` }));
      expect(
        scope.getAllByRole('button', { name: /汇总失败，查看详情/ }),
      ).toHaveLength(1);
      expect(scope.getAllByText('—')).toHaveLength(3);
    }
    fireEvent.click(
      screen.getByRole('button', { name: '本页汇总失败，查看详情' }),
    );
    const details = within(
      await screen.findByRole('dialog', { name: '本页汇总失败' }),
    );
    expect(details.getByText('本页有非数值内容')).toBeTruthy();
    expect(details.queryByRole('button', { name: '重试汇总' })).toBeNull();
  },
);
