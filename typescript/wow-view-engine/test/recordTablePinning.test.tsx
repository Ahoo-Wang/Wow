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
import { RecordTable } from '../src/record/RecordTable.js';
import { RecordColumnSettings } from '../src/record/RecordColumnSettings.js';
import {
  type RecordColumn,
  type ViewDefinition,
} from '../src/record/recordModel.js';
import { getRecordColumnPinning } from '../src/record/recordColumns.js';
import {
  cleanupTable,
  definition,
  instance,
  props,
} from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('keeps headers, records and summaries aligned with default action pins, resizing and hidden columns', () => {
  const pinnedColumns: RecordColumn[] = [
    { id: 'review', title: '审核', kind: 'actions', width: 80 },
    {
      id: 'name',
      kind: 'field',
      field: 'name',
      width: 120,
      pinned: 'left',
    },
    {
      id: 'hidden',
      kind: 'field',
      field: 'status',
      width: 900,
      pinned: 'left',
      visible: false,
    },
    {
      id: 'amount',
      kind: 'field',
      field: 'amount',
      width: 200,
      summary: ['SUM'],
    },
    {
      id: 'alias',
      title: '别名',
      kind: 'field',
      field: 'name',
      width: 100,
      pinned: 'left',
    },
    { id: 'more', title: '更多', kind: 'actions', width: 64 },
  ];
  const onColumnsChange = vi.fn();
  const draw = (next = pinnedColumns, selectable = true) => (
    <RecordTable
      {...props({
        instance: {
          ...instance,
          config: {
            ...instance.config,
            presentation: { layout: 'table', table: { columns: next } },
          },
        },
        definition: {
          ...definition,
          recordActions: { row: { name: 'actions' } },
        },
        extensions: { rowActions: { actions: () => <button>查看</button> } },
        pageSummary: {
          status: 'success',
          values: { amount: { SUM: 10 } },
          error: null,
        },
        allSummary: {
          status: 'success',
          values: { amount: { SUM: 100 } },
          error: null,
        },
        selectable,
        onColumnsChange,
      })}
    />
  );
  const view = render(draw());
  const cells = (selector: string) =>
    Array.from(view.container.querySelectorAll<HTMLElement>(selector));
  expect(cells('thead th').map(cell => cell.textContent)).toEqual([
    '',
    '名称',
    '别名',
    '金额',
    '审核',
    '更多',
  ]);
  expect(cells('tbody td').map(cell => cell.textContent)).toEqual([
    '',
    'Zulu',
    'Zulu',
    '10',
    '查看',
    '查看',
  ]);
  expect(
    cells('tfoot tr:first-child > *').map(cell => cell.textContent),
  ).toEqual(['本页', '合计10', '', '']);
  for (const selector of ['thead th', 'tbody td']) {
    const row = cells(selector);
    expect(row[3].hasAttribute('style')).toBe(false);
    expect(row[3].classList.contains('fve:text-right')).toBe(true);
    expect(row[3].classList.contains('fve:tabular-nums')).toBe(true);
    expect(row[0].style.left).toBe('0px');
    expect(row[1].style.left).toBe('48px');
    expect(row[2].style.left).toBe('168px');
    expect(row[4].style.right).toBe('64px');
    expect(row[5].style.right).toBe('0px');
  }
  for (const selector of [
    'tfoot tr:first-child > *',
    'tfoot tr:last-child > *',
  ]) {
    const row = cells(selector);
    expect(row[1].hasAttribute('style')).toBe(false);
    expect(row[1].classList.contains('fve:text-right')).toBe(true);
    expect(row[1].classList.contains('fve:tabular-nums')).toBe(true);
    expect(row[0].getAttribute('colspan')).toBe('3');
    expect(row[0].style.left).toBe('0px');
    expect(row[0].style.width).toBe('268px');
    expect(row[2].style.right).toBe('64px');
    expect(row[3].style.right).toBe('0px');
  }
  expect(cells('col')[3].style.width).toBe('200px');
  view.rerender(
    draw(
      pinnedColumns.map(column =>
        column.id === 'name' ? { ...column, width: 140 } : column,
      ),
    ),
  );
  expect(cells('tbody td')[2].style.left).toBe('188px');
  expect(cells('tfoot tr:first-child > *')[0].style.width).toBe('288px');
  view.rerender(
    draw(
      pinnedColumns.map(column =>
        column.id === 'name'
          ? { ...column, visible: false }
          : column.id === 'more'
            ? { ...column, pinned: false }
            : column,
      ),
      false,
    ),
  );
  expect(cells('thead th').map(cell => cell.textContent)).toEqual([
    '别名',
    '金额',
    '审核',
    '更多',
  ]);
  expect(cells('tbody td')[0].style.left).toBe('0px');
  expect(cells('tbody td')[2].style.right).toBe('64px');
  expect(cells('tbody td')[3].style.right).toBe('0px');
  expect(cells('tfoot tr:first-child > *')[0].style.width).toBe('100px');
  expect(onColumnsChange).not.toHaveBeenCalled();
});

