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

import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DerivedExpressionType,
  FilterOperator,
  HavingExpressionType,
  SortDirection,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '@ahoo-wang/wow-view-engine';
import { rowSource } from './rowSource.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const field = (name: string) => ({
  type: AggregationExpressionType.FIELD as const,
  field: name,
});
const count = (alias = 'count'): AggregationMetric => ({
  type: AggregationMetricType.COUNT,
  alias,
});
const numeric = (
  fn: AggregationFunction,
  name: string,
  alias: string,
  filter?: FilterExpression,
): AggregationMetric => ({
  type: AggregationMetricType.NUMERIC,
  function: fn,
  expression: field(name),
  alias,
  ...(filter ? { filter } : {}),
});
const percentile = (
  name: string,
  p: number,
  alias: string,
  filter?: FilterExpression,
): AggregationMetric => ({
  type: AggregationMetricType.PERCENTILE,
  expression: field(name),
  percentile: p,
  alias,
  ...(filter ? { filter } : {}),
});
const terms = (name: string, alias = name): AggregationGroup => ({
  type: AggregationGroupType.TERMS,
  field: name,
  alias,
});
const dates = (
  name: string,
  unit: AggregationDateUnit,
  timeZone: string,
  dense?: boolean,
  alias = 'at',
): AggregationGroup => ({
  type: AggregationGroupType.DATE_HISTOGRAM,
  field: name,
  unit,
  timeZone,
  alias,
  ...(dense === undefined ? {} : { dense }),
});
const eq = (name: string, value: unknown): FilterExpression =>
  ({ op: FilterOperator.EQ, field: name, value }) as FilterExpression;
const cmp = (
  op:
    | FilterOperator.GT
    | FilterOperator.GTE
    | FilterOperator.LT
    | FilterOperator.LTE
    | FilterOperator.EQ,
  name: string,
  value: number,
): FilterExpression => ({ op, field: name, value }) as FilterExpression;
const and = (...operands: FilterExpression[]): FilterExpression =>
  ({ op: FilterOperator.AND, operands }) as FilterExpression;
const or = (...operands: FilterExpression[]): FilterExpression =>
  ({ op: FilterOperator.OR, operands }) as FilterExpression;
const between = (name: string, from: number, to: number): FilterExpression =>
  ({
    op: FilterOperator.BETWEEN,
    field: name,
    lowerBound: from,
    upperBound: to,
  }) as FilterExpression;

function query(
  metrics: AggregationMetric[],
  rest: Omit<AggregationQuery, 'metrics'> = {},
): AggregationQuery {
  return { ...rest, metrics: metrics as AggregationQuery['metrics'] };
}

/** A zoned wall time as epoch milliseconds. */
const at = (wall: string, zone: string): number => {
  const local = Date.parse(`${wall.replace(' ', 'T')}Z`);
  let time = local;
  // Two rounds settle the offset for any time the clock does not skip.
  for (let round = 0; round < 2; round++)
    time =
      local - (Date.parse(`${wallOf(time, zone).replace(' ', 'T')}Z`) - time);
  return time;
};

/** An instant's wall time in a zone, as `YYYY-MM-DD HH:mm:ss`. */
const wallOf = (time: number, zone: string): string =>
  new Intl.DateTimeFormat('sv-SE', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(time);

/** A seeded stream of numbers in [0, 1), so a failure reproduces. */
function seeded(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1_664_525) + 1_013_904_223) >>> 0;
    return state / 2 ** 32;
  };
}

/** Orders in the retail shape: epoch-ms `firstEventTime`, `state.*` fields. */
function orders(size: number, seed = 7): RecordData[] {
  const random = seeded(seed);
  const from = Date.UTC(2025, 0, 1);
  const channels = ['APP', 'MINI', 'PC', 'LIVE'];
  return Array.from({ length: size }, (_, index) => ({
    id: `o${index}`,
    firstEventTime: from + Math.floor(random() * 400 * DAY),
    deleted: random() < 0.02,
    state: {
      channel: channels[Math.floor(random() * channels.length)],
      paidAmount: Math.round(random() * 100_000) / 100,
    },
  }));
}

