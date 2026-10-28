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
  AggregationDateUnit,
  ComparisonOperator,
  FilterOperator,
  HavingExpressionType,
  SortDirection,
  StringComparison,
  type AggregationMetric,
  type AggregationQuery,
  type HavingExpression,
} from '@ahoo-wang/wow-client/dsl';
import {
  ADD,
  DIVIDE,
  MULTIPLY,
  STATES,
  SUBTRACT,
  any,
  anyNullState,
  ask,
  condition,
  count,
  dateHistogram,
  derived,
  distinct,
  eq,
  f,
  gt,
  gte,
  line,
  lines,
  lit,
  ms,
  op,
  percentile,
  pick,
  ref,
  snapshot,
  sum,
  terms,
} from './fixtures/wowTck.js';

describe('the aggregation TCK (SnapshotQueryBackendSpec), part two', () => {
  describe('metric filters', () => {
    it('aggregation should count a funnel of metric filtered counts', async () => {
      expect(
        await ask({
          elements: [{ path: 'state.orders' }],
          metrics: [count('all'), count('paid', eq('status', 'PAID'))],
        }),
      ).toEqual([{ all: 3, paid: 2 }]);
    });

    it('aggregation should filter numeric and distinct metrics by record filters', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            count('big', gte('quantity', 2)),
            sum('amount', 'bigAmount', gte('quantity', 2)),
            distinct('productId', 'bigProducts', gte('quantity', 2)),
          ],
        }),
      ).toEqual([{ big: 5, bigAmount: 120, bigProducts: 4 }]);
    });

    it('aggregation should return zero or null when a metric filter matches nothing', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            count('none', gt('quantity', 100)),
            sum('amount', 'noneAmount', gt('quantity', 100)),
            distinct('productId', 'noneProducts', gt('quantity', 100)),
            percentile('amount', 95, 'noneP95', gt('quantity', 100)),
          ],
        }),
      ).toEqual([
        { none: 0, noneAmount: null, noneProducts: 0, noneP95: null },
      ]);
    });

    it('aggregation should combine metric filters with element filters', async () => {
      expect(
        await ask({
          elements: lines(eq('status', 'PAID')),
          metrics: [count('all'), count('big', gte('quantity', 2))],
        }),
      ).toEqual([{ all: 5, big: 4 }]);
    });

    it('aggregation should sort groups by a filtered metric', async () => {
      expect(
        (
          await ask({
            elements: lines(),
            groupBy: [terms('productId', 'product')],
            metrics: [sum('amount', 'bigAmount', gte('quantity', 2))],
            sort: [{ field: 'bigAmount', direction: SortDirection.DESC }],
          })
        ).map(row => row.product),
      ).toEqual(['delta', 'beta', 'alpha', 'gamma']);
    });

    it('aggregation should scope metric filters to the innermost element', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [count('byName', eq('productName', 'Alpha'))],
        }),
      ).toEqual([{ byName: 1 }]);
    });

    it('aggregation should count records matched by literal match metric filters', async () => {
      const text = (op: FilterOperator, value: string, ignoreCase = false) =>
        f({
          op,
          field: 'productName',
          value,
          ...(ignoreCase
            ? { stringComparison: StringComparison.CASE_INSENSITIVE }
            : {}),
        });
      expect(
        await ask(
          {
            elements: lines(),
            metrics: [
              count('contains', text(FilterOperator.CONTAINS, 'Alpha')),
              count('startsWith', text(FilterOperator.STARTS_WITH, 'Alpha')),
              count('endsWith', text(FilterOperator.ENDS_WITH, '2026')),
              count(
                'containsIgnoreCase',
                text(FilterOperator.CONTAINS, 'alpha', true),
              ),
            ],
          },
          [...STATES, anyNullState],
        ),
      ).toEqual([
        { contains: 2, startsWith: 2, endsWith: 1, containsIgnoreCase: 2 },
      ]);
    });

    it('aggregation metric filters should preserve null versus missing semantics', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            count('all'),
            count(
              'withAmount',
              f({ op: FilterOperator.IS_NOT_NULL, field: 'amount' }),
            ),
            count(
              'nullishAmount',
              f({ op: FilterOperator.IS_NULL, field: 'amount' }),
            ),
            count(
              'hasMissing',
              f({ op: FilterOperator.EXISTS, field: 'missing' }),
            ),
            count(
              'missingOrNull',
              f({ op: FilterOperator.IS_NULL, field: 'missing' }),
            ),
          ],
        }),
      ).toEqual([
        {
          all: 6,
          withAmount: 5,
          nullishAmount: 1,
          hasMissing: 0,
          missingOrNull: 6,
        },
      ]);
    });
  });

  describe('derived metrics', () => {
    const big = [
      count('big', gte('quantity', 2)),
      sum('amount', 'bigAmount', gte('quantity', 2)),
    ];

    it('aggregation should derive ratios from filtered metrics', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            ...big,
            derived('aov', op(DIVIDE, ref('bigAmount'), ref('big'))),
          ],
        }),
      ).toEqual([{ big: 5, bigAmount: 120, aov: 24 }]);
    });

    it('aggregation should derive ratios between sums and constants', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            sum('amount', 'totalAmount'),
            sum('amount', 'bigAmount', gte('quantity', 2)),
            derived(
              'margin',
              op(
                DIVIDE,
                op(SUBTRACT, ref('totalAmount'), ref('bigAmount')),
                lit(10),
              ),
            ),
            derived('target', lit(120)),
            derived('attainment', op(DIVIDE, ref('bigAmount'), ref('target'))),
          ],
        }),
      ).toEqual([
        {
          totalAmount: 130,
          bigAmount: 120,
          margin: 1,
          target: 120,
          attainment: 1,
        },
      ]);
    });

    it('aggregation should derive null on division by zero', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            count('none', gt('quantity', 100)),
            sum('amount', 'bigAmount', gte('quantity', 2)),
            derived('ratio', op(DIVIDE, ref('bigAmount'), ref('none'))),
          ],
        }),
      ).toEqual([{ none: 0, bigAmount: 120, ratio: null }]);
    });

    it('aggregation should evaluate derived metrics over an empty ungrouped summary', async () => {
      expect(
        await ask({
          elements: lines(undefined, gt('quantity', 10_000)),
          metrics: [
            count('c'),
            derived('one', lit(1)),
            derived('next', op(ADD, ref('c'), lit(1))),
          ],
        }),
      ).toEqual([{ c: 0, one: 1, next: 1 }]);
    });

    it('aggregation should propagate null from empty metrics into derived', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            sum('amount', 'noneAmount', gt('quantity', 100)),
            derived('scaled', op(MULTIPLY, ref('noneAmount'), lit(2))),
          ],
        }),
      ).toEqual([{ noneAmount: null, scaled: null }]);
    });

    it('aggregation should derive from previously derived metrics', async () => {
      expect(
        await ask({
          elements: lines(),
          metrics: [
            ...big,
            derived('aov', op(DIVIDE, ref('bigAmount'), ref('big'))),
            derived('aovDoubled', op(MULTIPLY, ref('aov'), lit(2))),
          ],
        }),
      ).toEqual([{ big: 5, bigAmount: 120, aov: 24, aovDoubled: 48 }]);
    });
  });

  describe('having', () => {
    const products = async (
      having: HavingExpression,
      rest: Partial<AggregationQuery> = {},
      metrics: AggregationMetric[] = [count('lines'), sum('amount', 'total')],
    ) =>
      (
        await ask({
          elements: lines(),
          groupBy: [terms('productId', 'product')],
          metrics,
          having,
          ...rest,
        })
      ).map(row => row.product);
    const { GT, GTE, EQ } = ComparisonOperator;

    it('aggregation having should filter groups by count thresholds', async () => {
      expect(await products(condition('lines', GTE, 2))).toEqual([
        'alpha',
        'beta',
      ]);
    });

    it('aggregation having should filter groups by derived thresholds', async () => {
      expect(
        await products(condition('share', GTE, 0.8), {}, [
          sum('amount', 'total'),
          derived('share', op(DIVIDE, ref('total'), lit(50))),
        ]),
      ).toEqual(['alpha', 'beta', 'delta']);
    });

    it('aggregation having should treat null as failing comparisons', async () => {
      expect(
        await products({
          type: HavingExpressionType.IS_NULL,
          metric: 'total',
          negated: true,
        }),
      ).toEqual(['alpha', 'beta', 'delta']);
    });

    it('aggregation having should apply limit after filtering', async () => {
      expect(
        await products(condition('total', GTE, 40), {
          sort: [{ field: 'total', direction: SortDirection.DESC }],
          limit: 2,
        }),
      ).toEqual(['delta', 'alpha']);
    });

    it('aggregation having should apply limit after filtering with group alias sort', async () => {
      expect(
        await products(condition('total', GTE, 40), {
          sort: [{ field: 'product', direction: SortDirection.ASC }],
          limit: 2,
        }),
      ).toEqual(['alpha', 'beta']);
    });

    it('aggregation having matching nothing should return an empty flux', async () => {
      expect(await products(condition('lines', GT, 100))).toEqual([]);
    });

    it('aggregation having should compose with and and or', async () => {
      expect(
        await products({
          type: HavingExpressionType.AND,
          operands: [
            condition('total', GTE, 40),
            {
              type: HavingExpressionType.OR,
              operands: [
                condition('lines', GT, 1),
                condition('total', GTE, 50),
              ],
            },
          ],
        }),
      ).toEqual(['alpha', 'beta', 'delta']);
    });

    it('aggregation having should support between and in', async () => {
      expect(
        await products({
          type: HavingExpressionType.OR,
          operands: [
            {
              type: HavingExpressionType.BETWEEN,
              metric: 'total',
              lower: 40,
              upper: 45,
            },
            { type: HavingExpressionType.IN, metric: 'total', values: [50] },
          ],
        }),
      ).toEqual(['alpha', 'beta', 'delta']);
      expect(await products(condition('total', EQ, 50))).toEqual(['delta']);
    });
  });

  describe('dense', () => {
    const days = (rest: Partial<AggregationQuery> = {}, documents = STATES) =>
      ask(
        {
          elements: lines(),
          groupBy: [
            dateHistogram(AggregationDateUnit.DAY, 'day', { dense: true }),
          ],
          metrics: [count('count')],
          ...rest,
        },
        documents,
      );

    it('aggregation dense day histogram should fill interior gaps with empty metric semantics', async () => {
      const rows = await days({
        metrics: [
          count('count'),
          sum('amount', 'total'),
          derived('aov', op(DIVIDE, ref('total'), ref('count'))),
        ],
      });
      expect(rows).toHaveLength(33);
      expect(rows[0].day).toBe(ms('2026-01-01T00:00:00Z'));
      expect(rows[rows.length - 1].day).toBe(ms('2026-02-02T00:00:00Z'));
      expect(rows.find(row => row.day === ms('2026-01-04T00:00:00Z'))).toEqual({
        day: ms('2026-01-04T00:00:00Z'),
        count: 0,
        total: null,
        aov: null,
      });
    });

    it('aggregation dense should not fill a single bucket', async () => {
      expect(await days({}, [anyNullState])).toHaveLength(1);
    });

    it('aggregation dense should let having drop filled rows', async () => {
      expect(
        await days({ having: condition('count', ComparisonOperator.GTE, 1) }),
      ).toHaveLength(5);
    });

    it('aggregation dense should let having match exactly the filled rows', async () => {
      const rows = await days({
        having: condition('count', ComparisonOperator.EQ, 0),
      });
      expect(rows).toHaveLength(28);
      expect(rows[0].day).toBe(ms('2026-01-04T00:00:00Z'));
    });

    it('aggregation dense should place filled rows in descending order', async () => {
      const rows = await days({
        sort: [{ field: 'day', direction: SortDirection.DESC }],
      });
      expect(rows.slice(0, 3).map(row => row.day)).toEqual([
        ms('2026-02-02T00:00:00Z'),
        ms('2026-02-01T00:00:00Z'),
        ms('2026-01-31T00:00:00Z'),
      ]);
    });

    it('aggregation dense should count filled rows toward the limit', async () => {
      const rows = await days({ limit: 5 });
      expect(rows.map(row => row.count)).toEqual([1, 2, 1, 0, 0]);
    });

    it('aggregation dense fill rows should carry ANY metrics as explicit JSON null', async () => {
      const rows = await days({
        metrics: [any('productName', 'name'), count('count')],
      });
      expect(rows).toHaveLength(33);
      const gap = rows.find(row => row.day === ms('2026-01-04T00:00:00Z'))!;
      expect('name' in gap && gap.name === null).toBe(true);
      expect(rows.map(row => row.name)).toContain('Alpha');
    });

    it('aggregation dense week histogram should align local week starts in a non utc zone', async () => {
      const shanghai = (date: string) => ms(`${date}T00:00:00+08:00`);
      const rows = await ask({
        elements: lines(),
        groupBy: [
          dateHistogram(AggregationDateUnit.WEEK, 'week', {
            timeZone: 'Asia/Shanghai',
            dense: true,
          }),
        ],
        metrics: [count('count')],
      });
      expect(rows.map(row => row.week)).toEqual(
        [
          '2025-12-29',
          '2026-01-05',
          '2026-01-12',
          '2026-01-19',
          '2026-01-26',
          '2026-02-02',
        ].map(shanghai),
      );
      expect(rows[1].count).toBe(0);
    });

    it('aggregation dense day histogram should skip a local date the zone never had', async () => {
      const apia = snapshot('aggregation-apia', 1, [
        {
          status: 'PAID',
          lines: [
            line('before-skip', 1, 10, '2011-12-29T10:00:00Z', []),
            line('after-skip', 2, 20, '2011-12-30T12:00:00Z', []),
          ],
        },
      ]);
      const rows = await ask(
        {
          elements: lines(),
          groupBy: [
            dateHistogram(AggregationDateUnit.DAY, 'day', {
              timeZone: 'Pacific/Apia',
              dense: true,
            }),
          ],
          metrics: [count('count')],
        },
        [apia],
      );
      expect(rows.map(row => row.day)).toEqual([
        ms('2011-12-29T00:00:00-10:00'),
        ms('2011-12-31T00:00:00+14:00'),
      ]);
    });

    it('aggregation dense hour histogram should fill local wall clock hours in a half hour offset zone', async () => {
      const lordHowe = snapshot('aggregation-lord-howe', 1, [
        {
          status: 'PAID',
          lines: [
            line('hour-03', 1, 10, '2026-07-01T16:30:00Z', []),
            line('hour-05', 2, 20, '2026-07-01T18:30:00Z', []),
          ],
        },
      ]);
      const rows = await ask(
        {
          elements: lines(),
          groupBy: [
            dateHistogram(AggregationDateUnit.HOUR, 'hour', {
              timeZone: 'Australia/Lord_Howe',
              dense: true,
            }),
          ],
          metrics: [count('count')],
        },
        [lordHowe],
      );
      expect(rows.map(row => row.hour)).toEqual([
        ms('2026-07-01T16:30:00Z'),
        ms('2026-07-01T17:30:00Z'),
        ms('2026-07-01T18:30:00Z'),
      ]);
    });
  });

  it('aggregation terms missingKey should bucket missing values into the sentinel key', async () => {
    const rows = await ask({
      elements: lines(),
      groupBy: [terms('productName', 'name', '__missing__')],
      metrics: [count('count')],
    });
    expect(rows.map(row => row.name)).toEqual([
      'Alpha',
      'Alpha 2026',
      '__missing__',
    ]);
    expect(rows[2].count).toBe(4);
  });

  it('answers at most 100 groups unless the query asks for more, as Wow does', async () => {
    const documents = Array.from({ length: 150 }, (_, index) => ({ index }));
    const groups = {
      groupBy: [terms('index', 'index')],
      metrics: [count('count')],
    };
    expect(await ask(groups, documents)).toHaveLength(100);
    expect(await ask({ ...groups, limit: 150 }, documents)).toHaveLength(150);
  });
});

