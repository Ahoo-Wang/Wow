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
 * The builders of an aggregation query's groups: terms, histograms, date
 * histograms and date parts, their options and scopes.
 */

import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationGroupScope,
  DateDiffUnit,
  aggregation,
} from '../../../src/dsl.js';

describe('AggregationQuery', () => {
  it('supports missing terms and dense date groups explicitly', () => {
    expect(
      aggregation.terms('productName', 'name', { missingKey: 'Unknown' }),
    ).toEqual({
      type: 'terms',
      field: 'productName',
      alias: 'name',
      missingKey: 'Unknown',
    });
    expect(
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        dense: true,
      }),
    ).toEqual({
      type: 'date-histogram',
      field: 'createdAt',
      unit: 'day',
      alias: 'day',
            dense: true,
    });
  });

  it('groups by a calendar part, dense only on request', () => {
    expect(
      aggregation.datePart('createdAt', 'weekday', {
        part: AggregationDatePart.DAY_OF_WEEK,
      }),
    ).toEqual({
      type: 'date-part',
      field: 'createdAt',
      part: 'day-of-week',
      alias: 'weekday',
          });
    expect(
      aggregation.datePart('createdAt', 'hour', {
        part: AggregationDatePart.HOUR_OF_DAY,
        timeZone: 'Asia/Shanghai',
        dense: true,
      }),
    ).toEqual({
      type: 'date-part',
      field: 'createdAt',
      part: 'hour-of-day',
      alias: 'hour',
      timeZone: 'Asia/Shanghai',
      dense: true,
    });
  });

  it('rejects an invalid date part, a blank zone and a non-boolean dense', () => {
    expect(() =>
      aggregation.datePart('createdAt', 'd', { part: 'week' as never }),
    ).toThrow('date part is invalid.');
    expect(() =>
      aggregation.datePart('createdAt', 'd', {
        part: AggregationDatePart.DAY_OF_MONTH,
        timeZone: ' ',
      }),
    ).toThrow('date part timeZone cannot be blank.');
    expect(() =>
      aggregation.datePart('createdAt', 'd', {
        part: AggregationDatePart.MONTH_OF_YEAR,
        dense: 1 as never,
      }),
    ).toThrow('date part dense must be boolean.');
  });

  it('groups expanded elements by a root field with a ROOT scope', () => {
    const days = aggregation.dateDiff('a', 'b', DateDiffUnit.DAY);
    expect(
      aggregation.terms('state.id', 'aggregate', {
        scope: AggregationGroupScope.ROOT,
      }),
    ).toEqual({
      type: 'terms',
      field: 'state.id',
      scope: 'root',
      alias: 'aggregate',
    });
    expect(
      aggregation.histogram('amount', 'band', {
        interval: 10,
        scope: AggregationGroupScope.ELEMENT,
      }).scope,
    ).toBe('element');
    expect(
      aggregation.dateHistogram('createTime', 'day', {
        unit: AggregationDateUnit.DAY,
        scope: AggregationGroupScope.ROOT,
      }).scope,
    ).toBe('root');
    expect(
      aggregation.datePart('createTime', 'hour', {
        part: AggregationDatePart.HOUR_OF_DAY,
        scope: AggregationGroupScope.ROOT,
      }).scope,
    ).toBe('root');
    expect(aggregation.terms('status', 'status')).not.toHaveProperty('scope');
    expect(() =>
      aggregation.histogram(days, 'band', {
        interval: 7,
        scope: AggregationGroupScope.ROOT,
      }),
    ).toThrow('HISTOGRAM with a ROOT scope requires a field input.');
    expect(() =>
      aggregation.terms('status', 'status', { scope: 'OUTER' as never }),
    ).toThrow('TERMS group scope is invalid.');
    // Hand-built groups are held to the same rule by aggregation.query().
    expect(() =>
      aggregation.query({
        groupBy: [
          {
            type: 'terms',
            expression: days,
            alias: 'g',
            scope: 'root',
          } as never,
        ],
        metrics: [aggregation.count('rows')],
      }),
    ).toThrow('TERMS with a ROOT scope requires a field input.');
  });

  it('rejects blank missing keys and non-boolean dense options', () => {
    expect(() =>
      aggregation.terms('productName', 'name', { missingKey: '  ' }),
    ).toThrow(TypeError);
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        dense: 'true' as never,
      }),
    ).toThrow(TypeError);
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: 'FORTNIGHT' as never,
      }),
    ).toThrow('date histogram unit is invalid.');
    expect(() =>
      aggregation.dateHistogram('createdAt', 'day', {
        unit: AggregationDateUnit.DAY,
        timeZone: ' ',
      }),
    ).toThrow('date histogram timeZone cannot be blank.');
  });

  it('builds aggregation groups', () => {
    expect([
      aggregation.terms('productId', 'product'),
      aggregation.histogram('amount', 'amountBand', { interval: 10 }),
      aggregation.dateHistogram('createdAt', 'month', {
        unit: AggregationDateUnit.MONTH,
      }),
    ]).toStrictEqual([
      { type: 'terms', field: 'productId', alias: 'product' },
      {
        type: 'histogram',
        field: 'amount',
        interval: 10,
        alias: 'amountBand',
      },
      {
        type: 'date-histogram',
        field: 'createdAt',
        unit: 'month',
        alias: 'month',
              },
    ]);
  });

  it.each([
    [
      'zero histogram interval',
      () => aggregation.histogram('amount', 'band', { interval: 0 }),
    ],
    ['multi-segment alias', () => aggregation.terms('status', 'group.status')],
    [
      'blank time zone',
      () =>
        aggregation.dateHistogram('createdAt', 'day', {
          unit: AggregationDateUnit.DAY,
          timeZone: ' ',
        }),
    ],
    [
      'missing date histogram unit',
      () =>
        Reflect.apply(aggregation.dateHistogram, null, [
          'createdAt',
          { alias: 'day' },
        ]),
    ],
    [
      'invalid date histogram unit',
      () =>
        Reflect.apply(aggregation.dateHistogram, null, [
          'createdAt',
          { unit: 'INVALID', alias: 'day' },
        ]),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow(TypeError);
  });
});
