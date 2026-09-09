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
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import {
  FilterDateTimeRange,
  type FilterDateTimeRangeProps,
} from '../src/filter/FilterDateTimeRange.js';
import { getBuiltinFilterCompiler } from '../src/filter/builtinFilterCompilers.js';
import type { FilterFieldDefinition } from '../src/filter/filterModel.js';
import { mount } from './fixtures/filterValueEditor.js';

afterEach(cleanup);

function mountRange(
  field: FilterFieldDefinition,
  initial: FilterDateTimeRangeProps['value'],
  options: Pick<FilterDateTimeRangeProps, 'showTime' | 'timeZone'> = {},
) {
  let current = initial;
  const validity = vi.fn();
  const changes = vi.fn();
  function Example() {
    const [value, setValue] = useState(initial);
    return (
      <FilterDateTimeRange
        field={field}
        {...options}
        value={value}
        onValidityChange={validity}
        onValueChange={next => {
          current = next;
          changes(next);
          setValue(next);
        }}
      />
    );
  }
  const view = render(<Example />);
  return {
    ...view,
    current: () => current,
    validity,
    changes,
    compile: () =>
      getBuiltinFilterCompiler('datetime-range')!.compile(current, {
        operator: Op.BETWEEN,
        field,
        fields: [field],
        timeZone: options.timeZone,
        options: { showTime: options.showTime },
      }),
  };
}

function day(dialog: HTMLElement, date: string) {
  return dialog.querySelector<HTMLButtonElement>(`[data-day="${date}"]`)!;
}

it('uses one range calendar for dates, retains an unfinished range and clears both bounds', async () => {
  const state = mountRange(
    { field: 'date', label: '日期', type: 'date' },
    { lowerBound: '2026-09-01', upperBound: '2026-09-02' },
  );
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  fireEvent.click(
    screen.getByRole('button', {
      name: '日期日期范围：2026-09-01 至 2026-09-02',
    }),
  );
  const dialog = await screen.findByRole('dialog', { name: '日期日期范围' });
  expect(state.container.contains(dialog)).toBe(false);
  fireEvent.click(day(dialog, '2026/9/5'));
  expect(state.current()).toEqual({
    lowerBound: '2026-09-05',
    upperBound: undefined,
  });
  expect(state.validity).toHaveBeenLastCalledWith(false, expect.any(String));
  expect(day(dialog, '2026/9/5').dataset.selectedSingle).toBe('true');
  act(() => day(dialog, '2026/9/5').focus());
  fireEvent.keyDown(day(dialog, '2026/9/5'), { key: 'ArrowRight' });
  await waitFor(() =>
    expect(document.activeElement).toBe(day(dialog, '2026/9/6')),
  );
  fireEvent.click(day(dialog, '2026/9/7'));
  expect(state.compile()).toEqual({
    op: Op.BETWEEN,
    field: 'date',
    lowerBound: '2026-09-05',
    upperBound: '2026-09-07',
  });
  expect(state.validity).toHaveBeenLastCalledWith(true);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.click(screen.getByRole('button', { name: /日期日期范围/ }));
  const selectedDialog = await screen.findByRole('dialog');
  expect(day(selectedDialog, '2026/9/5').dataset.rangeStart).toBe('true');
  expect(day(selectedDialog, '2026/9/6').dataset.rangeMiddle).toBe('true');
  expect(day(selectedDialog, '2026/9/7').dataset.rangeEnd).toBe('true');
  fireEvent.click(await screen.findByRole('button', { name: '清空区间' }));
  expect(state.current()).toEqual({
    lowerBound: undefined,
    upperBound: undefined,
  });
  expect(state.compile()).toBeUndefined();
  expect(state.validity).toHaveBeenLastCalledWith(true);
});