/**
 * `QueryBackendSpec`'s terms over a string array, in `query/query-tck`: its
 * orders (o1 to o5) and o6, whose keywords repeat — `a` on o1 and o6, `b` on
 * o1 and o2, `c` (twice) on o6 only; o3's are empty, o4 and o5 have none.
 */
describe('the query TCK (QueryBackendSpec): terms over an array', () => {
  const order = (id: string, amount: number, keywords?: string[]) => ({
    aggregateId: id,
    state: { amount, ...(keywords ? { keywords } : {}) },
  });
  const keywordOrders = [
    order('o1', 10.5, ['a', 'b']),
    order('o2', 20, ['b']),
    order('o3', 30, []),
    order('o4', 40),
    order('o5', 50),
    order('o6', 1, ['c', 'a', 'c']),
  ];

  it('aggregation groups a string array by each of its values', async () => {
    const rows = await ask(
      {
        groupBy: [terms('state.keywords', 'keyword')],
        metrics: [count('count'), sum('state.amount', 'total')],
      },
      keywordOrders,
    );
    // Null sorts first ascending; o6 counts once under `c`.
    expect(rows).toEqual([
      { keyword: null, count: 3, total: 120 },
      { keyword: 'a', count: 2, total: 11.5 },
      { keyword: 'b', count: 2, total: 30.5 },
      { keyword: 'c', count: 1, total: 1 },
    ]);
  });

  it('puts a record whose array holds nothing but nulls into missingKey', async () => {
    const rows = await ask(
      {
        groupBy: [terms('state.keywords', 'keyword', '__none__')],
        metrics: [count('count')],
      },
      [order('x', 1, [null, null] as never), order('y', 2, ['a'])],
    );
    expect(pick(rows, 'keyword', 'count')).toEqual([
      ['__none__', 1],
      ['a', 1],
    ]);
  });

  it('keys a scalar field by its value, as before', async () => {
    const rows = await ask(
      {
        groupBy: [terms('state.amount', 'amount')],
        metrics: [count('count')],
      },
      [order('x', 1), order('y', 1), order('z', 2)],
    );
    expect(pick(rows, 'amount', 'count')).toEqual([
      [1, 2],
      [2, 1],
    ]);
  });

  it('joins a record to every combination of two groups', async () => {
    const rows = await ask(
      {
        groupBy: [
          terms('state.keywords', 'keyword'),
          terms('state.colors', 'color'),
        ],
        metrics: [count('count'), sum('state.amount', 'total')],
      },
      [
        {
          aggregateId: 'x',
          state: { amount: 1, keywords: ['a', 'b'], colors: ['red', 'blue'] },
        },
        { aggregateId: 'y', state: { amount: 2, keywords: ['a'] } },
      ],
    );
    expect(pick(rows, 'keyword', 'color', 'count', 'total')).toEqual([
      ['a', null, 1, 2],
      ['a', 'blue', 1, 1],
      ['a', 'red', 1, 1],
      ['b', 'blue', 1, 1],
      ['b', 'red', 1, 1],
    ]);
  });
});
