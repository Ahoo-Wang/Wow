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
import { fireEvent, render, screen } from '@testing-library/react';
import { filter } from '@ahoo-wang/fetcher-wow';
import { RecordTable } from '../src/record/RecordTable.js';
import type { RecordColumn, ViewInstance } from '../src/contracts/viewModel.js';
import type { RowActionsRendererProps } from '../src/record/recordReactTypes.js';
import {
  cleanupTable,
  columns,
  definition,
  instance,
  props,
} from './fixtures/recordTable.js';

afterEach(cleanupTable);

it.each([filter.gte('amount', 20), null])(
  'passes the explicit runtime filter to row actions without a saved-query fallback: %j',
  appliedFilter => {
    let context: RowActionsRendererProps | undefined;
    render(
      <RecordTable
        {...props({
          appliedFilter,
          instance: {
            ...instance,
            config: {
              ...instance.config,
              presentation: {
                layout: 'table',
                table: {
                  columns: [
                    {
                      id: 'actions',
                      kind: 'actions',
                      renderer: { name: 'actions' },
                    },
                  ],
                },
              },
            },
          },
          extensions: {
            rowActions: {
              actions: value => {
                context = value;
                return <button>处理记录</button>;
              },
            },
          },
        })}
      />,
    );
    expect(context?.filter).toBe(appliedFilter);
  },
);

it('resolves explicit cells and row actions with complete records and preserved options', () => {
  const record = {
    meta: { id: 0 },
    name: 'A',
    amount: 9,
    secret: 'full record',
  };
  const tableInstance: ViewInstance = {
    ...instance,
    config: {
      ...instance.config,
      presentation: {
        layout: 'table',
        table: {
          columns: [
            {
              ...columns[0],
              renderer: { name: 'explicit', options: { prefix: '金额' } },
            },
            { id: 'actions', kind: 'actions' },
          ],
        },
      },
    },
  };
  const refresh = vi.fn(async () => {});
  render(
    <RecordTable
      {...props({
        rows: [record],
        instance: tableInstance,
        refresh,
        definition: {
          ...definition,
          fields: [
            { ...definition.fields[0], cellRenderer: { name: 'fallback' } },
          ],
          record: {
            ...definition.record,
            recordActions: {
              row: { name: 'actions', options: { label: '刷新' } },
            },
          },
        },
        extensions: {
          cells: {
            explicit: p => (
              <span>
                {p.options?.prefix}:{p.record.secret}:{p.rowKey}:{p.value}
              </span>
            ),
            fallback: () => <span>错误回退</span>,
          },
          rowActions: {
            actions: p => (
              <button onClick={() => void p.refresh()}>
                {p.options?.label}:{p.record.secret}:{p.rowKey}
              </button>
            ),
          },
        },
      })}
    />,
  );
  expect(screen.getByText('金额:full record:0:A')).toBeTruthy();
  expect(screen.queryByText('错误回退')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '刷新:full record:0' }));
  expect(refresh).toHaveBeenCalledOnce();
  expect(screen.queryByRole('button', { name: /操作.*排序/ })).toBeNull();
});

it('isolates missing and throwing renderers while keeping built-in values readable', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const tableColumns: RecordColumn[] = [
    {
      id: 'missing',
      kind: 'field',
      field: 'name',
      renderer: { name: 'missing' },
    },
    {
      id: 'broken',
      kind: 'field',
      field: 'amount',
      renderer: { name: 'broken' },
    },
    { id: 'status', kind: 'field', field: 'status' },
    { id: 'active', kind: 'field', field: 'active' },
    { id: 'detail', kind: 'field', field: 'detail' },
  ];
  render(
    <RecordTable
      {...props({
        instance: {
          ...instance,
          config: {
            ...instance.config,
            presentation: { layout: 'table', table: { columns: tableColumns } },
          },
        },
        rows: [
          {
            meta: { id: 1 },
            name: 'A',
            amount: 5,
            status: 1,
            active: false,
            detail: { value: 2 },
          },
        ],
        extensions: {
          cells: {
            broken: () => {
              throw new Error('cell failed');
            },
          },
        },
      })}
    />,
  );
  expect(screen.getByText(/未注册.*missing/)).toBeTruthy();
  expect(screen.getByText(/渲染失败/)).toBeTruthy();
  expect(screen.getByText('已付款')).toBeTruthy();
  expect(screen.getByText('否')).toBeTruthy();
  expect(screen.getByText('{"value":2}')).toBeTruthy();
});

it('keeps renderer failures isolated across unrelated updates and recovers after extension changes', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const broken = vi.fn(() => {
    throw new Error('broken renderer');
  });
  const value = props({
    instance: {
      ...instance,
      config: {
        ...instance.config,
        presentation: {
          layout: 'table',
          table: { columns: [{ ...columns[0], renderer: { name: 'custom' } }] },
        },
      },
    },
    extensions: { cells: { custom: broken } },
  });
  const view = render(<RecordTable {...value} />);
  const attempts = broken.mock.calls.length;
  view.rerender(<RecordTable {...value} selectedRowKeys={[0]} />);
  expect(broken).toHaveBeenCalledTimes(attempts);
  view.rerender(
    <RecordTable
      {...value}
      extensions={{ cells: { custom: () => <span>渲染已恢复</span> } }}
    />,
  );
  expect(screen.getByText('渲染已恢复')).toBeTruthy();
});

it('honors configured numeric precision and shows date-only, zoned datetime and null values', () => {
  const fields = [
    { ...definition.fields[1], numberFormat: { maximumFractionDigits: 8 } },
    { field: 'date', label: '日期', type: 'date' as const },
    {
      field: 'datetime',
      label: '时间',
      type: 'datetime' as const,
    },
    { field: 'absent', label: '空值' },
  ];
  render(
    <RecordTable
      {...props({
        definition: { ...definition, fields, timeZone: 'America/Los_Angeles' },
        instance: {
          ...instance,
          config: {
            ...instance.config,
            presentation: {
              layout: 'table',
              table: {
                columns: fields.map(field => ({
                  id: field.field,
                  kind: 'field',
                  field: field.field,
                })),
              },
            },
          },
        },
        rows: [
          {
            meta: { id: 0 },
            amount: 1.2345678,
            date: '2026-09-06',
            datetime: '2026-09-06T12:30:45Z',
            absent: null,
          },
        ],
      })}
    />,
  );
  expect(screen.getByText('1.2345678')).toBeTruthy();
  expect(screen.getByText('2026-09-06')).toBeTruthy();
  expect(screen.getByText(/05:30:45/)).toBeTruthy();
  expect(screen.getByText('—')).toBeTruthy();
});
