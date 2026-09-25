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
  fitChartSlots,
  fitCharts,
  leadMetric,
  shapeChart,
  switchChartType,
  validateChart,
} from '../src/analysis/index.js';
import {
  fiveNumberMetrics,
  fiveNumberSets,
  isFiveNumberSet,
} from '../src/analysis/boxplot.js';
import { roundUp } from '../src/analysis/gauge.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartSpec,
  RecordData,
} from '../src/model/index.js';
import { analysisConfig } from './fixtures.js';

const amount = { type: 'FIELD', field: 'amount' } as const;
const hours = { type: 'FIELD', field: 'hours' } as const;
const WAREHOUSE: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'warehouse',
};
const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'placedAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'UTC',
};
const COUNT: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const SUM: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: amount,
};
const AVG: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'average',
  function: 'AVG',
  expression: amount,
};

/** A field's five numbers, under an optional condition. */
function fiveOf(
  expression: typeof amount | typeof hours,
  prefix: string,
  filter?: AnalysisMetric extends infer M
    ? M extends { filter?: infer F }
      ? F
      : never
    : never,
): AnalysisMetric[] {
  const scoped = filter === undefined ? {} : { filter };
  return [
    {
      type: 'NUMERIC',
      alias: `${prefix}_min`,
      function: 'MIN',
      expression,
      ...scoped,
    },
    {
      type: 'PERCENTILE',
      alias: `${prefix}_p25`,
      expression,
      percentile: 25,
      ...scoped,
    },
    {
      type: 'PERCENTILE',
      alias: `${prefix}_p50`,
      expression,
      percentile: 50,
      ...scoped,
    },
    {
      type: 'PERCENTILE',
      alias: `${prefix}_p75`,
      expression,
      percentile: 75,
      ...scoped,
    },
    {
      type: 'NUMERIC',
      alias: `${prefix}_max`,
      function: 'MAX',
      expression,
      ...scoped,
    },
  ];
}

const FIVE = fiveOf(amount, 'amount');
const BOX = {
  low: 'amount_min',
  q1: 'amount_p25',
  median: 'amount_p50',
  q3: 'amount_p75',
  high: 'amount_max',
};

const config = (
  chart: ChartSpec,
  groups: AnalysisGroup[] = [WAREHOUSE],
  metrics: AnalysisMetric[] = FIVE,
) =>
  analysisConfig({
    groups,
    metrics: metrics as [AnalysisMetric, ...AnalysisMetric[]],
    chart,
  });

const codes = (
  chart: ChartSpec,
  groups?: AnalysisGroup[],
  metrics?: AnalysisMetric[],
) => validateChart(config(chart, groups, metrics)).map(issue => issue.code);

