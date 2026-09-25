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
 * How fast `rowSource` answers at the retail data set's size (README,
 * 「假数据源的速度」). Run with
 * `pnpm --filter wow-storybook exec vitest bench --run --project=unit`.
 *
 * The rows are synthetic but have the retail set's shape and size
 * (`docs/scenarios.md` 2.3, 2.5): about 20,000 sub-orders over
 * 2024-09-01 … 2026-09-22 in Asia/Shanghai, epoch-millisecond times,
 * nested `state.*` fields and an `items` array. No answer comes from memory
 * unless the case says so. The same queries were timed in headless Chromium
 * for the README's table.
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
import { rowSource } from './rowSource.js';

const ZONE = 'Asia/Shanghai';
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
/** 2024-09-01 00:00 and 2026-09-22 10:00 in Asia/Shanghai. */
const FROM = Date.UTC(2024, 7, 31, 16);
const NOW = Date.UTC(2026, 8, 22, 2);
/** Yesterday, as the operations report reads it: 2026-09-21 in Shanghai. */
const YESTERDAY = Date.UTC(2026, 8, 20, 16);

function seeded(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1_664_525) + 1_013_904_223) >>> 0;
    return state / 2 ** 32;
  };
}

/** About 20,000 sub-orders in the retail set's shape, oldest first or not. */
export function retailShapedOrders(size = 20_000, seed = 42): RecordData[] {
  const random = seeded(seed);
  const pick = <T>(values: readonly T[]) =>
    values[Math.floor(random() * values.length)];
  const channels = ['APP', 'MINI_PROGRAM', 'PC', 'LIVE', 'DISTRIBUTION'];
  const statuses = [
    'PENDING_PAYMENT',
    'PAID',
    'SHIPPED',
    'SIGNED',
    'COMPLETED',
    'COMPLETED',
    'COMPLETED',
    'CANCELLED',
    'CLOSED',
  ];
  const provinces = [
    '广东',
    '浙江',
    '江苏',
    '上海',
    '北京',
    '山东',
    '四川',
    '福建',
    '湖北',
    '河南',
    '新疆',
    '西藏',
  ];
  const levels = ['NORMAL', 'SILVER', 'GOLD', 'BLACK'];
  return Array.from({ length: size }, (_, index) => {
    // Growth over the span: later days are more likely.
    const placedAt = FROM + Math.floor(Math.sqrt(random()) * (NOW - FROM));
    const lines = 1 + Math.floor(random() ** 3 * 4);
    const items = Array.from({ length: lines }, (_, line) => {
      const qty = 1 + Math.floor(random() * 3);
      return {
        lineId: `${index}-${line}`,
        skuId: `SKU${Math.floor(random() ** 2 * 400)}`,
        qty,
        payAmount: Math.round(random() * 30_000) / 100,
      };
    });
    const paidAmount = items.reduce((sum, item) => sum + item.payAmount, 0);
    const status = pick(statuses);
    return {
      aggregateId: `TO${index}`,
      firstEventTime: placedAt,
      version: 3 + Math.floor(random() * 6),
      deleted: false,
      state: {
        orderNo: `TO${index}`,
        channel: pick(channels),
        status,
        buyer: { id: `B${Math.floor(random() * 7_000)}`, level: pick(levels) },
        address: { province: pick(provinces) },
        amounts: {
          paidAmount: Math.round(paidAmount * 100) / 100,
          refundedAmount:
            status === 'CLOSED' ? Math.round(paidAmount * 100) / 100 : 0,
        },
        items,
        shipSlaBreached: random() < 0.04,
      },
    };
  });
}

const rows = retailShapedOrders();

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

/** Every day of the span, dense: 752 buckets over all 20,000 rows. */
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

describe('rowSource over 20,000 retail-shaped rows', () => {
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
