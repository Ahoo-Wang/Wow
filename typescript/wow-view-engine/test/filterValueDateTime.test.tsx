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
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { cleanup, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { compileFilterConfiguration } from '../src/filter/filterCore';
import type { FilterFieldDefinition } from '../src/filter/filterModel';
import { change, mount } from './fixtures/filterValueEditor.js';

afterEach(cleanup);

it('round-trips date text and keeps an invalid or partial date raw', () => {
  const state = mount({
    id: 'd',
    operator: Op.EQ,
    field: 'birthday',
    component: { name: 'builtin' },
    props: { value: '2026-09-06' },
  });
  expect(
    (screen.getByRole('textbox', { name: '生日日期' }) as HTMLInputElement)
      .value,
  ).toBe('2026-09-06');
  change('生日日期', '2026-09-');
  expect(state.current().props.value).toBe('2026-09-');
  change('生日日期', '');
  expect(state.current().props.value).toBeUndefined();
});

it('shows timestamp zero in the global timezone and preserves partial datetime input', () => {
  const state = mount(
    {
      id: 'dt',
      operator: Op.EQ,
      field: 'createdAt',
      component: { name: 'builtin' },
      props: { value: 0 },
    },
    undefined,
    { showTime: true, timeZone: 'Asia/Shanghai' },
  );
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间日期',
      }) as HTMLInputElement
    ).value,
  ).toBe('1970-01-01');
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间时间',
      }) as HTMLInputElement
    ).value,
  ).toBe('08:00:00');
  change('创建时间时间', '');
  expect(state.current().props.value).toEqual({
    date: '1970-01-01',
    time: undefined,
    offsetMinutes: -480,
  });
  change('创建时间日期', '');
  expect(state.current().props.value).toEqual({
    date: undefined,
    time: undefined,
    offsetMinutes: -480,
  });
  change('创建时间时间', '12:');
  expect(state.current().props.value).toEqual({
    date: undefined,
    time: '12:',
    offsetMinutes: -480,
  });
});

it('starts an unset datetime without injecting a date or midnight', () => {
  const state = mount(
    {
      id: 'dt',
      operator: Op.EQ,
      field: 'createdAt',
      component: { name: 'builtin' },
      props: {},
    },
    undefined,
    {
      showTime: true,
      timeZone: 'Asia/Shanghai',
    },
  );
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间日期',
      }) as HTMLInputElement
    ).value,
  ).toBe('');
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间时间',
      }) as HTMLInputElement
    ).value,
  ).toBe('');
  change('创建时间日期', '2026-09-06');
  expect(state.current().props.value).toEqual({ date: '2026-09-06' });
});

it('normalizes seconds while retaining the calendar day on the other side of UTC', () => {
  const field: FilterFieldDefinition = {
    field: 'createdAt',
    label: '创建时间',
    type: 'datetime',
    editor: { name: 'builtin', options: { showTime: true } },
  };
  const state = mount(
    {
      id: 'west',
      operator: Op.EQ,
      field: 'createdAt',
      component: field.editor!,
      props: { value: 123 },
    },
    field,
    { showTime: true, timeZone: 'America/Los_Angeles' },
  );
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间日期',
      }) as HTMLInputElement
    ).value,
  ).toBe('1969-12-31');
  expect(
    (
      screen.getByRole('textbox', {
        name: '创建时间时间',
      }) as HTMLInputElement
    ).value,
  ).toBe('16:00:00');
  change('创建时间时间', '17:00:00.123');
  expect(
    compileFilterConfiguration(
      configuration(state.current()),
      [field],
      undefined,
      undefined,
      'America/Los_Angeles',
    ),
  ).toEqual({
    expression: { op: Op.EQ, field: 'createdAt', value: 3600000 },
    errors: [],
  });
});

it('preserves a loaded instant when editing within a repeated DST hour', () => {
  const field: FilterFieldDefinition = {
    field: 'createdAt',
    label: '创建时间',
    type: 'datetime',
    editor: { name: 'builtin', options: { showTime: true } },
  };
  const value = Date.parse('2026-11-01T06:30:00.000Z');
  const state = mount(
    {
      id: 'dst',
      operator: Op.EQ,
      field: 'createdAt',
      component: field.editor!,
      props: { value },
    },
    field,
    { showTime: true, timeZone: 'America/New_York' },
  );
  change('创建时间时间', '01:30:00.000');
  expect(
    compileFilterConfiguration(
      configuration(state.current()),
      [field],
      undefined,
      undefined,
      'America/New_York',
    ).expression,
  ).toEqual({
    op: Op.EQ,
    field: 'createdAt',
    value,
  });
  change('创建时间时间', '01:31:00');
  expect(
    compileFilterConfiguration(
      configuration(state.current()),
      [field],
      undefined,
      undefined,
      'America/New_York',
    ).expression,
  ).toEqual({
    op: Op.EQ,
    field: 'createdAt',
    value: value + 60_000,
  });
});