describe('five numbers (D41)', () => {
  it('finds a field’s lowest, three percentiles rising and highest as one set', () => {
    expect(fiveNumberSets(FIVE)).toEqual([BOX]);
    // In any order, beside other metrics, the percentile nearest 50 the median.
    const mixed = [COUNT, FIVE[4]!, FIVE[2]!, FIVE[0]!, FIVE[3]!, FIVE[1]!];
    expect(fiveNumberSets(mixed)).toEqual([BOX]);
    const ninety: AnalysisMetric = {
      type: 'PERCENTILE',
      alias: 'amount_p90',
      expression: amount,
      percentile: 90,
    };
    expect(fiveNumberSets([...FIVE, ninety])[0]).toMatchObject({
      q1: 'amount_p25',
      median: 'amount_p50',
      q3: 'amount_p90',
    });
  });

  it('keeps two fields, and two conditions, apart', () => {
    const sets = fiveNumberSets([...FIVE, ...fiveOf(hours, 'hours')]);
    expect(sets.map(set => set.median)).toEqual(['amount_p50', 'hours_p50']);
    const shipped = fiveOf(amount, 'shipped', {
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'SHIPPED' }],
    } as never);
    // Half under a condition and half not is no box.
    expect(
      fiveNumberSets([FIVE[0]!, FIVE[1]!, shipped[2]!, FIVE[3]!, FIVE[4]!]),
    ).toEqual([]);
    expect(fiveNumberSets(shipped)).toHaveLength(1);
  });

  it('needs all five, and reads only the quantities', () => {
    expect(fiveNumberSets(FIVE.slice(0, 4))).toEqual([]);
    expect(fiveNumberSets(FIVE.slice(1))).toEqual([]);
    expect(
      fiveNumberSets(FIVE, new Set(FIVE.slice(1).map(m => m.alias))),
    ).toEqual([]);
  });

  it('checks a spec’s five slots are such a set', () => {
    const byAlias = new Map(FIVE.map(metric => [metric.alias, metric]));
    const of = (alias: string) => byAlias.get(alias);
    expect(isFiveNumberSet(BOX, of)).toBe(true);
    expect(
      isFiveNumberSet({ ...BOX, q1: 'amount_p75', q3: 'amount_p25' }, of),
    ).toBe(false);
    expect(isFiveNumberSet({ ...BOX, low: 'amount_max' }, of)).toBe(false);
    byAlias.set('average', AVG);
    expect(isFiveNumberSet({ ...BOX, median: 'average' }, of)).toBe(false);
    expect(isFiveNumberSet({ ...BOX, median: 'nothing' }, of)).toBe(false);
  });

  it('adds what a metric of a field lacks, under its condition, beside free aliases', () => {
    const added = fiveNumberMetrics(SUM, ['total', 'amount_min_1'])!;
    expect(added.map(metric => metric.alias)).toEqual([
      'amount_min_2',
      'amount_p25_1',
      'amount_p50_1',
      'amount_p75_1',
      'amount_max_1',
    ]);
    expect(fiveNumberSets([SUM, ...added])).toHaveLength(1);
    // The metric itself is one of the five: four are added.
    expect(fiveNumberMetrics(FIVE[2]!, [])!.map(metric => metric.type)).toEqual(
      ['NUMERIC', 'PERCENTILE', 'PERCENTILE', 'NUMERIC'],
    );
    const filter = { op: 'and', children: [] } as never;
    expect(
      fiveNumberMetrics({ ...FIVE[0]!, filter } as AnalysisMetric, [])!.every(
        metric => 'filter' in metric && metric.filter === filter,
      ),
    ).toBe(true);
    expect(fiveNumberMetrics(COUNT, [])).toBeUndefined();
    expect(
      fiveNumberMetrics(
        { type: 'DISTINCT_COUNT', alias: 'd', expression: amount },
        [],
      ),
    ).toBeUndefined();
    // A percentile of something that is not a field is named by 「value」.
    expect(
      fiveNumberMetrics(
        {
          type: 'PERCENTILE',
          alias: 'x',
          percentile: 50,
          expression: {
            type: 'BINARY',
            operator: 'MULTIPLY',
            left: amount,
            right: { type: 'CONSTANT', value: 2 },
          },
        },
        [],
      )![0]!.alias,
    ).toBe('value_min_1');
  });
});

