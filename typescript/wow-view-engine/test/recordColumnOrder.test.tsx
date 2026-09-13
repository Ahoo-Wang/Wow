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

import { keyboardOrder } from './fixtures/listOrder.js';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { RecordColumnSettings } from '../src/record/RecordColumnSettings.js';
import type { RecordColumn } from '../src/contracts/viewModel.js';
import { cleanupTable, columns, definition } from './fixtures/recordTable.js';

afterEach(cleanupTable);

it('rejects keyboard moves across pinned regions and after disabling', async () => {
  const configured: RecordColumn[] = [
    { id: 'key', kind: 'field', field: 'meta.id', title: '主键' },
    ...columns,
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
  await keyboardOrder(
    screen.getByRole('button', { name: '拖动调整名称顺序' }),
    'ArrowUp',
  );
  await keyboardOrder(
    screen.getByRole('button', { name: '拖动调整金额顺序' }),
    'ArrowDown',
  );
  expect(onChange).not.toHaveBeenCalled();
  view.rerender(
    <RecordColumnSettings
      definition={definition}
      columns={configured}
      onChange={onChange}
      disabled
    />,
  );
  await keyboardOrder(
    screen.getByRole('button', { name: '拖动调整名称顺序' }),
    'ArrowDown',
  );
  expect(onChange).not.toHaveBeenCalled();
});

it('supports keyboard reordering on the drag handle without crossing fixed regions', async () => {
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
  expect((locked as HTMLButtonElement).disabled).toBe(true);
  expect(onChange).not.toHaveBeenCalled();
  const handle = screen.getByRole('button', { name: '拖动调整金额顺序' });
  handle.focus();
  await keyboardOrder(handle, 'ArrowUp');
  expect(
    onChange.mock.lastCall?.[0].map((column: RecordColumn) => column.id),
  ).toEqual(['key', 'amount', 'name', 'actions']);
  expect(document.activeElement).toBe(handle);
  expect(
    screen.getByRole('status', { name: '排序结果' }).textContent,
  ).toContain('金额已移至第 2 项');
  await keyboardOrder(handle, 'ArrowUp');
  expect(onChange).toHaveBeenCalledTimes(1);
  await keyboardOrder(handle, 'ArrowDown');
  expect(
    onChange.mock.lastCall?.[0].map((column: RecordColumn) => column.id),
  ).toEqual(['key', 'name', 'amount', 'actions']);
});
