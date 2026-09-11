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
import { afterEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { RecordTable } from '../src/record/RecordTable.js';
import type { RecordColumn } from '../src/contracts/viewModel.js';
import {
  cleanupTable,
  columns,
  definition,
  instance,
  props,
  mockTableWidth,
} from './fixtures/recordTable.js';

afterEach(cleanupTable);

function columnWidth(header: HTMLElement): string {
  const index = Array.from(header.parentElement!.children).indexOf(header);
  return header
    .closest('table')!
    .querySelectorAll<HTMLTableColElement>('colgroup col')[index].style.width;
}

it('fills available width with automatic business columns and preserves explicit widths after resizing', () => {
  const resize = mockTableWidth();
  const onColumnsChange = vi.fn();
  const configured: RecordColumn[] = [
    { id: 'key', kind: 'field', field: 'meta.id', width: 120 },
    { id: 'name', kind: 'field', field: 'name' },
    {
      id: 'amount',
      kind: 'field',
      field: 'amount',
      width: 100,
      summary: ['SUM'],
    },
    { id: 'actions', kind: 'actions', width: 80 },
  ];
  function Example() {
    const [current, setCurrent] = useState(configured);
    return (
      <RecordTable
        {...props({
          definition: {
            ...definition,
            fields: [
              ...definition.fields,
              { field: 'meta.id', label: '编号', type: 'string' },
            ],
            record: {
              ...definition.record,
              recordActions: { row: { name: 'actions' } },
            },
          },
          instance: {
            ...instance,
            config: {
              ...instance.config,
              presentation: { layout: 'table', table: { columns: current } },
            },
          },
          selectable: true,
          extensions: { rowActions: { actions: () => <button>查看</button> } },
          onColumnsChange: columns => {
            onColumnsChange(columns);
            setCurrent(columns);
          },
        })}
      />
    );
  }
  render(<Example />);
  act(() => resize(1000));
  const name = screen.getByRole('columnheader', { name: /名称/ });
  expect(columnWidth(name)).toBe('480px');
  expect(columnWidth(screen.getByRole('columnheader', { name: /编号/ }))).toBe(
    '120px',
  );
  expect(columnWidth(screen.getByRole('columnheader', { name: /金额/ }))).toBe(
    '100px',
  );
  expect(columnWidth(screen.getByRole('columnheader', { name: /操作/ }))).toBe(
    '80px',
  );
  expect(onColumnsChange).not.toHaveBeenCalled();
  act(() => resize(600));
  expect(columnWidth(name)).toBe('252px');
  act(() => resize(1000));
  fireEvent.keyDown(screen.getByRole('separator', { name: '调整名称列宽' }), {
    key: 'ArrowLeft',
  });
  expect(onColumnsChange).toHaveBeenLastCalledWith(
    configured.map(column =>
      column.id === 'name' ? { ...column, width: 470 } : column,
    ),
  );
  expect(columnWidth(name)).toBe('470px');
  act(() => resize(1200));
  expect(columnWidth(name)).toBe('470px');
  expect(screen.getByRole('table').style.width).toBe('1200px');
  expect(screen.getByRole('columnheader', { name: /操作/ }).style.right).toBe(
    '0px',
  );
  expect(screen.getAllByRole('columnheader')).toHaveLength(5);
  expect(onColumnsChange).toHaveBeenCalledTimes(1);
  act(() => resize(400));
  expect(screen.getByRole('table').style.width).toBe('818px');
});

it('keeps fractional automatic columns automatic after a resize gesture without movement', () => {
  const resize = mockTableWidth();
  const onColumnsChange = vi.fn();
  function Example() {
    const [current, setCurrent] = useState<RecordColumn[]>(
      ['a', 'b', 'c'].map(id => ({ id, kind: 'field', field: 'name' })),
    );
    return (
      <RecordTable
        {...props({
          instance: {
            ...instance,
            config: {
              ...instance.config,
              presentation: { layout: 'table', table: { columns: current } },
            },
          },
          onColumnsChange: columns => {
            onColumnsChange(columns);
            setCurrent(columns);
          },
        })}
      />
    );
  }
  render(<Example />);
  act(() => resize(1000));
  const handle = screen.getAllByRole('separator', { name: '调整名称列宽' })[0];
  fireEvent.mouseDown(handle, { clientX: 334 });
  fireEvent.mouseUp(document, { clientX: 334 });
  expect(onColumnsChange).not.toHaveBeenCalled();
  fireEvent.mouseDown(handle, { clientX: 334 });
  act(() => resize(1200));
  fireEvent.mouseUp(document, { clientX: 334 });
  expect(onColumnsChange).not.toHaveBeenCalled();
  for (const column of screen.getAllByRole('columnheader'))
    expect(columnWidth(column)).toBe('400px');
  fireEvent.mouseDown(handle, { clientX: 400 });
  fireEvent.mouseUp(document, { clientX: 440 });
  expect(onColumnsChange).toHaveBeenCalledTimes(1);
  expect(onColumnsChange.mock.lastCall?.[0]).toEqual([
    { id: 'a', kind: 'field', field: 'name', width: 440 },
    { id: 'b', kind: 'field', field: 'name' },
    { id: 'c', kind: 'field', field: 'name' },
  ]);
});

it('keeps business fields readable in compact tables and restores saved pinning and widths', async () => {
  const resize = mockTableWidth();
  const onColumnsChange = vi.fn();
  const configured: RecordColumn[] = [
    { id: 'key', kind: 'field', field: 'meta.id', width: 210 },
    { id: 'name', kind: 'field', field: 'name', width: 180, pinned: 'left' },
    { id: 'amount', kind: 'field', field: 'amount', width: 150 },
    { id: 'actions', kind: 'actions', width: 110 },
  ];
  render(
    <RecordTable
      {...props({
        definition: {
          ...definition,
          fields: [
            ...definition.fields,
            { field: 'meta.id', label: '编号', type: 'string' },
          ],
          record: {
            ...definition.record,
            recordActions: { row: { name: 'actions' } },
          },
        },
        instance: {
          ...instance,
          config: {
            ...instance.config,
            presentation: { layout: 'table', table: { columns: configured } },
          },
        },
        selectable: true,
        extensions: {
          rowActions: {
            actions: ({ rowKey }) => <button>查看 {rowKey}</button>,
          },
        },
        onColumnsChange,
      })}
    />,
  );
  act(() => resize(356));
  const key = screen.getByRole('columnheader', { name: /编号/ });
  const actions = screen.getByRole('columnheader', { name: /操作/ });
  expect(
    356 - 48 - parseFloat(columnWidth(key)) - parseFloat(columnWidth(actions)),
  ).toBeGreaterThanOrEqual(128);
  expect(key.getAttribute('data-pinned')).toBe('start');
  expect(actions.getAttribute('data-pinned')).toBe('end');
  expect(
    screen
      .getByRole('columnheader', { name: /名称/ })
      .getAttribute('data-pinned'),
  ).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '记录 0 操作' }));
  expect(
    within(
      await screen.findByRole('dialog', { name: '记录 0 操作' }),
    ).getByRole('button', { name: '查看 0' }),
  ).toBeTruthy();
  act(() => resize(160));
  expect(screen.getByText('空间不足，请展开视图或减少显示列。')).toBeTruthy();
  act(() => resize(1000));
  expect(screen.queryByText('空间不足，请展开视图或减少显示列。')).toBeNull();
  expect(columnWidth(screen.getByRole('columnheader', { name: /编号/ }))).toBe(
    '210px',
  );
  expect(columnWidth(screen.getByRole('columnheader', { name: /操作/ }))).toBe(
    '110px',
  );
  expect(
    screen
      .getByRole('columnheader', { name: /名称/ })
      .getAttribute('data-pinned'),
  ).toBe('start');
  expect(onColumnsChange).not.toHaveBeenCalled();
});

it('commits resizing at drag end and supports keyboard resizing', () => {
  const onColumnsChange = vi.fn();
  render(<RecordTable {...props({ onColumnsChange })} />);
  const handle = screen.getByRole('separator', { name: '调整名称列宽' });
  fireEvent.mouseDown(handle, { clientX: 180 });
  fireEvent.mouseMove(document, { clientX: 220 });
  expect(onColumnsChange).not.toHaveBeenCalled();
  fireEvent.mouseUp(document, { clientX: 220 });
  expect(onColumnsChange).toHaveBeenLastCalledWith([
    { ...columns[0], width: 220 },
    columns[1],
  ]);
  fireEvent.keyDown(handle, { key: 'ArrowRight' });
  expect(onColumnsChange).toHaveBeenLastCalledWith([
    { ...columns[0], width: 190 },
    columns[1],
  ]);
});
