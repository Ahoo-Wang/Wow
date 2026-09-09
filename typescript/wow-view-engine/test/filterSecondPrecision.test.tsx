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

import { configuration } from './fixtures/filterPanel.js';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { FilterTimeInput } from '../src/filter/FilterTimeInput.js';
import { FilterDateTimeRange } from '../src/filter/FilterDateTimeRange.js';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import type { FilterFieldDefinition } from '../src/filter/filterModel.js';
afterEach(cleanup);
const fields: FilterFieldDefinition[] = [
  {
    field: 'created',
    label: '创建',
    type: 'datetime',
    editor: { name: 'builtin', options: { showTime: true } },
  },
];
it('displays and publishes complete time input at second precision while retaining incomplete input', () => {
  const change = vi.fn();
  render(
    <FilterTimeInput
      value="09:30:45.123456789"
      label="时间"
      onValueChange={change}
    />,
  );
  expect(screen.getByRole('textbox', { name: '时间' })).toHaveProperty(
    'value',
    '09:30:45',
  );
  fireEvent.change(screen.getByRole('textbox', { name: '时间' }), {
    target: { value: '10:40:50.456' },
  });
  expect(change).toHaveBeenLastCalledWith('10:40:50');
  fireEvent.change(screen.getByRole('textbox', { name: '时间' }), {
    target: { value: '10:' },
  });
  expect(change).toHaveBeenLastCalledWith('10:');
});
it('compiles whole seconds in epoch-millisecond units and floors instants before the epoch', () => {
  for (const [value, expected] of [
    [123, 0],
    [-1, -1000],
    [{ date: '1970-01-01', time: '08:00:01.987654321' }, 1000],
  ] as const) {
    expect(
      compileFilterConfiguration(
        configuration({
          id: 'time',
          operator: Op.EQ,
          field: 'created',
          component: fields[0].editor!,
          props: { value },
        }),
        fields,
        undefined,
        undefined,
        'Asia/Shanghai',
      ),
    ).toEqual({ expression: filter.eq('created', expected), errors: [] });
  }
});
it('confirms restored fractional endpoints as whole seconds without mutating canceled source values', async () => {
  const change = vi.fn();
  const value = {
    lowerBound: Date.parse('2026-09-01T01:30:45.123Z'),
    upperBound: {
      date: '2026-09-03',
      time: '10:40:50.456',
      offsetMinutes: -480,
    },
  };
  render(
    <FilterDateTimeRange
      field={fields[0]}
      showTime
      timeZone="Asia/Shanghai"
      value={value}
      onValueChange={change}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  await screen.findByRole('dialog');
  expect(screen.getByRole('textbox', { name: '创建开始时间' })).toHaveProperty(
    'value',
    '09:30:45',
  );
  expect(change).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }));
  expect(change).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: /创建日期范围/ }));
  await screen.findByRole('dialog');
  fireEvent.click(screen.getByRole('button', { name: '确定', exact: true }));
  expect(change).toHaveBeenCalledWith({
    lowerBound: Date.parse('2026-09-01T01:30:45Z'),
    upperBound: { date: '2026-09-03', time: '10:40:50', offsetMinutes: -480 },
  });
  expect(value.upperBound.time).toBe('10:40:50.456');
});
it('keeps the complete final natural day and rejects malformed clock text', () => {
  expect(
    compileFilterConfiguration(
      configuration({
        id: 'days',
        operator: Op.BETWEEN,
        field: 'created',
        component: { name: 'datetime-range' },
        props: {
          lowerBound: { date: '2026-09-01' },
          upperBound: { date: '2026-09-01' },
        },
      }),
      fields,
      undefined,
      undefined,
      'UTC',
    ),
  ).toEqual({
    expression: filter.between(
      'created',
      Date.parse('2026-09-01T00:00:00Z'),
      Date.parse('2026-09-01T23:59:59.999Z'),
    ),
    errors: [],
  });
  for (const time of ['10:30:60.123', '10:30:50.bad', '10:30:50.', '10:'])
    expect(
      compileFilterConfiguration(
        configuration({
          id: 'bad',
          operator: Op.EQ,
          field: 'created',
          component: fields[0].editor!,
          props: { value: { date: '2026-09-01', time } },
        }),
        fields,
      ).errors.length,
    ).toBeGreaterThan(0);
});
