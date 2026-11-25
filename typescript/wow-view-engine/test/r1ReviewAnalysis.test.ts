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

/**
 * The first round of the deep review of the analysis, dashboard and react
 * layers and the value formatters: each test states the correct behaviour
 * and fails on main because of the defect it is named after.
 */

import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
} from '@ahoo-wang/wow-client/dsl';
import type {
  AnalysisViewConfig,
  CartesianData,
  FieldDefinition,
  MetricCardData,
  RecordData,
} from '../src/index.js';
import { numberFormatOf } from '../src/model/index.js';
import { drillConditions } from '../src/analysis/index.js';
import { shapeChart } from '../src/analysis/chart.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import type { MessageFormatters } from '../src/ui/index.js';
import { cellText, csvCellText } from '../src/ui/kit/display.js';
import { datePartValue } from '../src/ui/kit/datePart.js';
import { cellValue } from '../src/ui/record/cells.js';
import { analysisConfig } from './fixtures.js';

/**
 * Santiago's clocks go from 00:00 straight to 01:00 on 7 September 2025, so
 * that day has no midnight: Wow's day bucket of it starts at the first
 * instant it has, 01:00 -03:00 (`DateBuckets`: "a midnight a transition
 * skips begins at the transition").
 */
const SANTIAGO = 'America/Santiago';
/** 6 September 2025, 00:00 -04:00. */
const SEP_6 = Date.parse('2025-09-06T04:00:00.000Z');
/** 7 September 2025, 01:00 -03:00: the first instant of the day. */
const SEP_7 = Date.parse('2025-09-07T04:00:00.000Z');

const CREATED: FieldDefinition = {
  name: 'createdAt',
  label: 'Created',
  kind: 'datetime',
};

function daily(timeZone: string): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      {
        alias: 'day',
        field: 'createdAt',
        type: AggregationGroupType.DATE_HISTOGRAM,
        unit: AggregationDateUnit.DAY,
        timeZone,
      },
    ],
    metrics: [{ alias: 'orders', type: AggregationMetricType.COUNT }],
    layout: 'chart',
    chart: {
      type: 'line',
      cartesian: { x: 'day', series: [{ metric: 'orders' }] },
    },
  });
}

describe('r1 a day bucket in a zone whose midnight a transition skips', () => {
  it('r1 a day bucket drills to the whole of its day, up to the first instant of the next', () => {
    const conditions = drillConditions(
      daily(SANTIAGO),
      [CREATED],
      builtinFieldKinds,
      { day: SEP_6, orders: 5 },
      { timeZone: 'UTC' },
    );
    expect(conditions).toEqual([
      {
        field: 'createdAt',
        operator: FilterOperator.BETWEEN,
        value: {
          type: 'absolute',
          from: '2025-09-06T04:00:00.000Z',
          // 23:59:59.999 -04:00, the last moment of the 6th.
          to: new Date(SEP_7 - 1).toISOString(),
          timeZone: SANTIAGO,
        },
      },
    ]);
  });

  it('r1 a time axis fills the day whose midnight was skipped at the key Wow gives it', () => {
    const sep5 = Date.parse('2025-09-05T04:00:00.000Z');
    // 8 September, 00:00 -03:00.
    const sep8 = Date.parse('2025-09-08T03:00:00.000Z');
    const rows: RecordData[] = [
      { day: sep5, orders: 5 },
      { day: sep8, orders: 8 },
    ];
    const data = shapeChart(daily(SANTIAGO), rows, undefined, {
      timeZone: 'UTC',
    }) as CartesianData;
    expect(data.points.map(point => point.x)).toEqual([
      sep5,
      SEP_6,
      SEP_7,
      sep8,
    ]);
    expect(data.points.map(point => point.values.orders)).toEqual([5, 0, 0, 8]);
  });
});

/** No string here reaches the catalogue: numbers and text only. */
const WORDS = {} as MessageFormatters;

