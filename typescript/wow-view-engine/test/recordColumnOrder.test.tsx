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
import { fireEvent, render, screen } from '@testing-library/react';
import { RecordColumnSettings } from '../src/record/RecordColumnSettings.js';
import type { RecordColumn } from '../src/contracts/viewModel.js';
import {
  cleanupTable,
  columns,
  definition,
  mockColumnLayout,
} from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('keeps drag reordering within its fixed region and cancels without changing columns', () => {
  const configured: RecordColumn[] = [
    { id: 'key', kind: 'field', field: 'meta.id', title: '主键' },
    ...columns,
    { id: 'status', kind: 'field', field: 'status', title: '状态' },
    {
      id: 'right',
      kind: 'field',
      field: 'status',
      title: '右侧',
      pinned: 'right',
    },
    { id: 'actions', kind: 'actions' },
  ];
  const onChange = vi.fn();
  const view = render(
    <RecordColumnSettings
      definition={definition}
      columns={configured}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  mockColumnLayout();
  const handle = screen.getByRole('button', { name: '拖动调整金额顺序' });
  const dataTransfer = {
    setData: vi.fn(),
    setDragImage: vi.fn(),
    effectAllowed: '',
    dropEffect: '',
  };
  const target = (title: string) =>
    screen.getByRole('checkbox', { name: `显示${title}` }).closest('li')!;
  const nameHandle = screen.getByRole('button', { name: '拖动调整名称顺序' });
  fireEvent.dragStart(nameHandle, { dataTransfer });
  fireEvent.drop(target('状态'), { dataTransfer, clientY: 300 });
  expect(
    onChange.mock.lastCall?.[0].map((column: RecordColumn) => column.id),
  ).toEqual(['key', 'amount', 'status', 'name', 'right', 'actions']);
  onChange.mockClear();
  for (const title of ['主键', '右侧', '操作']) {
    fireEvent.dragStart(handle, { dataTransfer });
    const clientY = target(title).getBoundingClientRect().top + 10;
    fireEvent.dragOver(target(title), { dataTransfer, clientY });
    fireEvent.drop(target(title), { dataTransfer, clientY });
  }
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.dragStart(handle, { dataTransfer });
  fireEvent.dragOver(target('名称'), { dataTransfer, clientY: 166 });
  fireEvent.dragEnd(handle, { dataTransfer });
  fireEvent.drop(target('名称'), { dataTransfer, clientY: 166 });
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.dragStart(handle, { dataTransfer });
  view.rerender(
    <RecordColumnSettings
      definition={definition}
      columns={configured}
      onChange={onChange}
      disabled
    />,
  );
  fireEvent.drop(target('名称'), { dataTransfer, clientY: 166 });
  expect(onChange).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: /上移|下移/ })).toBeNull();
});

it('accepts the gap shown by the drop marker and uses the pointer side of each row', () => {
  const configured: RecordColumn[] = [
    ...columns,
    { id: 'status', kind: 'field', field: 'status', title: '状态' },
  ];
  const onChange = vi.fn();
  render(
    <RecordColumnSettings
      definition={definition}
      columns={configured}
      onChange={onChange}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  const list = mockColumnLayout();
  const handle = screen.getByRole('button', { name: '拖动调整名称顺序' });
  const dataTransfer = {
    setData: vi.fn(),
    setDragImage: vi.fn(),
    effectAllowed: '',
    dropEffect: '',
  };
  fireEvent.dragStart(handle, { dataTransfer });
  // A fast drag may leave its last dragover at the unchanged position.
  expect(fireEvent.dragOver(list, { dataTransfer, clientY: 166 })).toBe(false);
  // Amount ends at 200; status starts at 212. The insertion line is in this gap.
  expect(fireEvent.dragOver(list, { dataTransfer, clientY: 206 })).toBe(false);
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.drop(list, { dataTransfer, clientY: 206 });
  expect(onChange).toHaveBeenLastCalledWith([
    configured[1],
    configured[0],
    configured[2],
  ]);
  onChange.mockClear();
  const status = list.children[2];
  fireEvent.dragStart(handle, { dataTransfer });
  fireEvent.dragOver(status, { dataTransfer, clientY: 220 });
  fireEvent.dragLeave(list, { relatedTarget: status });
  expect(
    list.querySelector('[data-slot="column-drop-indicator"]'),
  ).toBeTruthy();
  fireEvent.dragLeave(list, { relatedTarget: document.body });
  expect(list.querySelector('[data-slot="column-drop-indicator"]')).toBeNull();
  fireEvent.keyDown(handle, { key: 'Tab' });

  fireEvent.drop(status, { dataTransfer, clientY: 220 });
  expect(onChange).toHaveBeenLastCalledWith([
    configured[1],
    configured[0],
    configured[2],
  ]);
  fireEvent.dragStart(handle, { dataTransfer });
  fireEvent.dragOver(status, { dataTransfer, clientY: 248 });
  fireEvent.drop(status, { dataTransfer, clientY: 248 });
  expect(onChange).toHaveBeenLastCalledWith([
    configured[1],
    configured[2],
    configured[0],
  ]);
});

it('supports keyboard reordering on the drag handle without crossing fixed regions', () => {
  const onChange = vi.fn();
  function Example() {
    const [value, setValue] = useState<RecordColumn[]>([
      { id: 'key', kind: 'field', field: 'meta.id', title: '主键' },
      ...columns,
      { id: 'actions', kind: 'actions' },
    ]);
    return (
      <RecordColumnSettings
        definition={definition}
        columns={value}
        onChange={next => {
          setValue(next);
          onChange(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  const locked = screen.getByRole('button', { name: '拖动调整主键顺序' });
  const transfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' };
  expect(fireEvent.dragStart(locked, { dataTransfer: transfer })).toBe(false);
  expect(transfer.setData).not.toHaveBeenCalled();
  expect(onChange).not.toHaveBeenCalled();
  const handle = screen.getByRole('button', { name: '拖动调整金额顺序' });
  handle.focus();
  fireEvent.keyDown(handle, { key: 'ArrowUp' });
  expect(
    onChange.mock.lastCall?.[0].map((column: RecordColumn) => column.id),
  ).toEqual(['key', 'amount', 'name', 'actions']);
  expect(document.activeElement).toBe(handle);
  expect(screen.getByRole('status').textContent).toContain('金额已移至第 2 项');
  fireEvent.keyDown(handle, { key: 'ArrowUp' });
  expect(onChange).toHaveBeenCalledTimes(1);
  fireEvent.keyDown(handle, { key: 'ArrowDown' });
  expect(
    onChange.mock.lastCall?.[0].map((column: RecordColumn) => column.id),
  ).toEqual(['key', 'name', 'amount', 'actions']);
});
