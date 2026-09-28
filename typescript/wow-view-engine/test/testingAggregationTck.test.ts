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
 * The aggregation cases of Wow's TCK, answered by the in-memory source:
 * `SnapshotQueryBackendSpec` in `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/`,
 * which every backend (MongoDB, Elasticsearch) runs. Same data, same
 * queries, same expected answers; each case keeps the TCK test's name. The
 * lines' times are epoch milliseconds here, where MongoDB stores dates.
 */

import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DateDiffUnit,
  FilterOperator,
  SortDirection,
  type AggregationExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../src/index.js';
import { memorySource } from '../src/testing/index.js';
import {
  ADD,
  DIVIDE,
  HOUR,
  MULTIPLY,
  STATES,
  SUBTRACT,
  any,
  anyNullState,
  ask,
  avg,
  binary,
  constant,
  count,
  dateHistogram,
  datePart,
  distinct,
  edge,
  eq,
  f,
  field,
  gt,
  gte,
  histogram,
  isIn,
  line,
  lines,
  lte,
  max,
  min,
  ms,
  percentile,
  pick,
  snapshot,
  stddev,
  sum,
  terms,
  variance,
  withinRankBounds,
  gate,
} from './fixtures/wowTck.js';

describe('the aggregation TCK (SnapshotQueryBackendSpec)', () => {
  it('aggregation should summarize standard root fields with every metric', async () => {
    expect(
      await ask({
        filter: f({
          op: FilterOperator.AGGREGATE_IDS,
          values: ['aggregation-a', 'aggregation-b'],
        }),
        metrics: [
          count('count'),
          sum('version', 'total'),
          avg('version', 'average'),
          min('version', 'minimum'),
          max('version', 'maximum'),
        ],
      }),
    ).toEqual([{ count: 2, total: 3, average: 1.5, minimum: 1, maximum: 2 }]);
  });

  it('aggregation should calculate arithmetic metrics in the innermost element scope', async () => {
    const net = binary(
      SUBTRACT,
      binary(MULTIPLY, field('amount'), field('quantity')),
      constant(10),
    );
    expect(
      await ask({
        elements: lines(),
        metrics: [
          count('count'),
          sum(net, 'total'),
          avg(net, 'average'),
          min(net, 'minimum'),
          max(net, 'maximum'),
        ],
      }),
    ).toEqual([
      { count: 6, total: 410, average: 82, minimum: 0, maximum: 240 },
    ]);
  });

  it('aggregation should ignore invalid arithmetic values', async () => {
    expect(
      await ask({
        elements: lines(undefined, isIn('productId', ['alpha', 'beta'])),
        metrics: [
          sum(
            binary(
              DIVIDE,
              field('amount'),
              binary(SUBTRACT, field('quantity'), constant(2)),
            ),
            'safeDivision',
          ),
          sum(
            binary(
              DIVIDE,
              field('quantity'),
              binary(SUBTRACT, field('quantity'), field('quantity')),
            ),
            'zeroDivision',
          ),
          sum(binary(MULTIPLY, field('missing'), constant(1)), 'missing'),
          sum(
            binary(MULTIPLY, constant(Number.MAX_VALUE), constant(2)),
            'overflow',
          ),
        ],
      }),
    ).toEqual([
      { safeDivision: 5, zeroDivision: null, missing: null, overflow: null },
    ]);
  });

  it('aggregation should accept singleton and ignore multi-valued numeric arrays', async () => {
    expect(
      await ask({
        elements: lines(),
        metrics: [
          sum(binary(MULTIPLY, field('samples'), constant(1)), 'total'),
        ],
      }),
    ).toEqual([{ total: 7 }]);
  });

  it('aggregation should apply two element filters and every group type', async () => {
    expect(
      await ask({
        elements: lines(eq('status', 'PAID'), gte('quantity', 2)),
        groupBy: [
          terms('productId', 'product'),
          histogram('quantity', 2, 'quantityBucket'),
          dateHistogram(AggregationDateUnit.DAY, 'day'),
        ],
        metrics: [count('count')],
      }),
    ).toEqual([
      { product: 'alpha', quantityBucket: 4, day: 1_767_398_400_000, count: 1 },
      { product: 'beta', quantityBucket: 2, day: 1_767_312_000_000, count: 2 },
      { product: 'delta', quantityBucket: 4, day: 1_769_990_400_000, count: 1 },
    ]);
  });

  it('aggregation should start UTC weeks on Monday', async () => {
    expect(
      await ask({
        elements: lines(undefined, isIn('productId', ['gamma', 'delta'])),
        groupBy: [dateHistogram(AggregationDateUnit.WEEK, 'week')],
        metrics: [count('count')],
      }),
    ).toEqual([
      { week: 1_769_385_600_000, count: 1 },
      { week: 1_769_990_400_000, count: 1 },
    ]);
  });

  describe('date parts', () => {
    const parts = async (
      part: AggregationDatePart,
      timeZone = 'UTC',
      dense = false,
    ) =>
      pick(
        await ask({
          elements: lines(),
          groupBy: [datePart(part, 'part', { timeZone, dense })],
          metrics: [count('count')],
        }),
        'part',
        'count',
      );

    it('aggregation date part should group ISO weekdays in the time zone', async () => {
      expect(await parts(AggregationDatePart.DAY_OF_WEEK)).toEqual([
        [1, 1],
        [4, 1],
        [5, 2],
        [6, 1],
        [7, 1],
      ]);
      expect(
        await parts(AggregationDatePart.DAY_OF_WEEK, 'Asia/Shanghai'),
      ).toEqual([
        [1, 1],
        [4, 1],
        [5, 1],
        [6, 2],
        [7, 1],
      ]);
    });

    it('aggregation date part should group wall hours, days of month and months', async () => {
      expect(await parts(AggregationDatePart.HOUR_OF_DAY)).toEqual([
        [10, 5],
        [18, 1],
      ]);
      expect(
        await parts(AggregationDatePart.HOUR_OF_DAY, 'Asia/Shanghai'),
      ).toEqual([
        [2, 1],
        [18, 5],
      ]);
      expect(await parts(AggregationDatePart.DAY_OF_MONTH)).toEqual([
        [1, 2],
        [2, 3],
        [3, 1],
      ]);
      expect(await parts(AggregationDatePart.MONTH_OF_YEAR)).toEqual([
        [1, 4],
        [2, 2],
      ]);
    });

    it('aggregation dense date part should fill its whole fixed domain', async () => {
      const hours = await parts(AggregationDatePart.HOUR_OF_DAY, 'UTC', true);
      expect(hours.map(([hour]) => hour)).toEqual(
        Array.from({ length: 24 }, (_, hour) => hour),
      );
      expect(hours.filter(([, n]) => (n as number) > 0)).toEqual([
        [10, 5],
        [18, 1],
      ]);
      expect(await parts(AggregationDatePart.DAY_OF_WEEK, 'UTC', true)).toEqual(
        [
          [1, 1],
          [2, 0],
          [3, 0],
          [4, 1],
          [5, 2],
          [6, 1],
          [7, 1],
        ],
      );
    });

    it('aggregation date parts should combine as weekday by hour', async () => {
      expect(
        pick(
          await ask({
            elements: lines(),
            groupBy: [
              datePart(AggregationDatePart.DAY_OF_WEEK, 'weekday'),
              datePart(AggregationDatePart.HOUR_OF_DAY, 'hour'),
            ],
            metrics: [count('count')],
          }),
          'weekday',
          'hour',
          'count',
        ),
      ).toEqual([
        [1, 10, 1],
        [4, 10, 1],
        [5, 10, 1],
        [5, 18, 1],
        [6, 10, 1],
        [7, 10, 1],
      ]);
    });
  });

  it('aggregation FIRST and LAST should read the earliest and latest line by orderBy', async () => {
    expect(
      pick(
        await ask({
          elements: lines(),
          groupBy: [terms('productId', 'product')],
          metrics: [
            edge(AggregationMetricType.FIRST, 'amount', 'open'),
            edge(AggregationMetricType.LAST, 'amount', 'close'),
            edge(AggregationMetricType.LAST, 'quantity', 'lastQuantity'),
          ],
        }),
        'product',
        'open',
        'close',
        'lastQuantity',
      ),
    ).toEqual([
      ['alpha', 10, 30, 4],
      ['beta', 20, 20, 2],
      ['delta', 50, 50, 5],
      ['gamma', null, null, 3],
    ]);
  });

  it('aggregation FIRST and LAST should honor their metric filter', async () => {
    expect(
      await ask({
        elements: lines(),
        metrics: [
          edge(
            AggregationMetricType.FIRST,
            'productId',
            'firstBig',
            gte('quantity', 3),
          ),
          edge(
            AggregationMetricType.LAST,
            'productId',
            'lastSmall',
            lte('quantity', 2),
          ),
        ],
      }),
    ).toEqual([{ firstBig: 'alpha', lastSmall: 'beta' }]);
  });

  describe('the time between two moments', () => {
    const start = 1_767_225_600_000;
    const dateDiffs: RecordData[] = [
      ['date-diff-a', 2 * HOUR],
      ['date-diff-b', 30 * HOUR],
      ['date-diff-c', -HOUR],
    ].map(([aggregateId, offset]) => ({
      aggregateId,
      firstEventTime: start,
      eventTime: start + (offset as number),
      state: { createdAt: start },
    }));
    const hours: AggregationExpression = {
      type: AggregationExpressionType.DATE_DIFF,
      from: 'firstEventTime',
      to: 'eventTime',
      unit: DateDiffUnit.HOUR,
    };

    it('aggregation DATE_DIFF should measure signed elapsed time in metrics and groups', async () => {
      expect(
        pick(
          await ask(
            {
              groupBy: [
                {
                  type: AggregationGroupType.HISTOGRAM,
                  alias: 'day',
                  interval: 24,
                  expression: hours,
                },
              ],
              metrics: [count('count'), max(hours, 'hours')],
            },
            dateDiffs,
          ),
          'day',
          'count',
          'hours',
        ),
      ).toEqual([
        [-24, 1, -1],
        [0, 1, 2],
        [24, 1, 30],
      ]);
      const [row] = await ask(
        {
          metrics: [
            sum(hours, 'total'),
            count(
              'late',
              f({
                op: FilterOperator.EXPRESSION,
                expression: hours,
                comparison: ComparisonOperator.GT,
                value: 24,
              }),
            ),
            avg({ ...hours, unit: DateDiffUnit.DAY }, 'days'),
          ],
        },
        dateDiffs,
      );
      expect(row.total).toBe(31);
      expect(row.late).toBe(1);
      expect(row.days).toBeCloseTo(31 / 24 / 3, 9);
    });

    it('EXPRESSION filter should compare the computed value and skip records without one', async () => {
      const documents = structuredClone(dateDiffs);
      (documents[0].state as RecordData).createdAt = null;
      const matching = async (
        expression: AggregationExpression,
        comparison: ComparisonOperator,
        value: number,
      ) =>
        (
          await memorySource(documents).paged({
            filter: f({
              op: FilterOperator.EXPRESSION,
              expression,
              comparison,
              value,
            }),
          })
        ).list
          .map(({ aggregateId }) => aggregateId)
          .sort();
      expect(await matching(hours, ComparisonOperator.GT, 24)).toEqual([
        'date-diff-b',
      ]);
      expect(await matching(hours, ComparisonOperator.LT, 0)).toEqual([
        'date-diff-c',
      ]);
      expect(await matching(hours, ComparisonOperator.NE, 2)).toEqual([
        'date-diff-b',
        'date-diff-c',
      ]);
      // state.createdAt was removed from date-diff-a: no value, so no match
      // whatever the comparison.
      expect(
        await matching(
          {
            type: AggregationExpressionType.DATE_DIFF,
            from: 'state.createdAt',
            to: 'eventTime',
            unit: DateDiffUnit.SECOND,
          },
          ComparisonOperator.NE,
          1e12,
        ),
      ).toEqual(['date-diff-b', 'date-diff-c']);
    });
  });

  it('aggregation should support second date histograms', async () => {
    expect(
      await ask({
        elements: lines(undefined, eq('productId', 'beta')),
        groupBy: [dateHistogram(AggregationDateUnit.SECOND, 'second')],
        metrics: [count('count')],
      }),
    ).toEqual([
      { second: ms('2026-01-02T10:00:00Z'), count: 1 },
      { second: ms('2026-01-02T18:00:00Z'), count: 1 },
    ]);
  });

  it('aggregation should return backend neutral decimal Terms keys', async () => {
    expect(
      await ask(
        {
          groupBy: [terms('state.decimalValue', 'decimal')],
          metrics: [count('count')],
        },
        [
          { aggregateId: 'decimal-a', state: { decimalValue: 1.25 } },
          { aggregateId: 'decimal-b', state: { decimalValue: 2.5 } },
        ],
      ),
    ).toEqual([
      { decimal: 1.25, count: 1 },
      { decimal: 2.5, count: 1 },
    ]);
  });

  it('aggregation should return one empty summary row', async () => {
    expect(
      await ask({
        filter: f({ op: FilterOperator.AGGREGATE_ID, value: 'missing' }),
        metrics: [
          any('state.data', 'anyData'),
          count('count'),
          sum('version', 'total'),
        ],
      }),
    ).toEqual([{ anyData: null, count: 0, total: null }]);
  });

  it('aggregation should select any non-null value without splitting the group', async () => {
    const rows = await ask(
      {
        elements: lines(undefined, eq('productId', 'alpha')),
        groupBy: [terms('productId', 'productId')],
        metrics: [any('productName', 'productName'), count('count')],
      },
      [...STATES, anyNullState],
    );
    expect(rows).toHaveLength(1);
    expect(['Alpha', 'Alpha 2026']).toContain(rows[0].productName);
    expect(rows[0].count).toBe(3);
  });

  it('aggregation any should return null when every value is absent', async () => {
    expect(
      await ask({
        elements: lines(undefined, eq('productId', 'gamma')),
        metrics: [any('productName', 'productName'), count('count')],
      }),
    ).toEqual([{ productName: null, count: 1 }]);
  });

  it('aggregation should return null when no numeric value contributes', async () => {
    expect(
      await ask({
        elements: lines(eq('status', 'CANCELLED'), eq('productId', 'gamma')),
        metrics: [
          count('count'),
          sum('amount', 'total'),
          avg('amount', 'average'),
          min('amount', 'minimum'),
          max('amount', 'maximum'),
        ],
      }),
    ).toEqual([
      { count: 1, total: null, average: null, minimum: null, maximum: null },
    ]);
  });

  it('aggregation should append group aliases for stable sorting', async () => {
    expect(
      pick(
        await ask({
          elements: lines(eq('status', 'PAID')),
          groupBy: [
            terms('productId', 'product'),
            histogram('quantity', 2, 'quantityBucket'),
          ],
          metrics: [count('count')],
          sort: [{ field: 'product', direction: SortDirection.ASC }],
        }),
        'product',
        'quantityBucket',
        'count',
      ),
    ).toEqual([
      ['alpha', 0, 1],
      ['alpha', 4, 1],
      ['beta', 2, 2],
      ['delta', 4, 1],
    ]);
  });

  it('aggregation should select metric Top-N across nested discounts', async () => {
    expect(
      await ask({
        elements: [
          ...lines(eq('status', 'PAID'), gte('quantity', 2)),
          { path: 'discounts', filter: gate(gt('amount', 0)) },
        ],
        groupBy: [terms('type', 'type')],
        metrics: [sum('amount', 'total')],
        sort: [{ field: 'total', direction: SortDirection.DESC }],
        limit: 1,
      }),
    ).toEqual([{ type: 'PROMO', total: 8 }]);
  });

  it('aggregation should count distinct values excluding null', async () => {
    expect(
      await ask({
        elements: lines(),
        metrics: [
          distinct('productId', 'products'),
          distinct('amount', 'amounts'),
        ],
      }),
    ).toEqual([{ products: 4, amounts: 4 }]);
  });

  it('aggregation should count distinct array elements', async () => {
    expect(
      await ask({
        elements: lines(),
        metrics: [distinct('samples', 'samples')],
      }),
    ).toEqual([{ samples: 3 }]);
  });

  it('aggregation should calculate exact population stddev and variance', async () => {
    const one = (id: string, productId: string, amount: number) =>
      snapshot(id, 1, [
        {
          status: 'PAID',
          lines: [line(productId, 1, amount, '2026-01-01T10:00:00Z', [])],
        },
      ]);
    expect(
      await ask(
        {
          elements: lines(eq('status', 'PAID')),
          metrics: [
            count('count'),
            stddev('amount', 'stddev'),
            variance('amount', 'variance'),
          ],
        },
        [one('stddev-a', 's1', 10), one('stddev-b', 's2', 20)],
      ),
    ).toEqual([{ count: 2, stddev: 5, variance: 25 }]);
  });

  it('aggregation should calculate percentile metrics within rank bounds', async () => {
    const [row] = await ask({
      elements: lines(eq('status', 'PAID')),
      metrics: [
        percentile('amount', 50, 'median'),
        percentile('amount', 95, 'p95'),
      ],
    });
    withinRankBounds(row.median, [10, 20, 20, 30, 50], 50);
    withinRankBounds(row.p95, [10, 20, 20, 30, 50], 95);
  });

  it('aggregation should return null or zero when no value contributes', async () => {
    expect(
      await ask({
        elements: lines(eq('status', 'CANCELLED'), eq('productId', 'gamma')),
        metrics: [
          count('count'),
          stddev('amount', 'stddev'),
          variance('amount', 'variance'),
          percentile('amount', 50, 'median'),
          distinct('amount', 'amounts'),
        ],
      }),
    ).toEqual([
      { count: 1, stddev: null, variance: null, median: null, amounts: 0 },
    ]);
  });

  it('aggregation should return zero distinct count in an empty summary', async () => {
    expect(
      await ask({
        filter: f({ op: FilterOperator.AGGREGATE_ID, value: 'missing' }),
        metrics: [
          count('count'),
          distinct('version', 'versions'),
          percentile('version', 50, 'median'),
        ],
      }),
    ).toEqual([{ count: 0, versions: 0, median: null }]);
  });

  it('aggregation should sort groups by distinct count', async () => {
    expect(
      pick(
        await ask({
          elements: lines(eq('status', 'PAID')),
          groupBy: [terms('productId', 'product')],
          metrics: [
            distinct('amount', 'amounts'),
            stddev('amount', 'amtStddev'),
          ],
          sort: [{ field: 'amounts', direction: SortDirection.DESC }],
        }),
        'product',
        'amounts',
        'amtStddev',
      ),
    ).toEqual([
      ['alpha', 2, 10],
      ['beta', 1, 0],
      ['delta', 1, 0],
    ]);
  });

  it('aggregation should apply arithmetic expressions to new metrics', async () => {
    const [row] = await ask({
      elements: lines(eq('status', 'PAID')),
      metrics: [
        distinct(binary(ADD, field('amount'), constant(0)), 'amounts'),
        percentile(
          binary(MULTIPLY, field('amount'), constant(1)),
          50,
          'medianAmount',
        ),
      ],
    });
    expect(row.amounts).toBe(4);
    withinRankBounds(row.medianAmount, [10, 20, 20, 30, 50], 50);
  });
});