describe('r1 a number a source sent as its exact text', () => {
  it('r1 a table cell writes a whole number past 2^53 in its column format, as its text does', () => {
    const field = { kind: 'number', numberFormat: {} };
    const display = { locale: 'en' };
    const value = '9007199254740993';
    // ui/README.md 「值显示」: `valueText`, `cellText`, `cellValue` alike.
    expect(cellText(value, field, WORDS, display)).toBe(
      '9,007,199,254,740,993',
    );
    expect(cellValue(value, field, WORDS, display, 'table')).toBe(
      '9,007,199,254,740,993',
    );
  });

  it('r1 a decimal amount read as its exact text is written in its currency and scale', () => {
    // A `decimal` Manifest field reaches the engine as a string
    // (wow-client's `TextNumbers`); its `numeric` says money in yuan, two
    // decimals.
    const field = {
      kind: 'number',
      numeric: { type: 'money', scale: 2, currency: 'CNY' },
    } as const;
    const format = numberFormatOf(field);
    const display = { locale: 'en' };
    const written = new Intl.NumberFormat('en', format).format(1234567.5);
    // The same amount as a number reads in its currency…
    expect(
      cellText(1234567.5, { ...field, numberFormat: format }, WORDS, display),
    ).toBe(written);
    // …and so does its exact text.
    expect(
      cellText(
        '1234567.50',
        { ...field, numberFormat: format },
        WORDS,
        display,
      ),
    ).toBe(written);
    expect(
      cellValue(
        '1234567.50',
        { ...field, numberFormat: format },
        WORDS,
        display,
        'table',
      ),
    ).toBe(written);
  });
});

describe('r1 a file of numbers', () => {
  it('r1 a bigint with no declared format is written to a file as the number it is', () => {
    // `csvCellText`: "a number no format was declared for, which is written
    // as the number it is" — `534,897` in a CSV is a string no column sums.
    expect(
      csvCellText(9_007_199_254_740_993n, { kind: 'number' }, WORDS, {
        locale: 'en',
      }),
    ).toBe('9007199254740993');
  });
});

describe('r1 a metric card compared with another metric', () => {
  const sum = (alias: string, field: string) =>
    ({
      alias,
      type: AggregationMetricType.NUMERIC,
      function: AggregationFunction.SUM,
      expression: { type: AggregationExpressionType.FIELD, field },
    }) as const;

  it('r1 a percent comparison against a negative number measures by its size, as a period change does', () => {
    // A loss of 50 against a planned loss of 100 is 50 better than the
    // plan: +50% of it, the reading `changeOf` and `bucketChange` give
    // (`delta / |was|`), not -50%, which colours a beat plan as a miss.
    const config = analysisConfig({
      groups: [],
      metrics: [sum('profit', 'amount'), sum('plan', 'amount')],
      layout: 'chart',
      chart: {
        type: 'metric',
        metric: {
          metric: 'profit',
          compare: { metric: 'plan', mode: 'percent' },
        },
      },
    });
    const card = shapeChart(config, [
      { profit: -50, plan: -100 },
    ]) as MetricCardData;
    expect(card.compare?.delta).toBeCloseTo(0.5);
  });
});

describe('r1 a calendar part in a language whose calendar is not Gregorian', () => {
  const gregorian = (
    date: number,
    options: Intl.DateTimeFormatOptions,
  ): string =>
    new Intl.DateTimeFormat('fa-IR', {
      ...options,
      timeZone: 'UTC',
      calendar: 'gregory',
    }).format(date);

  it('r1 the day of the month 3 reads as the 3rd, in any language', () => {
    expect(datePartValue(3, AggregationDatePart.DAY_OF_MONTH, 'fa-IR')).toBe(
      gregorian(Date.UTC(2024, 0, 3), { day: 'numeric' }),
    );
  });

  it('r1 the month of the year 9 reads as September, in any language', () => {
    expect(datePartValue(9, AggregationDatePart.MONTH_OF_YEAR, 'fa-IR')).toBe(
      gregorian(Date.UTC(2024, 8, 1), { month: 'short' }),
    );
  });
});
