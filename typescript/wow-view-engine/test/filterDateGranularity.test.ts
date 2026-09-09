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
import { FilterOperator as Op, filter } from '@ahoo-wang/fetcher-wow';
import {
  compileFilterConfiguration,
  createFilterConfiguration,
} from '../src/filter/filterConfiguration.js';
import type {
  FilterComponentProperties,
  FilterConfiguration,
  FilterFieldDefinition,
} from '../src/filter/filterModel.js';

const fields: FilterFieldDefinition[] = [
  { field: 'created', label: '创建时间', type: 'datetime' },
];
function configuration(
  operator: Op,
  props: FilterComponentProperties,
  showTime?: boolean,
): FilterConfiguration {
  return {
    mode: 'simple',
    root: {
      id: 'created',
      field: 'created',
      operator,
      component: {
        name: operator === Op.BETWEEN ? 'datetime-range' : 'builtin',
        ...(showTime === undefined ? {} : { options: { showTime } }),
      },
      props,
    },
  };
}
function compile(config: FilterConfiguration, timeZone?: string) {
  return compileFilterConfiguration(
    config,
    fields,
    undefined,
    undefined,
    timeZone,
  );
}
it('defaults datetime ranges to inclusive calendar days in the global zone and restores the same query', () => {
  const config = configuration(Op.BETWEEN, {
    lowerBound: { date: '2026-09-06' },
    upperBound: { date: '2026-09-07' },
  });
  const expected = filter.between(
    'created',
    Date.parse('2026-09-05T16:00:00Z'),
    Date.parse('2026-09-07T15:59:59.999Z'),
  );
  expect(compile(config, 'Asia/Shanghai')).toEqual({
    expression: expected,
    errors: [],
  });
  const restored = createFilterConfiguration(
    JSON.parse(JSON.stringify(config)).root,
  );
  expect(compile(restored, 'Asia/Shanghai')).toEqual({
    expression: expected,
    errors: [],
  });
  expect(config.root.props.lowerBound).toEqual({ date: '2026-09-06' });
});
it.each([
  ['2026-03-08', '2026-03-08T05:00:00Z', '2026-03-09T03:59:59.999Z'],
  ['2026-11-01', '2026-11-01T04:00:00Z', '2026-11-02T04:59:59.999Z'],
])('uses actual DST calendar-day boundaries for %s', (date, start, end) => {
  expect(
    compile(configuration(Op.EQ, { value: { date } }), 'America/New_York'),
  ).toEqual({
    expression: filter.between('created', Date.parse(start), Date.parse(end)),
    errors: [],
  });
});
it('uses second precision when time is explicitly enabled, and preserves the saved choice', () => {
  const config = configuration(
    Op.BETWEEN,
    {
      lowerBound: { date: '2026-09-06', time: '09:30:45.123' },
      upperBound: { date: '2026-09-06', time: '10:00:00.456' },
    },
    true,
  );
  expect(compile(config, 'Asia/Shanghai')).toEqual({
    expression: filter.between(
      'created',
      Date.parse('2026-09-06T01:30:45Z'),
      Date.parse('2026-09-06T02:00:00Z'),
    ),
    errors: [],
  });
  expect(createFilterConfiguration(config.root).root.component.options).toEqual(
    { showTime: true },
  );
  expect(
    compile(
      configuration(
        Op.EQ,
        { value: { date: '2026-09-06', time: '09:30' } },
        false,
      ),
      'Asia/Shanghai',
    ).expression,
  ).toEqual(
    filter.between(
      'created',
      Date.parse('2026-09-05T16:00:00Z'),
      Date.parse('2026-09-06T15:59:59.999Z'),
    ),
  );
});
it('maps day comparisons while respecting the allowed user operation', () => {
  const start = Date.parse('2026-09-05T16:00:00Z'),
    end = Date.parse('2026-09-06T15:59:59.999Z');
  for (const [operator, expected] of [
    [Op.EQ, filter.between('created', start, end)],
    [Op.NE, filter.nor([filter.between('created', start, end)])],
    [Op.GT, filter.gt('created', end)],
    [Op.GTE, filter.gte('created', start)],
    [Op.LT, filter.lt('created', start)],
    [Op.LTE, filter.lte('created', end)],
  ] as const) {
    expect(
      compileFilterConfiguration(
        configuration(operator, { value: { date: '2026-09-06' } }),
        [{ ...fields[0], operators: [operator] }],
        [operator],
        undefined,
        'Asia/Shanghai',
      ),
    ).toEqual({ expression: expected, errors: [] });
  }
});
it('validates empty, partial, reversed, malformed values and configuration', () => {
  expect(compile(configuration(Op.BETWEEN, {})).expression).toEqual(
    filter.matchAll(),
  );
  for (const config of [
    configuration(Op.BETWEEN, { lowerBound: { date: '2026-09-06' } }),
    configuration(Op.BETWEEN, {
      lowerBound: { date: '2026-09-07' },
      upperBound: { date: '2026-09-06' },
    }),
    configuration(Op.EQ, { value: { date: '2026-02-30' } }),
    configuration(Op.EQ, { value: { bad: true } }),
    configuration(Op.EQ, { value: { date: '2026-09-06' } }, true),
    {
      ...configuration(Op.EQ, { value: { date: '2026-09-06' } }),
      root: {
        ...configuration(Op.EQ, {}).root,
        component: { name: 'builtin', options: { showTime: 'yes' } },
      },
    },
  ])
    expect(compile(config).errors.length).toBeGreaterThan(0);
  expect(
    compile(configuration(Op.EQ, { value: 0 }), 'Bad/Zone').errors.length,
  ).toBeGreaterThan(0);
});
it('defaults to the local timezone when the global zone is absent', () => {
  const start = new Date(2026, 8, 6).getTime(),
    end = new Date(2026, 8, 7).getTime() - 1;
  expect(
    compile(configuration(Op.EQ, { value: { date: '2026-09-06' } })),
  ).toEqual({ expression: filter.between('created', start, end), errors: [] });
});