describe('the boxplot', () => {
  it('fits one dimension over a field’s five numbers', () => {
    expect(fitCharts({ groups: [WAREHOUSE], metrics: FIVE }).boxplot).toEqual({
      available: true,
    });
    expect(
      fitCharts({ groups: [WAREHOUSE], metrics: [COUNT] }).boxplot.reason,
    ).toBe('chart.fit.needs-five-numbers');
    expect(fitCharts({ groups: [], metrics: FIVE }).boxplot.reason).toBe(
      'chart.fit.needs-dimension',
    );
    expect(
      fitCharts({ groups: [WAREHOUSE, DAY], metrics: FIVE }).boxplot.reason,
    ).toBe('chart.fit.needs-one-dimension');
  });

  it('fills its slots with the first set, and keeps a set chosen', () => {
    const two = [...FIVE, ...fiveOf(hours, 'hours')];
    const filled = fitChartSlots({ type: 'boxplot' }, [WAREHOUSE], two);
    expect(filled.boxplot).toEqual({ category: 'warehouse', ...BOX });
    const chosen = fitChartSlots(
      {
        type: 'boxplot',
        boxplot: {
          category: 'warehouse',
          low: 'hours_min',
          q1: 'hours_p25',
          median: 'hours_p50',
          q3: 'hours_p75',
          high: 'hours_max',
        },
      },
      [WAREHOUSE],
      two,
    );
    expect(chosen.boxplot?.median).toBe('hours_p50');
    // No set: empty slots, which validation names.
    expect(
      fitChartSlots({ type: 'boxplot' }, [WAREHOUSE], [COUNT]).boxplot,
    ).toEqual({
      category: 'warehouse',
      low: '',
      q1: '',
      median: '',
      q3: '',
      high: '',
    });
    expect(leadMetric(filled)).toBe('amount_p50');
    // A box is five numbers, not one carried over.
    expect(switchChartType({ ...filled }, 'bar').cartesian).toBeUndefined();
    expect(
      switchChartType(
        {
          type: 'bar',
          cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
        },
        'boxplot',
      ).boxplot,
    ).toBeUndefined();
  });

  it('refuses five numbers that are not one field’s set', () => {
    expect(
      codes({ type: 'boxplot', boxplot: { category: 'warehouse', ...BOX } }),
    ).toEqual([]);
    expect(
      codes({
        type: 'boxplot',
        boxplot: { category: 'warehouse', ...BOX, median: 'amount_min' },
      }),
    ).toEqual(['chart.boxplot.not-five-numbers']);
    expect(
      codes({
        type: 'boxplot',
        boxplot: { category: 'warehouse', ...BOX, high: 'gone' },
      }),
    ).toEqual(['chart.metric.unknown']);
  });

  it('shapes a box per row, time forward, and counts a row missing a number', () => {
    const rows: RecordData[] = [
      {
        day: Date.UTC(2026, 0, 2),
        amount_min: 1,
        amount_p25: 2,
        amount_p50: 3,
        amount_p75: 4,
        amount_max: 5,
      },
      {
        day: Date.UTC(2026, 0, 1),
        amount_min: 0,
        amount_p25: 1,
        amount_p50: 2,
        amount_p75: 3,
        amount_max: 9,
      },
      {
        day: Date.UTC(2026, 0, 3),
        amount_min: null,
        amount_p25: 1,
        amount_p50: 2,
        amount_p75: 3,
        amount_max: 9,
      },
    ];
    const data = shapeChart(
      config({ type: 'boxplot', boxplot: { category: 'day', ...BOX } }, [DAY]),
      rows,
    );
    expect(data).toEqual({
      type: 'boxplot',
      boxes: [
        {
          group: Date.UTC(2026, 0, 1),
          low: 0,
          q1: 1,
          median: 2,
          q3: 3,
          high: 9,
        },
        {
          group: Date.UTC(2026, 0, 2),
          low: 1,
          q1: 2,
          median: 3,
          q3: 4,
          high: 5,
        },
      ],
      omitted: 1,
      approximate: true,
    });
  });
});

