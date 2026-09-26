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

import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  projectAnalysis,
  shapeChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type FilterNode,
  type MetricCardData,
  type MetricCardSpec,
  type RecordData,
} from '../src/index.js';
import { AnalysisChart, ViewSurface, zhCN } from '../src/ui/index.js';
import { metricReach } from '../src/analysis/metricWindow.js';
import { regrouped } from '../src/runtime/dashboard/grouping.js';
import { dailyOrdersDefinition, testSource } from './fixtures.js';

afterEach(cleanup);

/**
 * A trend card names the period its number covers, not the bucket it came
 * from (2026-09-26 review, P0-1): a month bucket under a filter of one day
 * is that day, under 「过去 7 天」 those days, and a period the dates cut
 * short is compared with nothing it is not like. And a card whose rows
 * stopped at the limit before the latest period says so, rather than
 * headlining the last bucket that fitted (P0-2).
 */

const NOW = new Date(Date.UTC(2026, 8, 22, 10));
const day = (month: number, n: number) => Date.UTC(2026, month - 1, n);

const MONTH: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'month',
  unit: 'MONTH',
  label: '月份',
};

const ORDERS: AnalysisMetric = { type: 'COUNT', alias: 'orders' };

function card(
  filter: FilterNode[] = [],
  spec: Partial<MetricCardSpec> = {},
  extra: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [MONTH],
    metrics: [ORDERS],
    sort: [{ alias: 'month', direction: 'ASC' }],
    limit: 400,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'metric',
      metric: { metric: 'orders', trend: { x: 'month' }, ...spec },
    },
    ...extra,
  };
}

const between = (from: string, to: string): FilterNode => ({
  field: 'createdAt',
  operator: 'BETWEEN',
  value: { type: 'absolute', from, to },
});

const shape = (
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  cutShort = false,
) =>
  shapeChart(config, rows, undefined, {
    timeZone: 'UTC',
    now: NOW,
    cutShort,
  }) as MetricCardData;

describe('the period a trend card’s number covers', () => {
  it('is the one day a filter holds of a month, over once that day is', () => {
    const data = shape(card([between('2026-09-21', '2026-09-21')]), [
      { month: day(9, 1), orders: 33 },
    ]);
    expect(data.value).toBe(33);
    expect(data.period).toEqual({
      at: day(9, 1),
      span: { from: day(9, 21), to: day(9, 22), zone: 'UTC' },
      unit: 'MONTH',
    });
  });

  it('is today so far under 「今天」, not the month so far', () => {
    const data = shape(
      card([
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'preset', preset: 'today' },
        },
      ]),
      [{ month: day(9, 1), orders: 2 }],
    );
    expect(data.period).toMatchObject({
      partial: true,
      span: { from: day(9, 22), to: day(9, 23) },
    });
  });

  it('is the days a range holds of a month, which ended when the range did', () => {
    const data = shape(card([between('2026-08-01', '2026-08-20')]), [
      { month: day(8, 1), orders: 400 },
    ]);
    expect(data.value).toBe(400);
    expect(data.period?.span).toEqual({
      from: day(8, 1),
      to: day(8, 21),
      zone: 'UTC',
    });
    expect(data.period?.partial).toBeUndefined();
  });

  it('compares two periods cut unlike each other with nothing', () => {
    // Seven days of August against five of September: not a change.
    const data = shape(card([between('2026-08-25', '2026-09-05')]), [
      { month: day(8, 1), orders: 70 },
      { month: day(9, 1), orders: 50 },
    ]);
    expect(data.period).toMatchObject({
      at: day(9, 1),
      span: { from: day(9, 1), to: day(9, 6) },
      unmatched: true,
    });
    expect(data.period?.change).toBeUndefined();
    expect(data.period?.previous).toBeUndefined();
  });

  it('reads 「本月至今」 as the month so far, not a month cut at ten o’clock', () => {
    const data = shape(
      card([
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'preset', preset: 'monthToDate' },
        },
      ]),
      [{ month: day(9, 1), orders: 2 }],
    );
    expect(data.period).toEqual({
      at: day(9, 1),
      unit: 'MONTH',
      partial: true,
    });
  });

  it('keeps whole buckets as they were: no span, a change', () => {
    const data = shape(card([between('2026-07-01', '2026-08-31')]), [
      { month: day(7, 1), orders: 40 },
      { month: day(8, 1), orders: 50 },
    ]);
    expect(data.period).toEqual({
      at: day(8, 1),
      unit: 'MONTH',
      previous: { at: day(7, 1), value: 40 },
      change: { delta: 10, ratio: 0.25 },
    });
  });
});