it('confirms a same-day datetime range at second precision while retaining DST offsets', async () => {
  const initial = {
    lowerBound: Date.parse('2026-11-01T06:30:00.123Z'),
    upperBound: Date.parse('2026-11-01T06:45:00.456Z'),
  };
  const state = mountRange(
    {
      field: 'created',
      label: '创建',
      type: 'datetime',
    },
    initial,
    { showTime: true, timeZone: 'America/New_York' },
  );
  expect(screen.queryByRole('textbox', { name: '创建开始时间' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  const dialog = await screen.findByRole('dialog');
  expect(screen.getAllByRole('grid')).toHaveLength(1);
  expect(
    (screen.getByRole('textbox', { name: '创建开始时间' }) as HTMLInputElement)
      .value,
  ).toBe('01:30:00');
  expect(
    (screen.getByRole('textbox', { name: '创建结束时间' }) as HTMLInputElement)
      .value,
  ).toBe('01:45:00');
  expect(state.changes).not.toHaveBeenCalled();
  fireEvent.click(day(dialog, '2026/11/1'));
  fireEvent.click(day(dialog, '2026/11/1'));
  expect(state.current()).toEqual(initial);
  expect(state.validity).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog')).toBe(dialog);
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(state.compile()).toEqual({
    op: Op.BETWEEN,
    field: 'created',
    lowerBound: Date.parse('2026-11-01T06:30:00Z'),
    upperBound: Date.parse('2026-11-01T06:45:00Z'),
  });
  expect(state.current().lowerBound).toEqual({
    date: '2026-11-01',
    time: '01:30:00',
    offsetMinutes: 300,
  });
  expect(state.current().upperBound).toEqual({
    date: '2026-11-01',
    time: '01:45:00',
    offsetMinutes: 300,
  });
  expect(state.changes).toHaveBeenCalledTimes(1);
  expect(state.validity).toHaveBeenLastCalledWith(true);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('selects dates without injecting clock values into an unset datetime range', async () => {
  const state = mountRange(
    { field: 'created', label: '创建', type: 'datetime' },
    { lowerBound: { date: '2026-09-01' } },
    { showTime: true, timeZone: 'Asia/Shanghai' },
  );
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(day(dialog, '2026/9/1'));
  expect(
    (screen.getByRole('textbox', { name: '创建开始时间' }) as HTMLInputElement)
      .value,
  ).toBe('');
  expect(
    (screen.getByRole('textbox', { name: '创建结束时间' }) as HTMLInputElement)
      .value,
  ).toBe('');
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(screen.getByRole('alert')).toBeTruthy();
  expect(state.changes).not.toHaveBeenCalled();
  expect(state.validity).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole('textbox', { name: '创建开始时间' }), {
    target: { value: '09:30' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: '创建结束时间' }), {
    target: { value: '10:30' },
  });
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(state.current()).toEqual({
    lowerBound: { date: '2026-09-01', time: '09:30' },
    upperBound: { date: '2026-09-01', time: '10:30' },
  });
  expect(state.validity).toHaveBeenLastCalledWith(true);
});

it('keeps invalid local edits in the popup without publishing unconfirmed values', async () => {
  const initial = {
    lowerBound: {
      date: '2026-09-',
      time: '09:30:45.123456789',
      offsetMinutes: -480,
    },
    upperBound: { time: '12:' },
  };
  const state = mountRange(
    { field: 'created', label: '创建', type: 'datetime' },
    initial,
    { showTime: true, timeZone: 'Asia/Shanghai' },
  );
  const trigger = screen.getByRole('button', { name: /创建日期范围/ });
  expect(trigger.getAttribute('aria-invalid')).toBe('true');
  fireEvent.click(trigger);
  await screen.findByRole('dialog');
  expect(screen.getByRole('textbox', { name: '创建开始时间' })).toHaveProperty(
    'value',
    '09:30:45',
  );
  fireEvent.change(screen.getByRole('textbox', { name: '创建结束时间' }), {
    target: { value: '12:30' },
  });
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  const error = screen.getByRole('alert');
  expect(screen.getByRole('textbox', { name: '创建结束时间' })).toHaveProperty(
    'value',
    '12:30',
  );
  expect(
    screen
      .getByRole('textbox', { name: '创建结束时间' })
      .getAttribute('aria-describedby'),
  ).toBe(error.id);
  expect(
    screen
      .getByRole('textbox', { name: '创建开始日期' })
      .getAttribute('aria-describedby'),
  ).toBe(error.id);
  expect(state.current()).toEqual(initial);
  expect(state.changes).not.toHaveBeenCalled();
  expect(state.validity).not.toHaveBeenCalled();
});

it.each(['取消', 'Escape'])(
  'discards unconfirmed date and time changes on %s and restores them when reopened',
  async action => {
    const initial = {
      lowerBound: {
        date: '2026-09-01',
        time: '09:30:45.123',
        offsetMinutes: -480,
      },
      upperBound: {
        date: '2026-09-03',
        time: '10:40:50.456',
        offsetMinutes: -480,
      },
    };
    const state = mountRange(
      { field: 'created', label: '创建', type: 'datetime' },
      initial,
      { showTime: true, timeZone: 'Asia/Shanghai' },
    );
    const trigger = screen.getByRole('button', { name: /创建日期范围/ });
    fireEvent.click(trigger);
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(day(dialog, '2026/9/5'));
    const time = screen.getByRole('textbox', { name: '创建结束时间' });
    fireEvent.change(time, { target: { value: '12:30' } });
    if (action === 'Escape') fireEvent.keyDown(time, { key: 'Escape' });
    else
      fireEvent.click(
        screen.getByRole('button', { name: action, exact: true }),
      );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(state.changes).not.toHaveBeenCalled();
    expect(state.validity).not.toHaveBeenCalled();
    expect(state.current()).toEqual(initial);
    fireEvent.click(trigger);
    await screen.findByRole('dialog');
    expect(
      screen.getByRole('textbox', { name: '创建结束时间' }),
    ).toHaveProperty('value', '10:40:50');
    expect(day(screen.getByRole('dialog'), '2026/9/1').dataset.rangeStart).toBe(
      'true',
    );
  },
);

it('waits for confirmation before clearing a datetime range', async () => {
  const state = mountRange(
    { field: 'created', label: '创建', type: 'datetime' },
    {
      lowerBound: { date: '2026-09-01', time: '09:30' },
      upperBound: { date: '2026-09-03', time: '10:30' },
    },
    { showTime: true, timeZone: 'Asia/Shanghai' },
  );
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  await screen.findByRole('dialog');
  fireEvent.click(screen.getByRole('button', { name: '清空区间' }));
  expect(state.changes).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(state.current()).toEqual({
    lowerBound: undefined,
    upperBound: undefined,
  });
  expect(state.compile()).toBeUndefined();
});

it.each([
  ['value', { value: { lowerBound: { date: '2026-09-02', time: '08:00' } } }],
  ['disabled', { disabled: true }],
  ['showTime', { showTime: false }],
  ['timeZone', { timeZone: 'America/New_York' }],
] as const)(
  'discards an open datetime draft when %s changes',
  async (_name, update) => {
    const onValueChange = vi.fn();
    const props: FilterDateTimeRangeProps = {
      field: { field: 'created', label: '创建', type: 'datetime' },
      value: {
        lowerBound: { date: '2026-09-01', time: '09:30' },
        upperBound: { date: '2026-09-03', time: '10:30' },
      },
      showTime: true,
      timeZone: 'Asia/Shanghai',
      onValueChange,
    };
    const view = render(<FilterDateTimeRange {...props} />);
    fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByRole('textbox', { name: '创建结束时间' }), {
      target: { value: '12:30' },
    });
    view.rerender(<FilterDateTimeRange {...props} {...update} />);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(onValueChange).not.toHaveBeenCalled();
  },
);

it('retains a draft across equivalent props and callback replacements', async () => {
  const previous = vi.fn(),
    latest = vi.fn();
  const props: FilterDateTimeRangeProps = {
    field: { field: 'created', label: '创建', type: 'datetime' },
    value: {
      lowerBound: { date: '2026-09-01', time: '09:30' },
      upperBound: { date: '2026-09-03', time: '10:30' },
    },
    showTime: true,
    timeZone: 'Asia/Shanghai',
    onValueChange: previous,
  };
  const view = render(<FilterDateTimeRange {...props} />);
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  await screen.findByRole('dialog');
  fireEvent.change(screen.getByRole('textbox', { name: '创建结束时间' }), {
    target: { value: '12:30' },
  });
  view.rerender(
    <FilterDateTimeRange
      {...props}
      value={{ ...props.value }}
      onValueChange={latest}
    />,
  );
  expect(screen.getByRole('textbox', { name: '创建结束时间' })).toHaveProperty(
    'value',
    '12:30',
  );
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(previous).not.toHaveBeenCalled();
  expect(latest).toHaveBeenCalledWith({
    ...props.value,
    upperBound: { date: '2026-09-03', time: '12:30' },
  });
});

it.each(['date', 'datetime'] as const)(
  'uses the same picker for a generic %s BETWEEN editor',
  type => {
    const state = mount(
      {
        id: 'range',
        operator: Op.BETWEEN,
        field: 'created',
        component: { name: 'builtin' },
        props: {},
      },
      { field: 'created', label: '创建', type },
    );
    expect(
      screen.getByRole('button', { name: '创建日期范围：选择日期范围' }),
    ).toBeTruthy();
    expect(screen.queryByRole('textbox', { name: '创建下限日期' })).toBeNull();
    expect(screen.queryByRole('textbox', { name: '创建上限日期' })).toBeNull();
    expect(state.changes).toEqual([]);
  },
);

it('disables an already open range calendar and its clear action', async () => {
  const onValueChange = vi.fn();
  const props: FilterDateTimeRangeProps = {
    field: { field: 'created', label: '创建', type: 'date' },
    value: { lowerBound: '2026-09-01', upperBound: '2026-09-03' },
    onValueChange,
  };
  const view = render(<FilterDateTimeRange {...props} />);
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  const dialog = await screen.findByRole('dialog');
  view.rerender(<FilterDateTimeRange {...props} disabled />);
  expect(day(dialog, '2026/9/5').disabled).toBe(true);
  expect(
    (screen.getByRole('button', { name: '清空区间' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  fireEvent.click(day(dialog, '2026/9/5'));
  expect(onValueChange).not.toHaveBeenCalled();
});

it('does not limit restored date ranges to the current year', async () => {
  const state = mountRange(
    { field: 'date', label: '日期', type: 'date' },
    { lowerBound: '2099-12-01', upperBound: '2099-12-03' },
  );
  fireEvent.click(screen.getByRole('button', { name: /日期日期范围/ }));
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(day(dialog, '2099/12/5'));
  fireEvent.click(day(dialog, '2099/12/6'));
  expect(state.current()).toEqual({
    lowerBound: '2099-12-05',
    upperBound: '2099-12-06',
  });
});

it('defaults datetime controls to dates and selects a range across two visible months', async () => {
  const state = mountRange(
    { field: 'created', label: '创建', type: 'datetime' },
    { lowerBound: { date: '2026-09-01' }, upperBound: { date: '2026-09-03' } },
  );
  expect(screen.queryByRole('textbox', { name: '创建开始时间' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  const dialog = await screen.findByRole('dialog');
  expect(screen.getByRole('grid', { name: '2026年10月' })).toBeTruthy();
  fireEvent.click(day(dialog, '2026/9/29'));
  fireEvent.click(
    day(screen.getByRole('grid', { name: '2026年10月' }), '2026/10/2'),
  );
  expect(state.current()).toEqual({
    lowerBound: { date: '2026-09-29' },
    upperBound: { date: '2026-10-02' },
  });
  expect(state.validity).toHaveBeenLastCalledWith(true);
});
