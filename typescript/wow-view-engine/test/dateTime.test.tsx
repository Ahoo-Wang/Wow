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
import { afterEach, expect, it } from 'vitest';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { FilterDatePicker } from '../src/filter/FilterDatePicker.js';
import { FilterTimeInput } from '../src/filter/FilterTimeInput.js';

afterEach(cleanup);

it.each([
  ['12:', '分钟', '30', '12:30'],
  ['07:45:', '秒', '30', '07:45:30'],
  ['07:45:12.123456789', '秒', '30', '07:45:30'],
])(
  'preserves existing parts of %s when selecting %s',
  async (value, part, next, expected) => {
    const changes: string[] = [];
    render(
      <FilterTimeInput
        label="时间"
        value={value}
        onValueChange={value => changes.push(value)}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '时间选择时间' }));
    fireEvent.click(
      await screen.findByRole('combobox', { name: `时间${part}` }),
    );
    const option = await screen.findByRole('option', {
      name: next,
      exact: true,
    });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
    expect(changes).toEqual([expected]);
  },
);

it('accepts unset date and time values without marking them invalid', () => {
  render(
    <>
      <FilterDatePicker label="创建日期" onValueChange={() => {}} />
      <FilterTimeInput label="创建时刻" onValueChange={() => {}} />
    </>,
  );
  expect(
    screen
      .getByRole('button', { name: '创建日期：选择日期' })
      .getAttribute('aria-invalid'),
  ).toBe('false');
  const input = screen.getByRole('textbox', { name: '创建时刻' });
  expect((input as HTMLInputElement).value).toBe('');
  expect(input.getAttribute('aria-invalid')).toBe('false');
});

it('disables date changes when the host disables an already open picker', async () => {
  const changes: (Date | undefined)[] = [];
  const props = {
    label: '创建日期',
    value: new Date(2026, 8, 5),
    onValueChange: (date: Date | undefined) => changes.push(date),
  };
  const view = render(<FilterDatePicker {...props} />);
  fireEvent.click(screen.getByRole('button', { name: /创建日期/ }));
  await screen.findByRole('dialog');
  view.rerender(<FilterDatePicker {...props} disabled />);
  const day = document.querySelector<HTMLButtonElement>(
    '[data-day="2026/9/6"]',
  )!;
  expect(day.disabled).toBe(true);
  fireEvent.click(day);
  expect(changes).toEqual([]);
});

it('selects the calendar day and keeps its popup outside clipping parents', async () => {
  const chosen: Date[] = [];
  const { container } = render(
    <div style={{ overflow: 'hidden', height: 40 }}>
      <FilterDatePicker
        label="创建日期"
        value={new Date(2026, 8, 5)}
        onValueChange={date => {
          if (date) chosen.push(date);
        }}
      />
    </div>,
  );
  fireEvent.click(screen.getByRole('button', { name: /创建日期/ }));
  const dialog = await screen.findByRole('dialog');
  const day = dialog.querySelector<HTMLButtonElement>('[data-day="2026/9/6"]');
  expect(day).not.toBeNull();
  expect(container.contains(dialog)).toBe(false);
  const selected = dialog.querySelector<HTMLButtonElement>(
    '[data-day="2026/9/5"]',
  )!;
  act(() => selected.focus());
  fireEvent.keyDown(selected, { key: 'ArrowRight' });
  await waitFor(() => expect(document.activeElement).toBe(day));
  fireEvent.click(day!);
  expect(
    chosen.map(date => [date.getFullYear(), date.getMonth(), date.getDate()]),
  ).toEqual([[2026, 8, 6]]);
});

it('retains incomplete time input so callers cannot silently reuse an old value', () => {
  const changes: string[] = [];
  function Example() {
    const [value, setValue] = useState('09:00');
    return (
      <FilterTimeInput
        label="截止时间"
        value={value}
        onValueChange={next => {
          changes.push(next);
          setValue(next);
        }}
      />
    );
  }
  render(<Example />);
  const input = screen.getByRole('textbox', { name: '截止时间' });
  fireEvent.change(input, { target: { value: '12:' } });
  expect((input as HTMLInputElement).value).toBe('12:');
  expect(input.getAttribute('aria-invalid')).toBe('true');
  fireEvent.change(input, { target: { value: '' } });
  expect(changes).toEqual(['12:', '']);
  expect(input.getAttribute('aria-invalid')).toBe('false');
});

it('changes the chosen time part at second precision', async () => {
  const changes: string[] = [];
  render(
    <FilterTimeInput
      label="截止时间"
      value="09:30:45.123456789"
      onValueChange={next => changes.push(next)}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '截止时间选择时间' }));
  fireEvent.click(
    await screen.findByRole('combobox', { name: '截止时间小时' }),
  );
  const hour = await screen.findByRole('option', { name: '10', exact: true });
  fireEvent.pointerDown(hour, { pointerType: 'mouse' });
  fireEvent.click(hour);
  expect(changes).toEqual(['10:30:45']);
});