describe('rowSource', () => {
  describe('the time index', () => {
    const rows = orders(2_000);
    const plain = rowSource(rows);
    const indexed = rowSource(rows, { timeField: 'firstEventTime' });
    const t = (days: number) => Date.UTC(2025, 0, 1) + days * DAY;
    const cases: [string, FilterExpression][] = [
      [
        'GTE and LT',
        and(
          cmp(FilterOperator.GTE, 'firstEventTime', t(30)),
          cmp(FilterOperator.LT, 'firstEventTime', t(61)),
        ),
      ],
      [
        'GT and LTE',
        and(
          cmp(FilterOperator.GT, 'firstEventTime', t(30)),
          cmp(FilterOperator.LTE, 'firstEventTime', t(61)),
        ),
      ],
      [
        'BETWEEN with another condition',
        and(
          between('firstEventTime', t(100), t(130) - 1),
          eq('state.channel', 'LIVE'),
        ),
      ],
      [
        'a nested AND',
        and(
          eq('state.channel', 'APP'),
          and(
            cmp(FilterOperator.GTE, 'firstEventTime', t(200)),
            cmp(FilterOperator.LT, 'firstEventTime', t(210)),
          ),
        ),
      ],
      [
        'an EQ on a row time',
        cmp(
          FilterOperator.EQ,
          'firstEventTime',
          rows[17].firstEventTime as number,
        ),
      ],
      [
        'a range past the rows',
        cmp(FilterOperator.GT, 'firstEventTime', t(900)),
      ],
      [
        'an OR, which is not cut',
        or(
          cmp(FilterOperator.LT, 'firstEventTime', t(10)),
          cmp(FilterOperator.GT, 'firstEventTime', t(390)),
        ),
      ],
      [
        'bounds that cross',
        and(
          cmp(FilterOperator.GTE, 'firstEventTime', t(50)),
          cmp(FilterOperator.LT, 'firstEventTime', t(40)),
        ),
      ],
    ];

    it.each(cases)(
      'answers %s as the unindexed source does',
      async (_, where) => {
        const sort = [{ field: 'id', direction: SortDirection.ASC }];
        const page = {
          filter: where,
          sort,
          pagination: { index: 1, size: 5000 },
        };
        expect(await indexed.paged(page)).toEqual(await plain.paged(page));
        const totals = query(
          [
            count(),
            numeric(AggregationFunction.SUM, 'state.paidAmount', 'paid'),
          ],
          {
            filter: where,
            groupBy: [terms('state.channel', 'channel')],
            sort: [{ field: 'channel', direction: SortDirection.ASC }],
          },
        );
        // The rows are summed in another order, so the cents are compared.
        const rounded = (answer: RecordData[]) =>
          answer.map(row => ({
            ...row,
            paid: Math.round((row.paid as number) * 100),
          }));
        expect(rounded(await indexed.aggregate(totals))).toEqual(
          rounded(await plain.aggregate(totals)),
        );
      },
    );

    it('reads the rows oldest first when the query has no sort', async () => {
      const { list } = await indexed.paged({
        filter: {
          op: FilterOperator.DELETION,
          state: 'ALL',
        } as FilterExpression,
        pagination: { index: 1, size: 5000 },
      });
      const times = list.map(row => row.firstEventTime as number);
      expect(times).toEqual([...times].sort((left, right) => left - right));
      expect(list).toHaveLength(rows.length);
    });

    it('refuses a time column that is not epoch milliseconds', () => {
      expect(() =>
        rowSource([{ firstEventTime: '2026-09-21T00:00:00Z' }], {
          timeField: 'firstEventTime',
        }),
      ).toThrow(/epoch milliseconds/);
    });
  });

  describe('date buckets', () => {
    /** Each row's bucket, by row id. */
    async function cut(
      times: number[],
      unit: AggregationDateUnit,
      zone: string,
    ) {
      const answer = await rowSource(
        times.map((time, id) => ({ id, time })),
      ).aggregate(
        query([count()], {
          groupBy: [terms('id', 'id'), dates('time', unit, zone)],
        }),
      );
      expect(answer).toHaveLength(times.length);
      return times.map(
        (_, id) => answer.find(row => row.id === id)!.at as number,
      );
    }

    /**
     * A bucket start by plain arithmetic, for a zone whose offset never
     * changes: the wall time is the instant plus the offset.
     */
    function fixed(
      time: number,
      offset: number,
      unit: AggregationDateUnit,
    ): number {
      const wall = time + offset;
      const date = new Date(wall);
      const [year, month] = [date.getUTCFullYear(), date.getUTCMonth()];
      const day = Math.floor(wall / DAY) * DAY;
      const floor = (width: number) => Math.floor(wall / width) * width;
      const local = {
        [AggregationDateUnit.YEAR]: Date.UTC(year, 0, 1),
        [AggregationDateUnit.QUARTER]: Date.UTC(year, month - (month % 3), 1),
        [AggregationDateUnit.MONTH]: Date.UTC(year, month, 1),
        [AggregationDateUnit.WEEK]: day - ((date.getUTCDay() + 6) % 7) * DAY,
        [AggregationDateUnit.DAY]: day,
        [AggregationDateUnit.HOUR]: floor(HOUR),
        [AggregationDateUnit.MINUTE]: floor(60_000),
        [AggregationDateUnit.SECOND]: floor(1_000),
      }[unit];
      return local - offset;
    }

    const random = seeded(11);
    const spread = Array.from(
      { length: 2_000 },
      () => Date.UTC(2024, 8, 1) + Math.floor(random() * 760 * DAY),
    );
    const fixedZones: [string, number][] = [
      ['Asia/Shanghai', 8 * HOUR],
      ['Asia/Kolkata', 5.5 * HOUR],
      ['UTC', 0],
    ];

    it.each(
      fixedZones.flatMap(([zone, offset]) =>
        Object.values(AggregationDateUnit).map(
          unit => [zone, unit, offset] as const,
        ),
      ),
    )('cuts %s by %s on the calendar', async (zone, unit, offset) => {
      expect(await cut(spread, unit, zone)).toEqual(
        spread.map(time => fixed(time, offset, unit)),
      );
    });

    it('cuts at local midnight on the days New York moves its clock', async () => {
      const york = 'America/New_York';
      const iso = (times: number[]) =>
        times.map(time => new Date(time).toISOString());
      const times = [
        Date.parse('2026-03-08T16:00:00Z'), // 12:00 EDT on the 23-hour day
        Date.parse('2026-03-09T04:30:00Z'), // 00:30 EDT the day after
        Date.parse('2026-11-02T04:00:00Z'), // 23:00 EST on the 25-hour day
        Date.parse('2026-11-15T12:00:00Z'),
      ];
      expect(iso(await cut(times, AggregationDateUnit.DAY, york))).toEqual([
        '2026-03-08T05:00:00.000Z',
        '2026-03-09T04:00:00.000Z',
        '2026-11-01T04:00:00.000Z',
        '2026-11-15T05:00:00.000Z',
      ]);
      expect(iso(await cut(times, AggregationDateUnit.MONTH, york))).toEqual([
        '2026-03-01T05:00:00.000Z',
        '2026-03-01T05:00:00.000Z',
        '2026-11-01T04:00:00.000Z',
        '2026-11-01T04:00:00.000Z',
      ]);
    });

    it('keeps the offset an hour is read in, as wow-mongo truncates it', async () => {
      const iso = (times: number[]) =>
        times.map(time => new Date(time).toISOString());
      expect(
        iso(
          await cut(
            [
              Date.parse('2026-03-08T06:30:00Z'), // 01:30 EST
              Date.parse('2026-03-08T07:30:00Z'), // 03:30 EDT
              Date.parse('2026-11-01T05:30:00Z'), // 01:30 EDT
              Date.parse('2026-11-01T06:30:00Z'), // 01:30 EST, the second time
            ],
            AggregationDateUnit.HOUR,
            'America/New_York',
          ),
        ),
      ).toEqual([
        '2026-03-08T06:00:00.000Z',
        '2026-03-08T07:00:00.000Z',
        '2026-11-01T05:00:00.000Z',
        '2026-11-01T06:00:00.000Z',
      ]);
      // Lord Howe moves half an hour: 02:00 becomes 02:30 on 2026-10-04.
      expect(
        iso(
          await cut(
            [
              Date.parse('2026-10-03T15:45:00Z'),
              Date.parse('2026-10-04T01:00:00Z'),
            ],
            AggregationDateUnit.HOUR,
            'Australia/Lord_Howe',
          ),
        ),
      ).toEqual(['2026-10-03T15:00:00.000Z', '2026-10-04T01:00:00.000Z']);
      expect(
        iso(
          await cut(
            [Date.parse('2026-10-04T01:00:00Z')],
            AggregationDateUnit.DAY,
            'Australia/Lord_Howe',
          ),
        ),
      ).toEqual(['2026-10-03T13:30:00.000Z']);
    });

    it('keys an ISO time as the instant it names', async () => {
      const answer = await rowSource([
        { time: '2026-09-21T15:59:59.999Z' },
        { time: '2026-09-21T16:00:00.000Z' },
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.DAY, 'Asia/Shanghai')],
        }),
      );
      expect(answer).toEqual([
        { at: at('2026-09-21 00:00', 'Asia/Shanghai'), count: 1 },
        { at: at('2026-09-22 00:00', 'Asia/Shanghai'), count: 1 },
      ]);
    });

    it('starts a week on Monday, as wow-mongo truncates it', async () => {
      const zone = 'Asia/Shanghai';
      const answer = await rowSource([
        { time: at('2026-09-20 23:59', zone) }, // Sunday
        { time: at('2026-09-21 00:00', zone) }, // Monday
        { time: at('2026-09-27 12:00', zone) }, // Sunday
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.WEEK, zone)],
        }),
      );
      expect(answer).toEqual([
        { at: at('2026-09-14 00:00', zone), count: 1 },
        { at: at('2026-09-21 00:00', zone), count: 2 },
      ]);
    });

    it('starts a week at its local Monday midnight across a clock change', async () => {
      const zone = 'America/New_York';
      // 2026-03-08 is a Sunday and the day the clocks go forward.
      const answer = await rowSource([
        { time: at('2026-03-08 12:00', zone) },
        { time: at('2026-03-09 10:00', zone) },
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.WEEK, zone)],
        }),
      );
      expect(
        answer.map(row => new Date(row.at as number).toISOString()),
      ).toEqual(['2026-03-02T05:00:00.000Z', '2026-03-09T04:00:00.000Z']);
    });

    it('starts a quarter in January, April, July or October', async () => {
      const zone = 'Asia/Shanghai';
      const answer = await rowSource([
        { time: at('2025-12-31 23:59', zone) },
        { time: at('2026-01-01 00:00', zone) },
        { time: at('2026-03-31 23:59', zone) },
        { time: at('2026-08-15 09:00', zone) },
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.QUARTER, zone)],
        }),
      );
      expect(answer).toEqual([
        { at: at('2025-10-01 00:00', zone), count: 1 },
        { at: at('2026-01-01 00:00', zone), count: 2 },
        { at: at('2026-07-01 00:00', zone), count: 1 },
      ]);
    });
  });

  describe('dense', () => {
    const zone = 'Asia/Shanghai';
    const rows = [
      { time: at('2026-09-01 08:00', zone), amount: 10, buyer: 'a' },
      { time: at('2026-09-01 20:00', zone), amount: 30, buyer: 'b' },
      { time: at('2026-09-04 09:00', zone), amount: 5, buyer: 'a' },
      { time: null, amount: 99, buyer: 'c' },
    ];
    const metrics: AggregationMetric[] = [
      count(),
      numeric(AggregationFunction.SUM, 'amount', 'amount'),
      {
        type: AggregationMetricType.DISTINCT_COUNT,
        expression: field('buyer'),
        alias: 'buyers',
      },
      { type: AggregationMetricType.ANY, field: 'buyer', alias: 'someone' },
      percentile('amount', 50, 'median'),
      {
        type: AggregationMetricType.DERIVED,
        alias: 'average',
        expression: {
          type: DerivedExpressionType.BINARY,
          operator: AggregationExpressionOperator.DIVIDE,
          left: { type: DerivedExpressionType.METRIC_REF, metric: 'amount' },
          right: { type: DerivedExpressionType.METRIC_REF, metric: 'count' },
        },
      },
    ];

    it('fills the empty buckets between the first and the last, as empty metrics', async () => {
      const answer = await rowSource(rows).aggregate(
        query(metrics, {
          groupBy: [dates('time', AggregationDateUnit.DAY, zone, true)],
        }),
      );
      const empty = {
        count: 0,
        amount: null,
        buyers: 0,
        someone: null,
        median: null,
        average: null,
      };
      expect(answer).toEqual([
        {
          at: at('2026-09-01 00:00', zone),
          count: 2,
          amount: 40,
          buyers: 2,
          someone: 'b',
          median: 20,
          average: 20,
        },
        { at: at('2026-09-02 00:00', zone), ...empty },
        { at: at('2026-09-03 00:00', zone), ...empty },
        {
          at: at('2026-09-04 00:00', zone),
          count: 1,
          amount: 5,
          buyers: 1,
          someone: 'a',
          median: 5,
          average: 5,
        },
      ]);
    });

    it('filters and sorts the filled buckets like any other', async () => {
      const answer = await rowSource(rows).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.DAY, zone, true)],
          having: {
            type: HavingExpressionType.CONDITION,
            metric: 'count',
            operator: ComparisonOperator.EQ,
            value: 0,
          },
          sort: [{ field: 'at', direction: SortDirection.DESC }],
          limit: 1,
        }),
      );
      expect(answer).toEqual([{ at: at('2026-09-03 00:00', zone), count: 0 }]);
    });

    it('fills weeks, months and quarters on the calendar', async () => {
      const spread = [
        { time: at('2026-01-10 12:00', zone) },
        { time: at('2026-09-21 12:00', zone) },
      ];
      const keys = async (unit: AggregationDateUnit) =>
        (
          await rowSource(spread).aggregate(
            query([count()], { groupBy: [dates('time', unit, zone, true)] }),
          )
        ).map(row => {
          const wall = wallOf(row.at as number, zone);
          expect(wall.slice(11)).toBe('00:00:00');
          return wall.slice(0, 10);
        });
      expect(await keys(AggregationDateUnit.QUARTER)).toEqual([
        '2026-01-01',
        '2026-04-01',
        '2026-07-01',
      ]);
      expect(await keys(AggregationDateUnit.MONTH)).toEqual(
        ['01', '02', '03', '04', '05', '06', '07', '08', '09'].map(
          month => `2026-${month}-01`,
        ),
      );
      const weeks = await keys(AggregationDateUnit.WEEK);
      expect(weeks[0]).toBe('2026-01-05');
      expect(weeks.at(-1)).toBe('2026-09-21');
      expect(weeks).toHaveLength(38);
      expect(
        new Set(weeks.map(day => new Date(`${day}T00:00:00Z`).getUTCDay())),
      ).toEqual(new Set([1]));
    });

    it('fills days of 23 and 25 hours at their local midnights', async () => {
      const york = 'America/New_York';
      const answer = await rowSource([
        { time: at('2026-03-06 12:00', york) },
        { time: at('2026-03-10 12:00', york) },
        { time: at('2026-10-30 12:00', york) },
        { time: at('2026-11-03 12:00', york) },
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.DAY, york, true)],
        }),
      );
      const days = answer.map(row => wallOf(row.at as number, york));
      // Every calendar date from 03-06 to 11-03, counted on a plain calendar.
      const calendar = Array.from(
        { length: (Date.UTC(2026, 10, 3) - Date.UTC(2026, 2, 6)) / DAY + 1 },
        (_, index) =>
          `${new Date(Date.UTC(2026, 2, 6) + index * DAY).toISOString().slice(0, 10)} 00:00:00`,
      );
      expect(days).toEqual(calendar);
      expect(answer.map(row => row.count).filter(Boolean)).toEqual([
        1, 1, 1, 1,
      ]);
    });

    it('fills hours of a day', async () => {
      const answer = await rowSource([
        { time: at('2026-09-21 09:10', zone) },
        { time: at('2026-09-21 12:50', zone) },
      ]).aggregate(
        query([count()], {
          groupBy: [dates('time', AggregationDateUnit.HOUR, zone, true)],
        }),
      );
      expect(answer.map(row => row.count)).toEqual([1, 0, 0, 1]);
      expect(answer[1].at).toBe(at('2026-09-21 10:00', zone));
    });

    it('answers nothing when no record has a time', async () => {
      expect(
        await rowSource([{ time: null }]).aggregate(
          query([count()], {
            groupBy: [dates('time', AggregationDateUnit.DAY, zone, true)],
          }),
        ),
      ).toEqual([]);
    });

    it('refuses a dense histogram beside another group, as Wow does', async () => {
      await expect(
        rowSource(rows).aggregate(
          query([count()], {
            groupBy: [
              dates('time', AggregationDateUnit.DAY, zone, true),
              terms('buyer'),
            ],
          }),
        ),
      ).rejects.toThrow(/only group/);
    });
  });

  describe('date parts', () => {
    const zone = 'Asia/Shanghai';
    const part = (
      name: AggregationDatePart,
      alias: string,
      dense?: boolean,
    ): AggregationGroup => ({
      type: AggregationGroupType.DATE_PART,
      field: 'time',
      part: name,
      timeZone: zone,
      alias,
      ...(dense === undefined ? {} : { dense }),
    });
    // 2026-09-21 is a Monday; 23:30 in Shanghai is 15:30 UTC, still Monday.
    const rows = [
      { time: at('2026-09-21 23:30', zone) },
      { time: at('2026-09-28 23:10', zone) },
      { time: at('2026-09-27 09:00', zone) },
      { time: at('2026-02-03 00:05', zone) },
    ];

    it('reads the ISO weekday and the hour on the zone’s wall clock', async () => {
      const answer = await rowSource(rows).aggregate(
        query([count()], {
          groupBy: [
            part(AggregationDatePart.DAY_OF_WEEK, 'weekday'),
            part(AggregationDatePart.HOUR_OF_DAY, 'hour'),
          ],
          sort: [
            { field: 'weekday', direction: SortDirection.ASC },
            { field: 'hour', direction: SortDirection.ASC },
          ],
        }),
      );
      expect(answer).toEqual([
        { weekday: 1, hour: 23, count: 2 },
        { weekday: 2, hour: 0, count: 1 },
        { weekday: 7, hour: 9, count: 1 },
      ]);
    });

    it('reads the day of the month and the month', async () => {
      const answer = await rowSource(rows).aggregate(
        query([count()], {
          groupBy: [
            part(AggregationDatePart.MONTH_OF_YEAR, 'month'),
            part(AggregationDatePart.DAY_OF_MONTH, 'day'),
          ],
          sort: [
            { field: 'month', direction: SortDirection.ASC },
            { field: 'day', direction: SortDirection.ASC },
          ],
        }),
      );
      expect(answer).toEqual([
        { month: 2, day: 3, count: 1 },
        { month: 9, day: 21, count: 1 },
        { month: 9, day: 27, count: 1 },
        { month: 9, day: 28, count: 1 },
      ]);
    });

    it('fills every key of the part when dense, in order', async () => {
      const answer = await rowSource(rows).aggregate(
        query([count()], {
          groupBy: [part(AggregationDatePart.DAY_OF_WEEK, 'weekday', true)],
        }),
      );
      expect(answer.map(row => [row.weekday, row.count])).toEqual([
        [1, 2],
        [2, 1],
        [3, 0],
        [4, 0],
        [5, 0],
        [6, 0],
        [7, 1],
      ]);
    });

    it('refuses a dense part beside another group, as Wow does', async () => {
      await expect(
        rowSource(rows).aggregate(
          query([count()], {
            groupBy: [
              part(AggregationDatePart.DAY_OF_WEEK, 'weekday', true),
              part(AggregationDatePart.HOUR_OF_DAY, 'hour'),
            ],
          }),
        ),
      ).rejects.toThrow(/only group/);
    });
  });

  describe('metrics', () => {
    const rows = [10, 20, 20, 30, 50].map((amount, index) => ({
      amount,
      kind: index < 3 ? 'small' : 'large',
    }));

    it('computes an exact percentile by linear interpolation', async () => {
      // Sorted [10, 20, 20, 30, 50]: rank (5 − 1) · 0.5 = 2 → 20; rank 3.8 → 30 + 0.8 · 20.
      expect(
        await rowSource(rows).aggregate(
          query([
            percentile('amount', 50, 'median'),
            percentile('amount', 95, 'p95'),
            percentile('amount', 1, 'p1'),
          ]),
        ),
      ).toEqual([{ median: 20, p95: 46, p1: 10.4 }]);
    });

    it('skips what is not a number and answers null over nothing', async () => {
      const answer = await rowSource([
        ...rows,
        { amount: null },
        { amount: 'n/a' },
        {},
      ]).aggregate(
        query([
          percentile('amount', 50, 'median'),
          percentile('amount', 50, 'none', eq('kind', 'huge')),
          numeric(
            AggregationFunction.STDDEV,
            'amount',
            'noSpread',
            eq('kind', 'huge'),
          ),
        ]),
      );
      expect(answer).toEqual([{ median: 20, none: null, noSpread: null }]);
    });

    it('computes a gated percentile over the rows its conditions admit', async () => {
      expect(
        await rowSource(rows).aggregate(
          query([
            count(),
            percentile('amount', 50, 'largeMedian', eq('kind', 'large')),
          ]),
        ),
      ).toEqual([{ count: 5, largeMedian: 40 }]);
    });

    it('computes the population standard deviation and variance', async () => {
      const spread = [2, 4, 4, 4, 5, 5, 7, 9].map(value => ({ value }));
      expect(
        await rowSource(spread).aggregate(
          query([
            numeric(AggregationFunction.STDDEV, 'value', 'stddev'),
            numeric(AggregationFunction.VARIANCE, 'value', 'variance'),
          ]),
        ),
      ).toEqual([{ stddev: 2, variance: 4 }]);
    });

    it('computes a spread per group and over a formula', async () => {
      const answer = await rowSource(rows).aggregate(
        query(
          [
            {
              type: AggregationMetricType.NUMERIC,
              function: AggregationFunction.VARIANCE,
              alias: 'variance',
              expression: {
                type: AggregationExpressionType.BINARY,
                operator: AggregationExpressionOperator.MULTIPLY,
                left: field('amount'),
                right: { type: AggregationExpressionType.CONSTANT, value: 2 },
              },
            },
          ],
          {
            groupBy: [terms('kind')],
            sort: [{ field: 'kind', direction: SortDirection.ASC }],
          },
        ),
      );
      // large: 60, 100 → mean 80, variance 400; small: 20, 40, 40 → mean 33⅓, variance 88.8…
      expect(answer[0]).toEqual({ kind: 'large', variance: 400 });
      expect(answer[1].kind).toBe('small');
      expect(answer[1].variance).toBeCloseTo(800 / 9, 10);
    });

    it('answers ANY with the greatest value, as wow-mongo picks it', async () => {
      const answer = await rowSource([
        { kind: 'a', name: 'pear' },
        { kind: 'a', name: 'apple' },
        { kind: 'a', name: null },
        { kind: 'b' },
      ]).aggregate(
        query(
          [
            { type: AggregationMetricType.ANY, field: 'name', alias: 'name' },
            {
              type: AggregationMetricType.ANY,
              field: 'name',
              alias: 'gated',
              filter: eq('name', 'apple'),
            },
          ],
          {
            groupBy: [terms('kind')],
            sort: [{ field: 'kind', direction: SortDirection.ASC }],
          },
        ),
      );
      expect(answer).toEqual([
        { kind: 'a', name: 'pear', gated: 'apple' },
        { kind: 'b', name: null, gated: null },
      ]);
    });

    it('answers every new metric null in the one row over nothing', async () => {
      expect(
        await rowSource([]).aggregate(
          query([
            count(),
            percentile('amount', 90, 'p90'),
            numeric(AggregationFunction.STDDEV, 'amount', 'stddev'),
            { type: AggregationMetricType.ANY, field: 'name', alias: 'name' },
          ]),
        ),
      ).toEqual([{ count: 0, p90: null, stddev: null, name: null }]);
    });
  });

  describe('first and last', () => {
    const rows = [
      { eventTime: 3, placed: 30, price: 12, shop: 'a' },
      { eventTime: 1, placed: 40, price: 10, shop: 'a' },
      { eventTime: 2, placed: 10, price: 15, shop: 'a' },
      { eventTime: 4, placed: 20, price: null, shop: 'a' },
      { eventTime: null, placed: 5, price: 99, shop: 'b' },
    ];
    const edge = (
      type: AggregationMetricType.FIRST | AggregationMetricType.LAST,
      alias: string,
      orderBy?: string,
      filter?: FilterExpression,
    ): AggregationMetric => ({
      type,
      field: 'price',
      alias,
      ...(orderBy ? { orderBy } : {}),
      ...(filter ? { filter } : {}),
    });

    it('reads the value on the earliest and the latest record by event time', async () => {
      const answer = await rowSource(rows).aggregate(
        query(
          [
            edge(AggregationMetricType.FIRST, 'open'),
            edge(AggregationMetricType.LAST, 'close'),
          ],
          {
            groupBy: [terms('shop')],
            sort: [{ field: 'shop', direction: SortDirection.ASC }],
          },
        ),
      );
      // The latest record has no price, so the one before it closes; the
      // shop whose only record has no time has no first and no last.
      expect(answer).toEqual([
        { shop: 'a', open: 10, close: 12 },
        { shop: 'b', open: null, close: null },
      ]);
    });

    it('orders by the field it names, under its own condition', async () => {
      const answer = await rowSource(rows).aggregate(
        query(
          [
            edge(AggregationMetricType.FIRST, 'open', 'placed'),
            edge(
              AggregationMetricType.LAST,
              'close',
              'placed',
              cmp(FilterOperator.LT, 'price', 14),
            ),
          ],
          { groupBy: [terms('shop')], filter: eq('shop', 'a') },
        ),
      );
      expect(answer).toEqual([{ shop: 'a', open: 15, close: 10 }]);
    });
  });

  describe('memory', () => {
    it('answers a repeated aggregation from memory, each time with its own copy', async () => {
      const rows: RecordData[] = [
        { kind: 'a', amount: 1 },
        { kind: 'a', amount: 2 },
      ];
      const source = rowSource(rows);
      const totals = query([
        numeric(AggregationFunction.SUM, 'amount', 'amount'),
      ]);
      const first = await source.aggregate(totals);
      expect(first).toEqual([{ amount: 3 }]);
      first[0].amount = 1_000;
      // The rows are fixed for a source's life; changing one under it shows
      // which answers come from memory.
      rows[0].amount = 100;
      expect(await source.aggregate(totals)).toEqual([{ amount: 3 }]);
      expect(await source.aggregate({ ...totals, limit: 10 })).toEqual([
        { amount: 102 },
      ]);
      expect(await rowSource(rows).aggregate(totals)).toEqual([
        { amount: 102 },
      ]);
    });

    it('does not remember a refusal', async () => {
      const source = rowSource([{ amount: 1 }]);
      const refused = query([count()], {
        groupBy: [terms('amount')],
        having: {
          type: HavingExpressionType.IS_NULL,
          metric: 'count',
        } as AggregationQuery['having'],
      });
      await expect(source.aggregate(refused)).rejects.toThrow(
        /does not evaluate IS_NULL/,
      );
      await expect(source.aggregate(refused)).rejects.toThrow(
        /does not evaluate IS_NULL/,
      );
    });
  });

  describe('array and envelope filters', () => {
    const rows: RecordData[] = [
      {
        aggregateId: 'TO-1',
        ownerId: 'M1',
        state: { tags: ['GIFT', 'URGENT'] },
      },
      { aggregateId: 'TO-2', ownerId: 'M2', state: { tags: ['GIFT'] } },
      { aggregateId: 'TO-3', ownerId: 'M1', state: { tags: [] } },
    ];
    const source = rowSource(rows);
    const ids = async (filter: FilterExpression) =>
      (await source.paged({ filter })).list.map(row => row.aggregateId);

    it('holds an array field to every value with CONTAINS_ALL, as $all does', async () => {
      await expect(
        ids({
          op: FilterOperator.CONTAINS_ALL,
          field: 'state.tags',
          values: ['GIFT', 'URGENT'],
        } as FilterExpression),
      ).resolves.toEqual(['TO-1']);
    });

    it('reads the aggregate id and the owner off the snapshot envelope', async () => {
      await expect(
        ids({ op: FilterOperator.OWNER_ID, value: 'M1' } as FilterExpression),
      ).resolves.toEqual(['TO-1', 'TO-3']);
      await expect(
        ids({
          op: FilterOperator.AGGREGATE_ID,
          value: 'TO-2',
        } as FilterExpression),
      ).resolves.toEqual(['TO-2']);
      await expect(
        ids({
          op: FilterOperator.AGGREGATE_IDS,
          values: ['TO-1', 'TO-3'],
        } as FilterExpression),
      ).resolves.toEqual(['TO-1', 'TO-3']);
    });
  });

  describe('refusals', () => {
    it('still refuses what it cannot translate', async () => {
      const source = rowSource([{ amount: 1 }]);
      await expect(
        source.aggregate(
          query([count()], {
            filter: {
              op: 'IS_EMPTY',
              field: 'tags',
            } as unknown as FilterExpression,
          }),
        ),
      ).rejects.toThrow(/does not evaluate IS_EMPTY/);
      await expect(
        source.aggregate(
          query([count()], {
            groupBy: [
              dates('amount', 'FORTNIGHT' as AggregationDateUnit, 'UTC'),
            ],
          }),
        ),
      ).rejects.toThrow(/does not bucket by FORTNIGHT/);
    });
  });
});