it('expands selected days independently and keeps custom output restrictions', () => {
  const a = filter.between(
    'created',
    Date.parse('2026-09-05T16:00:00Z'),
    Date.parse('2026-09-06T15:59:59.999Z'),
  );
  const b = filter.between(
    'created',
    Date.parse('2026-09-07T16:00:00Z'),
    Date.parse('2026-09-08T15:59:59.999Z'),
  );
  expect(
    compile(
      configuration(Op.IN, {
        values: [{ date: '2026-09-06' }, { date: '2026-09-08' }],
      }),
      'Asia/Shanghai',
    ).expression,
  ).toEqual(filter.or([a, b]));
  expect(
    compile(
      configuration(Op.NOT_IN, {
        values: [{ date: '2026-09-06' }, { date: '2026-09-08' }],
      }),
      'Asia/Shanghai',
    ).expression,
  ).toEqual(filter.nor([a, b]));
  const config = configuration(Op.EQ, { value: '2026-09-06' });
  config.root.component.name = 'custom-date';
  expect(
    compileFilterConfiguration(
      config,
      [{ ...fields[0], operators: [Op.EQ] }],
      [Op.EQ],
      { 'custom-date': { compile: () => a } },
      'Asia/Shanghai',
    ).errors.length,
  ).toBeGreaterThan(0);
});

it('validates named built-in calendar-day output as wire operators, not editor choices', () => {
  const config = configuration(Op.IN, { values: [0] });
  config.root.component = { name: 'multi-select' };
  const result = compileFilterConfiguration(
    config,
    [{ ...fields[0], editor: { name: 'multi-select' } }],
    [Op.IN],
    undefined,
    'UTC',
  );
  expect(result).toEqual({
    expression: filter.or([filter.between('created', 0, 86399999)]),
    errors: [],
  });
});
