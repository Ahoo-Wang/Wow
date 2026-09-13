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

it('toggles a pin icon without asking for a side and preserves configured sides until edited', () => {
  const configured: RecordColumn[] = [
    { ...columns[0], pinned: 'right' },
    columns[1],
  ];
  const onChange = vi.fn();
  function Example({ disabled = false }) {
    const [value, setValue] = useState(configured);
    return (
      <RecordColumnSettings
        definition={definition}
        columns={value}
        disabled={disabled}
        onChange={next => {
          onChange(next);
          setValue(next);
        }}
      />
    );
  }
  const view = render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  expect(screen.queryByRole('combobox', { name: /固定位置/ })).toBeNull();
  const amountPin = screen.getByRole('button', { name: '固定金额' });
  expect(amountPin.getAttribute('aria-pressed')).toBe('false');
  fireEvent.click(amountPin);
  expect(amountPin.getAttribute('aria-pressed')).toBe('true');
  expect(onChange.mock.lastCall?.[0]).toEqual([
    { ...columns[1], pinned: 'right' },
    configured[0],
  ]);
  fireEvent.click(amountPin);
  expect(amountPin.getAttribute('aria-pressed')).toBe('false');
  expect(onChange.mock.lastCall?.[0]).toEqual([
    { ...columns[1], pinned: false },
    configured[0],
  ]);
  const namePin = screen.getByRole('button', { name: '固定名称' });
  expect(namePin.getAttribute('aria-pressed')).toBe('true');
  fireEvent.click(namePin);
  expect(namePin.getAttribute('aria-pressed')).toBe('false');
  expect((namePin as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(namePin);
  expect(onChange).toHaveBeenCalledTimes(3);
  view.rerender(<Example disabled />);
  fireEvent.click(namePin);
  expect(onChange).toHaveBeenCalledTimes(3);
  expect(configured[0].pinned).toBe('right');
});

it('pins only beside one pinned neighbor and inherits that side', () => {
  const configured: RecordColumn[] = [
    { id: 'key', kind: 'field', field: 'meta.id', title: '主键' },
    ...columns,
    { id: 'status', kind: 'field', field: 'status', title: '状态' },
    { id: 'actions', kind: 'actions', title: '操作' },
  ];
  const onChange = vi.fn();
  function Example() {
    const [current, setCurrent] = useState(configured);
    return (
      <RecordColumnSettings
        definition={definition}
        columns={current}
        onChange={next => {
          onChange(next);
          setCurrent(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  const pin = (title: string) =>
    screen.getByRole('button', { name: `固定${title}` }) as HTMLButtonElement;
  expect(pin('金额').disabled).toBe(true);
  fireEvent.click(pin('金额'));
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(pin('名称'));
  expect(
    onChange.mock.lastCall?.[0].find(
      (column: RecordColumn) => column.id === 'name',
    ).pinned,
  ).toBe('left');
  fireEvent.click(pin('状态'));
  expect(
    onChange.mock.lastCall?.[0].find(
      (column: RecordColumn) => column.id === 'status',
    ).pinned,
  ).toBe('right');
  expect(pin('金额').disabled).toBe(true);
  expect(pin('金额').title).toContain('上下列均已固定');
  fireEvent.click(pin('名称'));
  expect(pin('金额').disabled).toBe(false);
  fireEvent.click(pin('金额'));
  expect(
    onChange.mock.lastCall?.[0].find(
      (column: RecordColumn) => column.id === 'amount',
    ).pinned,
  ).toBe('right');
  expect(pin('名称').disabled).toBe(true);
  expect(pin('主键').disabled).toBe(true);
  expect(pin('操作').disabled).toBe(true);
  expect(screen.queryByRole('spinbutton')).toBeNull();
});

it('changes column order and visibility without hiding the final column', async () => {
  const onChange = vi.fn();
  function Example() {
    const [value, setValue] = useState(columns);
    return (
      <RecordColumnSettings
        definition={definition}
        columns={value}
        onChange={next => {
          onChange(next);
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  const handle = screen.getByRole('button', { name: '拖动调整金额顺序' });
  await keyboardOrder(handle, 'ArrowUp');
  expect(onChange).toHaveBeenLastCalledWith([columns[1], columns[0]]);
  fireEvent.click(screen.getByRole('checkbox', { name: '显示名称' }));
  expect(
    (
      screen.getByRole('checkbox', { name: '显示金额' }) as HTMLInputElement
    ).getAttribute('aria-disabled'),
  ).toBe('true');
  expect(screen.queryByRole('spinbutton')).toBeNull();
});

it('selects multiple summary functions and clears the last selection without closing the list', async () => {
  const onChange = vi.fn();
  function Example() {
    const [columns, setColumns] = useState<RecordColumn[]>([
      { id: 'amount', kind: 'field', field: 'amount', summary: ['SUM'] },
    ]);
    return (
      <RecordColumnSettings
        definition={definition}
        columns={columns}
        onChange={next => {
          setColumns(next);
          onChange(next);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.click(screen.getByRole('button', { name: '列设置' }));
  fireEvent.click(screen.getByRole('combobox', { name: '金额汇总方式' }));
  const average = await screen.findByRole('option', { name: '平均值' });
  fireEvent.pointerDown(average, { pointerType: 'mouse' });
  fireEvent.click(average);
  expect(onChange.mock.lastCall?.[0][0].summary).toEqual(['SUM', 'AVG']);
  expect(
    screen.getByRole('option', { name: '合计' }).getAttribute('aria-selected'),
  ).toBe('true');
  expect(
    screen
      .getByRole('option', { name: '平均值' })
      .getAttribute('aria-selected'),
  ).toBe('true');
  const sum = screen.getByRole('option', { name: '合计' });
  fireEvent.pointerDown(sum, { pointerType: 'mouse' });
  fireEvent.click(sum);
  expect(onChange.mock.lastCall?.[0][0].summary).toEqual(['AVG']);
  fireEvent.pointerDown(average, { pointerType: 'mouse' });
  fireEvent.click(average);
  expect(onChange.mock.lastCall?.[0][0].summary).toBeUndefined();
  expect(
    screen.getByRole('combobox', { name: '金额汇总方式' }).textContent,
  ).toContain('不汇总');
  expect(screen.getByRole('listbox').getAttribute('aria-multiselectable')).toBe(
    'true',
  );
});