describe('a trend card whose rows stopped at the limit', () => {
  const rows = [
    { month: day(1, 1), orders: 1 },
    { month: day(2, 1), orders: 2 },
  ];

  it('shows no number when the latest period may be past the cut', () => {
    const data = shape(card(), rows, true);
    expect(data.value).toBeNull();
    expect(data.period).toBeUndefined();
    expect(data.cut).toEqual({ limit: 400 });
    // The sparkline still draws what came back.
    expect(data.trend).toHaveLength(2);
  });

  it('reads the latest when the rows run latest first, so the cut took the earliest', () => {
    const data = shape(
      card([], {}, { sort: [{ alias: 'month', direction: 'DESC' }] }),
      [...rows].reverse(),
      true,
    );
    expect(data.cut).toBeUndefined();
    expect(data.period?.at).toBe(day(2, 1));
  });
});

describe('a metric whose own dates the question’s miss', () => {
  const sum = (alias: string, preset: string): AnalysisMetric => ({
    type: 'COUNT',
    alias,
    filter: {
      op: 'and',
      children: [
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'preset', preset } as never,
        },
      ],
    },
  });
  const compared = (filter: FilterNode[]) =>
    card(
      filter,
      {
        metric: 'gmv',
        trend: undefined,
        compare: { metric: 'last', mode: 'percent' },
      },
      {
        groups: [],
        metrics: [
          sum('gmv', 'monthToDate'),
          sum('last', 'lastMonthToDate'),
          {
            type: 'DERIVED',
            alias: 'delta',
            expression: {
              type: 'BINARY',
              operator: 'SUBTRACT',
              left: { type: 'METRIC_REF', metric: 'gmv' },
              right: { type: 'METRIC_REF', metric: 'last' },
            },
          },
        ],
        sort: [],
      },
    );

  it('is out, cut short or untouched, and a derived metric reads its operands', () => {
    const reach = metricReach(compared([between('2026-09-21', '2026-09-21')]), {
      now: NOW,
      timeZone: 'UTC',
    });
    expect(Object.fromEntries(reach)).toEqual({
      gmv: 'clipped',
      last: 'out',
      delta: 'out',
    });
    expect(metricReach(compared([]), { now: NOW, timeZone: 'UTC' }).size).toBe(
      0,
    );
  });

  it('reads empty rather than 0, and the card compares with nothing', () => {
    const config = compared([between('2026-09-21', '2026-09-21')]);
    const view = projectAnalysis(
      dailyOrdersDefinition(),
      config,
      [{ gmv: 33, last: 0, delta: 33 }],
      undefined,
      undefined,
      { timeZone: 'UTC', now: NOW },
    );
    expect(view.rows).toEqual([{ gmv: 33, last: null, delta: null }]);
    expect((view.chart as MetricCardData).compare).toEqual({
      value: null,
      delta: null,
      unmatched: true,
    });
  });
});

