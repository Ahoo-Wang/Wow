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
 * How fast `rowSource` answers over the retail data set (README,
 * 「假数据源的速度」). Run with
 * `pnpm --filter wow-storybook exec vitest bench --run --project=unit`.
 *
 * The rows are the sub-orders of `generateRetail` at its default showcase
 * scale: about 20,000 over 2024-09-01 … 2026-09-22 in Asia/Shanghai, with
 * epoch-millisecond times. No answer comes from memory unless the case says
 * so. The same queries were timed in headless Chromium for the README's
 * table.
 */

import { bench, describe } from 'vitest';
import {
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
  SortDirection,
  type AggregationMetric,
  type AggregationQuery,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '@ahoo-wang/wow-view-engine';
import { generateRetail, shanghai } from './retail/generate.js';
import { rowSource } from './rowSource.js';

const ZONE = 'Asia/Shanghai';
const DAY = 24 * 3_600_000;
/** Yesterday, as the operations report reads it: 2026-09-21 in Shanghai. */
const YESTERDAY = shanghai('2026-09-21');

/** The showcase sub-orders, as the stories will read them. */
export function retailOrders(): RecordData[] {
  return generateRetail().orders as unknown as RecordData[];
}

const rows = retailOrders();

const field = (name: string) => ({
  type: AggregationExpressionType.FIELD as const,
  field: name,
});
const count: AggregationMetric = {
  type: AggregationMetricType.COUNT,
  alias: 'orders',
};
const gmv: AggregationMetric = {
  type: AggregationMetricType.NUMERIC,
  function: AggregationFunction.SUM,
  expression: field('state.amounts.paidAmount'),
  alias: 'gmv',
};
const buyers: AggregationMetric = {
  type: AggregationMetricType.DISTINCT_COUNT,
  expression: field('state.buyer.id'),
  alias: 'buyers',
};
const within = (from: number, to: number): FilterExpression[] => [
  { op: FilterOperator.GTE, field: 'firstEventTime', value: from },
  { op: FilterOperator.LT, field: 'firstEventTime', value: to },
];
const all = (...operands: FilterExpression[]): FilterExpression => ({
  op: FilterOperator.AND,
  operands,
});
const paid: FilterExpression = {
  op: FilterOperator.NOT_IN,
  field: 'state.status',
  values: ['PENDING_PAYMENT', 'CANCELLED'],
};
const days = (unit: AggregationDateUnit, dense = false) => ({
  type: AggregationGroupType.DATE_HISTOGRAM as const,
  field: 'firstEventTime',
  alias: 'at',
  unit,
  timeZone: ZONE,
  ...(dense ? { dense } : {}),
});
const terms = (name: string, alias: string) => ({
  type: AggregationGroupType.TERMS as const,
  field: name,
  alias,
});

/** A filter plus a `$group` over every row: the case README quotes first. */
export const FILTER_AND_GROUP: AggregationQuery = {
  filter: paid,
  groupBy: [terms('state.channel', 'channel')],
  metrics: [count, gmv, buyers],
};

/** Every day of the span, dense: about 750 buckets over every row. */
export const DAILY_OVER_THE_SPAN: AggregationQuery = {
  filter: paid,
  groupBy: [days(AggregationDateUnit.DAY, true)],
  metrics: [count, gmv],
  limit: 1_000,
};

/**
 * The eight panels of the operations report (`docs/scenarios.md` 4.1):
 * yesterday's cards and the day before for comparison, yesterday by the
 * hour, 30 days by the day, the channel split, the top provinces, the
 * status funnel over everything, and the monthly trend with its spread.
 */
export const BOARD: AggregationQuery[] = [
  {
    filter: all(...within(YESTERDAY, YESTERDAY + DAY)),
    metrics: [count, gmv, buyers],
  },
  {
    filter: all(...within(YESTERDAY - DAY, YESTERDAY)),
    metrics: [count, gmv, buyers],
  },
  {
    filter: all(...within(YESTERDAY, YESTERDAY + DAY), paid),
    groupBy: [days(AggregationDateUnit.HOUR, true)],
    metrics: [count, gmv],
  },
  {
    filter: all(...within(YESTERDAY - 29 * DAY, YESTERDAY + DAY), paid),
    groupBy: [days(AggregationDateUnit.DAY, true)],
    metrics: [count, gmv],
  },
  {
    filter: all(...within(YESTERDAY - 29 * DAY, YESTERDAY + DAY), paid),
    groupBy: [terms('state.channel', 'channel')],
    metrics: [count, gmv],
  },
  {
    filter: all(...within(YESTERDAY - 29 * DAY, YESTERDAY + DAY), paid),
    groupBy: [terms('state.address.province', 'province')],
    metrics: [gmv],
    sort: [{ field: 'gmv', direction: SortDirection.DESC }],
    limit: 10,
  },
  { groupBy: [terms('state.status', 'status')], metrics: [count] },
  {
    filter: paid,
    groupBy: [days(AggregationDateUnit.MONTH)],
    metrics: [
      gmv,
      {
        type: AggregationMetricType.PERCENTILE,
        expression: field('state.amounts.paidAmount'),
        percentile: 50,
        alias: 'median',
      },
      {
        type: AggregationMetricType.NUMERIC,
        function: AggregationFunction.STDDEV,
        expression: field('state.amounts.paidAmount'),
        alias: 'spread',
      },
    ],
  },
];

const options = { time: 2_000, warmupTime: 500 };

describe('rowSource over the showcase retail orders', () => {
  // One source for the per-query cases; a limit no answer reaches makes each
  // round a query the source has not seen, so none comes from memory.
  const built = rowSource(rows, { timeField: 'firstEventTime' });
  let round = 0;
  const unseen = (query: AggregationQuery): AggregationQuery => ({
    ...query,
    limit: 5_000 + (round++ % 5_000),
  });
  bench(
    'filter + $group, no time range',
    async () => {
      await built.aggregate(unseen(FILTER_AND_GROUP));
    },
    options,
  );
  bench(
    'every day of 25 months, dense',
    async () => {
      await built.aggregate(unseen(DAILY_OVER_THE_SPAN));
    },
    options,
  );
  bench(
    'the 8-panel board, first answers of a new source',
    async () => {
      const source = rowSource(rows, { timeField: 'firstEventTime' });
      await Promise.all(BOARD.map(query => source.aggregate(query)));
    },
    options,
  );
  const warm = rowSource(rows, { timeField: 'firstEventTime' });
  bench(
    'the 8-panel board, again on the same source',
    async () => {
      await Promise.all(BOARD.map(query => warm.aggregate(query)));
    },
    options,
  );
  bench(
    'building the source (sorting by time)',
    () => {
      rowSource(rows, { timeField: 'firstEventTime' });
    },
    options,
  );
});
