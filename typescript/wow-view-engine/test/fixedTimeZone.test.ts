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
import { expect, it } from 'vitest';
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import {
  compileBuiltinFilter,
  compileFilterConfiguration,
} from '../src/filter/filterCore.js';
import { dateTimeValue } from '../src/filter/filterDateTimeValue.js';
import { formatRecordDateTime } from '../src/record/recordValueFormat.js';
import { validateViewDefinition } from '../src/record/recordValidation.js';
import type { FilterFieldDefinition } from '../src/filter/filterModel.js';

const field: FilterFieldDefinition = {
  field: 'created',
  label: '创建时间',
  type: 'datetime',
};
const format = {
  locale: 'en-GB',
  dateStyle: 'short',
  timeStyle: 'short',
} as const;

it.each([
  ['+08:00', '2026-09-09T00:00:00.000Z'],
  ['+0530', '2026-09-09T02:30:00.000Z'],
  ['-03:30', '2026-09-09T11:30:00.000Z'],
  ['-00:30', '2026-09-09T08:30:00.000Z'],
  ['+08', '2026-09-09T00:00:00.000Z'],
  ['-00:00', '2026-09-09T08:00:00.000Z'],
])(
  'uses the same fixed offset %s for saved values, calendar bounds and display',
  (timeZone, instant) => {
    const timestamp = Date.parse(instant);
    const context = { operator: Op.EQ, field, fields: [field], timeZone };
    const value = { date: '2026-09-09', time: '08:00:00' };
    expect(() =>
      validateViewDefinition({
        id: 'dates',
        title: 'Dates',
        sourceId: 'dates',
        record: { rowKey: 'created', allowedLayouts: ['table'] },
        fields: [field],
        timeZone,
      }),
    ).not.toThrow();
    expect(
      compileBuiltinFilter(
        { value },
        { ...context, options: { showTime: true } },
      ),
    ).toEqual({ op: Op.EQ, field: 'created', value: timestamp });
    expect(dateTimeValue(timestamp, timeZone)).toMatchObject(value);
    expect(compileBuiltinFilter({ value }, context)).toEqual({
      op: Op.BETWEEN,
      field: 'created',
      lowerBound: timestamp - 8 * 3600000,
      upperBound: timestamp + 16 * 3600000 - 1,
    });
    expect(
      compileFilterConfiguration(
        {
          mode: 'advanced',
          root: node(Op.EQ, 'created', { value: timestamp }, {}),
        },
        [field],
        undefined,
        undefined,
        timeZone,
      ).errors,
    ).toEqual([]);
    expect(formatRecordDateTime(timestamp, 'datetime', timeZone, format)).toBe(
      '09/09/2026, 08:00',
    );
    expect(
      formatRecordDateTime('2026-09-09T08:00:00', 'datetime', timeZone, format),
    ).toBe('09/09/2026, 08:00');
  },
);

it.each([
  '+24:00',
  '+08:60',
  'prefix+08:00',
  '+08:00junk',
  '+08:00\n',
  'Bad/Zone',
  '',
])(
  'rejects invalid timezone %j even for unset conditions and empty cells',
  timeZone => {
    expect(
      compileFilterConfiguration(
        { mode: 'advanced', root: node(Op.MATCH_ALL, undefined, {}, {}) },
        [field],
        undefined,
        undefined,
        timeZone,
      ).errors,
    ).not.toEqual([]);
    expect(() =>
      compileBuiltinFilter(
        {},
        { operator: Op.EQ, field, fields: [field], timeZone },
      ),
    ).toThrow();
    expect(() => formatRecordDateTime(null, 'datetime', timeZone)).toThrow();
  },
);

it('keeps the configured offset label in long time formats', () => {
  expect(
    formatRecordDateTime('2026-09-09T04:00:00Z', 'datetime', '+0530', {
      ...format,
      timeStyle: 'long',
    }),
  ).toBe('09/09/2026, 09:30:00 GMT+05:30');
});

import { node } from './fixtures/filterCore.js';