describe('the note a result carries for a metric out of its dates', () => {
  it('names the metric the reader sees empty', async () => {
    const month = (from: string, to: string) => ({
      op: 'and' as const,
      children: [between(from, to)],
    });
    const config = card(
      [between('2026-09-21', '2026-09-21')],
      {},
      {
        groups: [],
        metrics: [
          {
            type: 'COUNT',
            alias: 'now',
            filter: month('2026-09-01', '2026-09-30'),
          },
          {
            type: 'COUNT',
            alias: 'last',
            label: '上月同期',
            filter: month('2026-08-01', '2026-08-31'),
          },
        ],
        sort: [],
        layout: 'table',
      },
    );
    const engine = new ViewEngine({
      definitions: [dailyOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'cards',
            definitionId: 'orders',
            title: 'Cards',
            scope: 'personal',
            revision: '1',
            config,
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    const runtime = await engine.open('cards');
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    expect(runtime.getSnapshot().result?.data.issues).toEqual([
      {
        code: 'analysis.metric.out-of-reach',
        severity: 'note',
        path: ['metrics', 1],
        params: { metric: '上月同期' },
      },
    ]);
  });
});

describe('a board’s time grouping', () => {
  it('drops the name the view gave a dimension at another unit', () => {
    const definition = dailyOrdersDefinition();
    const { config } = regrouped(card(), definition, 'DAY', []);
    const group = config.kind === 'analysis' ? config.groups[0] : undefined;
    expect(group).toMatchObject({ unit: 'DAY' });
    expect(group && 'label' in group).toBe(false);
    // At its own unit it keeps it.
    const same = regrouped(card(), definition, 'MONTH', []).config;
    expect(same.kind === 'analysis' && same.groups[0]).toMatchObject({
      label: '月份',
    });
  });
});

describe('the card says the span', () => {
  function drawn(
    config: AnalysisViewConfig,
    rows: RecordData[],
    locale: string,
    cutShort = false,
  ) {
    const data = shape(config, rows, cutShort);
    render(
      <ViewSurface
        locale={locale}
        timeZone="UTC"
        {...(locale === 'zh-CN' ? { messages: zhCN } : {})}
      >
        <AnalysisChart data={data} spec={config.chart} columns={[]} />
      </ViewSurface>,
    );
    const text = (slot: string) =>
      document.querySelector(`[data-slot="${slot}"]`)?.textContent;
    return text;
  }

  it('names one day, days of a month and days across months', () => {
    let text = drawn(
      card([between('2026-09-21', '2026-09-21')]),
      [{ month: day(9, 1), orders: 33 }],
      'zh-CN',
    );
    expect(text('metric-period')).toBe('2026年9月21日');
    cleanup();
    text = drawn(
      card([between('2026-09-15', '2026-09-21')]),
      [{ month: day(9, 1), orders: 33 }],
      'zh-CN',
    );
    expect(text('metric-period')).toBe('2026年9月15日–21日');
    cleanup();
    text = drawn(
      card([between('2026-08-25', '2026-09-05')]),
      [
        { month: day(8, 1), orders: 70 },
        { month: day(9, 1), orders: 50 },
      ],
      'en-US',
    );
    expect(text('metric-period')).toBe('September 1 – 5, 2026');
    expect(text('metric-change')).toBe(
      'The dates picked cover this period and the one before unequally; not compared',
    );
  });

  it('writes the times where an end falls inside a day, and both years apart', () => {
    let text = drawn(
      card([
        {
          field: 'createdAt',
          operator: 'GTE',
          value: { type: 'absolute', from: '2026-09-21T06:00:00.000Z' },
        },
        {
          field: 'createdAt',
          operator: 'LT',
          value: { type: 'absolute', from: '2026-09-21T18:00:00.000Z' },
        },
      ]),
      [{ month: day(9, 1), orders: 3 }],
      'en-US',
    );
    expect(text('metric-period')).toMatch(/^Sep 21, 2026, 6:00\sAM – /);
    cleanup();
    text = drawn(
      {
        ...card([between('2025-12-20', '2026-01-10')]),
        groups: [{ ...MONTH, unit: 'YEAR' as const }],
      },
      [{ month: Date.UTC(2026, 0, 1), orders: 3 }],
      'en-US',
    );
    expect(text('metric-period')).toBe('January 1 – 10, 2026');
    cleanup();
    // A week across the new year, cut to three days of it.
    text = drawn(
      {
        ...card([between('2025-12-31', '2026-01-02')]),
        groups: [{ ...MONTH, unit: 'WEEK' as const }],
      },
      [{ month: Date.UTC(2025, 11, 29), orders: 3 }],
      'en-US',
    );
    expect(text('metric-period')).toBe('Dec 31, 2025 – Jan 2, 2026');
    cleanup();
    text = drawn(
      {
        ...card([between('2025-12-20', '2026-01-10')], {}),
        groups: [{ ...MONTH, unit: 'QUARTER' as const }],
      },
      [
        { month: Date.UTC(2025, 9, 1), orders: 3 },
        { month: Date.UTC(2026, 0, 1), orders: 3 },
      ],
      'zh-CN',
    );
    expect(text('metric-period')).toBe('2026年1月1日–10日');
    expect(text('metric-change')).toBe(
      '所选日期在本期与上一期覆盖的长短不同，不作比较',
    );
  });

  it('says today so far by its day', () => {
    const text = drawn(
      card([
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'preset', preset: 'today' },
        },
      ]),
      [{ month: day(9, 1), orders: 2 }],
      'zh-CN',
    );
    expect(text('metric-period')).toBe('2026年9月22日（至今）');
  });

  it('says why there is no number when the latest period was cut off', () => {
    const text = drawn(
      card(),
      [{ month: day(1, 1), orders: 1 }],
      'zh-CN',
      true,
    );
    expect(text('metric-value')).toBe('—');
    expect(text('metric-period')).toBeUndefined();
    expect(text('metric-cut')).toContain('超过 400 期');
  });
});