describe('the gauge', () => {
  it('fits one number and nothing grouped', () => {
    expect(fitCharts({ groups: [], metrics: [COUNT] }).gauge.available).toBe(
      true,
    );
    expect(
      fitCharts({ groups: [WAREHOUSE], metrics: [COUNT] }).gauge.reason,
    ).toBe('chart.fit.needs-no-dimension');
    expect(
      fitCharts({ groups: [], metrics: [COUNT], moments: new Set(['orders']) })
        .gauge.reason,
    ).toBe('chart.fit.needs-quantity');
  });

  it('keeps its type over no dimension, and its scale whichever metric', () => {
    const chart = fitChartSlots(
      { type: 'gauge', gauge: { metric: 'gone', target: 10, max: 20 } },
      [],
      [COUNT, SUM],
    );
    expect(chart).toEqual({
      type: 'gauge',
      gauge: { metric: 'orders', target: 10, max: 20 },
    });
    expect(leadMetric(chart)).toBe('orders');
    expect(
      switchChartType({ type: 'metric', metric: { metric: 'total' } }, 'gauge')
        .gauge,
    ).toEqual({ metric: 'total' });
  });

  it('refuses a dimension, a number that is none and a scale with no length', () => {
    const ok = { type: 'gauge' as const, gauge: { metric: 'orders' } };
    expect(codes(ok, [], [COUNT])).toEqual([]);
    expect(codes(ok, [WAREHOUSE], [COUNT])).toContain(
      'chart.gauge.needs-no-group',
    );
    expect(
      codes(
        { type: 'gauge', gauge: { metric: 'orders', target: Number.NaN } },
        [],
        [COUNT],
      ),
    ).toEqual(['chart.gauge.not-a-number']);
    expect(
      codes(
        { type: 'gauge', gauge: { metric: 'orders', min: 5, max: 5 } },
        [],
        [COUNT],
      ),
    ).toEqual(['chart.gauge.empty-scale']);
  });

  it('shapes a scale to a round number past the value and the target', () => {
    const gauge = (spec: object, rows: RecordData[] = [{ orders: 870 }]) =>
      shapeChart(
        config(
          { type: 'gauge', gauge: { metric: 'orders', ...spec } },
          [],
          [COUNT],
        ),
        rows,
      );
    expect(gauge({})).toEqual({ type: 'gauge', value: 870, min: 0, max: 1000 });
    expect(gauge({ target: 1200 })).toEqual({
      type: 'gauge',
      value: 870,
      target: 1200,
      reached: 870 / 1200,
      min: 0,
      max: 2000,
    });
    expect(gauge({ max: 500 })).toMatchObject({ max: 500, beyond: 'above' });
    expect(gauge({ min: 900, max: 1000 })).toMatchObject({ beyond: 'below' });
    expect(gauge({}, [{ orders: -30 }])).toMatchObject({ min: -50, max: 0 });
    expect(gauge({}, [])).toEqual({
      type: 'gauge',
      value: null,
      min: 0,
      max: 1,
    });
    expect(gauge({ target: 0 }, [{ orders: 3 }])).not.toHaveProperty('reached');
  });

  it('rounds up by one, two, two and a half and five', () => {
    expect([0.3, 3, 20, 21, 870, 1230, 2400].map(roundUp)).toEqual([
      0.5, 5, 20, 25, 1000, 2000, 2500,
    ]);
    expect(roundUp(0)).toBe(0);
    expect(roundUp(-4)).toBe(0);
  });
});