it('locks the bound row key and actions to opposite edges despite conflicting preferences or order', () => {
  const configured: RecordColumn[] = [
    {
      id: 'actions',
      kind: 'actions',
      title: '操作',
      width: 80,
      pinned: 'left',
    },
    {
      id: 'left',
      kind: 'field',
      field: 'name',
      title: '左侧字段',
      width: 100,
      pinned: 'left',
    },
    {
      id: 'right',
      kind: 'field',
      field: 'amount',
      title: '右侧字段',
      width: 90,
      pinned: 'right',
    },
    {
      id: 'primary',
      kind: 'field',
      field: 'meta.id',
      title: '主键',
      width: 70,
      pinned: false,
    },
    {
      id: 'id',
      kind: 'field',
      field: 'name',
      title: '普通 ID 列',
      width: 110,
      pinned: false,
    },
  ];
  const before = structuredClone(configured);
  expect(getRecordColumnPinning(configured[0], definition.rowKey)).toBe(
    'right',
  );
  expect(getRecordColumnPinning(configured[3], definition.rowKey)).toBe('left');
  expect(getRecordColumnPinning(configured[4], definition.rowKey)).toBe(false);
  const boundDefinition: ViewDefinition = {
    ...definition,
    fields: [
      ...definition.fields,
      { field: 'meta.id', label: '主键', type: 'number' },
    ],
    recordActions: { row: { name: 'actions' } },
  };
  const onChange = vi.fn();
  const view = render(
    <>
      <RecordColumnSettings
        definition={boundDefinition}
        columns={configured}
        onChange={onChange}
      />
      <RecordTable
        {...props({
          definition: boundDefinition,
          instance: {
            ...instance,
            config: {
              ...instance.config,
              presentation: { layout: 'table', table: { columns: configured } },
            },
          },
          selectable: true,
          extensions: { rowActions: { actions: () => <button>查看</button> } },
          onColumnsChange: onChange,
        })}
      />
    </>,
  );
  const headers = Array.from(
    view.container.querySelectorAll<HTMLElement>('thead th'),
  );
  expect(headers.map(cell => cell.textContent)).toEqual([
    '',
    '主键',
    '左侧字段',
    '普通 ID 列',
    '右侧字段',
    '操作',
  ]);
  for (const selector of ['thead th', 'tbody td']) {
    const cells = view.container.querySelectorAll<HTMLElement>(selector);
    expect(cells[1].style.left).toBe('48px');
    expect(cells[2].style.left).toBe('118px');
    expect(cells[4].style.right).toBe('80px');
    expect(cells[5].style.right).toBe('0px');
  }
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  expect(screen.queryByRole('combobox', { name: '主键固定位置' })).toBeNull();
  expect(screen.queryByRole('combobox', { name: '操作固定位置' })).toBeNull();
  expect(screen.getByRole('button', { name: '固定普通 ID 列' })).toBeTruthy();
  for (const name of ['固定主键', '固定操作']) {
    const button = screen.getByRole('button', { name }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(button);
  }
  for (const name of ['主键', '左侧字段', '右侧字段', '操作'])
    expect(
      (
        screen.getByRole('button', {
          name: `拖动调整${name}顺序`,
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  expect(configured).toEqual(before);
  expect(onChange).not.toHaveBeenCalled();
});