describe('the radar and parallel axes', () => {
  const three = [COUNT, SUM, AVG];

  it('fit one dimension and three metrics a mark can measure', () => {
    for (const type of ['radar', 'parallel'] as const) {
      expect(
        fitCharts({ groups: [WAREHOUSE], metrics: three })[type].available,
      ).toBe(true);
      expect(
        fitCharts({ groups: [WAREHOUSE], metrics: [COUNT, SUM] })[type].reason,
      ).toBe('chart.fit.needs-three-metrics');
      expect(
        fitCharts({
          groups: [WAREHOUSE],
          metrics: three,
          moments: new Set(['average']),
        })[type].reason,
      ).toBe('chart.fit.needs-quantity');
      expect(fitCharts({ groups: [], metrics: three })[type].reason).toBe(
        'chart.fit.needs-dimension',
      );
      expect(
        fitCharts({ groups: [WAREHOUSE, DAY], metrics: three })[type].reason,
      ).toBe('chart.fit.needs-one-dimension');
    }
  });

  it('keeps the axes the spec lists, every metric while it lists none', () => {
    expect(fitChartSlots({ type: 'radar' }, [WAREHOUSE], three).radar).toEqual({
      category: 'warehouse',
      metrics: ['orders', 'total', 'average'],
    });
    expect(
      fitChartSlots(
        {
          type: 'parallel',
          parallel: {
            category: 'x',
            metrics: ['average', 'gone', 'orders', 'orders'],
          },
        },
        [WAREHOUSE],
        three,
      ).parallel,
    ).toEqual({ category: 'warehouse', metrics: ['average', 'orders'] });
    const radar: ChartSpec = {
      type: 'radar',
      radar: { category: 'warehouse', metrics: ['total', 'average', 'orders'] },
    };
    expect(leadMetric(radar)).toBe('total');
    expect(
      leadMetric({
        type: 'parallel',
        parallel: { category: 'w', metrics: ['orders'] },
      }),
    ).toBe('orders');
    // The lead joins the axes at the front, once.
    expect(
      switchChartType(
        {
          type: 'pie',
          pie: { category: 'warehouse', value: 'orders' },
          radar: { category: 'warehouse', metrics: ['total', 'average'] },
        },
        'radar',
      ).radar?.metrics,
    ).toEqual(['orders', 'total', 'average']);
    expect(
      switchChartType(
        {
          ...radar,
          pie: { category: 'warehouse', value: 'total' },
          type: 'pie',
        },
        'radar',
      ).radar?.metrics,
    ).toEqual(['total', 'average', 'orders']);
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'warehouse', value: 'total' } },
        'parallel',
      ).parallel,
    ).toBeUndefined();
  });

  it('refuses fewer than three axes, and one listed twice', () => {
    expect(
      codes(
        {
          type: 'radar',
          radar: { category: 'warehouse', metrics: ['orders', 'total'] },
        },
        [WAREHOUSE],
        three,
      ),
    ).toEqual(['chart.radar.too-few-metrics']);
    expect(
      codes(
        {
          type: 'parallel',
          parallel: {
            category: 'warehouse',
            metrics: ['orders', 'total', 'orders'],
          },
        },
        [WAREHOUSE],
        three,
      ),
    ).toEqual(['chart.parallel.duplicate-metric']);
    expect(
      codes(
        {
          type: 'parallel',
          parallel: { category: 'warehouse', metrics: ['orders', 'total'] },
        },
        [WAREHOUSE],
        three,
      ),
    ).toEqual(['chart.parallel.too-few-metrics']);
    expect(
      codes(
        {
          type: 'radar',
          radar: {
            category: 'warehouse',
            metrics: ['orders', 'orders', 'orders'],
          },
        },
        [WAREHOUSE],
        three,
      ),
    ).toEqual(['chart.radar.duplicate-metric']);
  });

  it('draws a radar’s first eight groups and counts the rest', () => {
    const rows = Array.from({ length: 10 }, (_, index) => ({
      warehouse: `W${index}`,
      orders: index,
      total: index * 10,
      average: index === 9 ? null : 5,
    }));
    const spec = {
      category: 'warehouse',
      metrics: ['orders', 'total', 'average'],
    };
    const radar = shapeChart(
      config({ type: 'radar', radar: spec }, [WAREHOUSE], three),
      rows,
    );
    expect(radar).toMatchObject({ type: 'radar', omitted: 2 });
    expect(radar?.type === 'radar' && radar.profiles.map(p => p.group)).toEqual(
      ['W0', 'W1', 'W2', 'W3', 'W4', 'W5', 'W6', 'W7'],
    );
    const parallel = shapeChart(
      config({ type: 'parallel', parallel: spec }, [WAREHOUSE], three),
      rows,
    );
    expect(parallel).toMatchObject({ type: 'parallel', omitted: 1 });
    expect(parallel?.type === 'parallel' && parallel.profiles).toHaveLength(9);
    expect(parallel?.type === 'parallel' && parallel.profiles[2]).toEqual({
      group: 'W2',
      values: [2, 20, 5],
    });
  });

  it('runs a time dimension forward', () => {
    const rows = [
      { day: Date.UTC(2026, 0, 2), orders: 1, total: 1, average: 1 },
      { day: Date.UTC(2026, 0, 1), orders: 2, total: 2, average: 2 },
    ];
    const data = shapeChart(
      config(
        {
          type: 'parallel',
          parallel: {
            category: 'day',
            metrics: ['orders', 'total', 'average'],
          },
        },
        [DAY],
        three,
      ),
      rows,
    );
    expect(
      data?.type === 'parallel' && data.profiles.map(p => p.group),
    ).toEqual([Date.UTC(2026, 0, 1), Date.UTC(2026, 0, 2)]);
  });
});
