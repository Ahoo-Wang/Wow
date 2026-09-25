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
  projectAnalysis,
  shapeChart,
  validateChart,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type CartesianSpec,
  type RecordData,
} from '../src/index.js';
import {
  derivedGap,
  derivedValues,
  movingWindow,
} from '../src/analysis/derived.js';
import { ordersDefinition } from './fixtures.js';
import {
  placeLines,
  seriesExtremes,
  statistic,
} from '../src/analysis/references.js';

/**
 * What a cartesian chart draws over its marks (D33 batch B), all computed
 * in the kernel: reference lines at a statistic of the measured values,
 * target bands, the highest and lowest points, and a trend, a moving
 * average and a running total along the whole time axis — none of them
 * over a 0 the kernel filled in, and none over rows that are not whole
 * (Q53), each left out with why.
 */

const day = (n: number) => Date.UTC(2026, 8, n);

const daily: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'createdAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'UTC',
};
const region: AnalysisGroup = { type: 'TERMS', field: 'region', alias: 'wh' };

const count: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const average: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'avg',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

function config(
  cartesian: Partial<CartesianSpec> = {},
  {
    groups = [daily],
    metrics = [count],
    type = 'line',
    having,
  }: {
    groups?: AnalysisGroup[];
    metrics?: AnalysisViewConfig['metrics'];
    type?: 'line' | 'bar' | 'area';
    having?: AnalysisViewConfig['having'];
  } = {},
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups,
    metrics,
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    ...(having ? { having } : {}),
    chart: {
      type,
      cartesian: {
        x: groups[0].alias,
        series: metrics.map(metric => ({ metric: metric.alias })),
        ...cartesian,
      },
    },
  };
}

/** Five days, the third one missing from the rows. */
const rows: RecordData[] = [
  { day: day(1), orders: 4 },
  { day: day(2), orders: 8 },
  { day: day(4), orders: 2 },
  { day: day(5), orders: 6 },
];

const shaped = (
  cfg: AnalysisViewConfig,
  from: RecordData[] = rows,
  cutShort = false,
) =>
  shapeChart(cfg, from, undefined, {
    timeZone: 'UTC',
    cutShort,
  }) as CartesianData;

describe('reference statistics', () => {
  it('takes the mean and the middle value', () => {
    expect(statistic([4, 8, 2, 6], 'average')).toBe(5);
    expect(statistic([4, 8, 2, 6], 'median')).toBe(5);
    expect(statistic([4, 8, 2], 'median')).toBe(4);
  });

  it('leaves a filled-in 0 out of an average line', () => {
    // Day 3 is filled with a known 0 (a count), which the library's own
    // average would have counted: (4+8+0+2+6)/5 = 4.
    const data = shaped(
      config({
        referenceLines: [
          { axis: 'left', statistic: 'average', metric: 'orders' },
        ],
      }),
    );
    expect(data.points[2]).toMatchObject({ values: { orders: 0 } });
    expect(data.points[2].filled).toEqual(['orders']);
    expect(data.references).toEqual([
      { axis: 'left', statistic: 'average', metric: 'orders', value: 5 },
    ]);
  });

  it('keeps a constant line as written', () => {
    const data = shaped(
      config({ referenceLines: [{ axis: 'left', value: 7, label: '目标' }] }),
    );
    expect(data.references).toEqual([
      { axis: 'left', value: 7, label: '目标' },
    ]);
    expect(data.gaps).toBeUndefined();
  });

  it('leaves a statistic line out over a split, over shares and over nothing measured, saying why', () => {
    const split = config(
      {
        splitBy: 'wh',
        referenceLines: [
          { axis: 'left', statistic: 'median', metric: 'orders' },
        ],
      },
      { groups: [daily, region] },
    );
    const data = shaped(split, [
      { day: day(1), wh: 'east', orders: 1 },
      { day: day(1), wh: 'south', orders: 2 },
    ]);
    expect(data.references).toBeUndefined();
    expect(data.gaps).toEqual([
      { kind: 'median', metric: 'orders', gap: 'split' },
    ]);

    const spec: CartesianSpec = {
      x: 'day',
      series: [{ metric: 'orders', stack: 'all' }],
      percentStack: true,
      referenceLines: [
        { axis: 'left', statistic: 'average', metric: 'orders' },
      ],
    };
    const bars = {
      ...shaped(config({}, { type: 'bar' })),
      chart: 'bar' as const,
    };
    expect(placeLines(spec, bars).gaps).toEqual([
      { kind: 'average', metric: 'orders', gap: 'shares' },
    ]);

    expect(
      placeLines(
        {
          x: 'day',
          series: [{ metric: 'orders' }],
          referenceLines: [
            { axis: 'left', statistic: 'average', metric: 'orders' },
          ],
        },
        {
          ...bars,
          points: [{ x: 1, values: { orders: 0 }, filled: ['orders'] }],
        },
      ).gaps,
    ).toEqual([{ kind: 'average', metric: 'orders', gap: 'none-measured' }]);
  });
});

describe('seriesExtremes', () => {
  it('marks the highest and the lowest measured point, never a filled-in 0', () => {
    const data = shaped(config({ extremes: true }));
    // Day 3 (index 2) is the filled 0; the lowest measured is day 4's 2.
    expect(data.extremes).toEqual({ orders: { high: 1, low: 3 } });
  });

  it('marks nothing on a flat series or a single point', () => {
    const flat = shaped(config({ extremes: true }), [
      { day: day(1), orders: 3 },
      { day: day(2), orders: 3 },
    ]);
    expect(flat.extremes).toBeUndefined();
    expect(
      seriesExtremes({
        type: 'cartesian',
        chart: 'line',
        points: [{ x: 1, values: { orders: 3 } }],
        series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
      }),
    ).toEqual({});
  });

  it('marks nothing unless asked', () => {
    expect(shaped(config()).extremes).toBeUndefined();
  });
});

describe('derived series', () => {
  it('computes a running total over the whole time axis, the filled day included', () => {
    const data = shaped(
      config({ derived: [{ kind: 'cumulative', metric: 'orders' }] }),
    );
    expect(data.derived).toEqual([
      expect.objectContaining({
        kind: 'cumulative',
        metric: 'orders',
        values: [4, 12, 12, 14, 20],
      }),
    ]);
    expect(data.gaps).toBeUndefined();
  });

  it('averages a window of points and draws nothing before it fills', () => {
    expect(derivedValues([1, 2, 3, 4, 5], 'moving-average', 3)).toEqual([
      null,
      null,
      2,
      3,
      4,
    ]);
    const data = shaped(
      config({
        derived: [{ kind: 'moving-average', metric: 'orders', window: 2 }],
      }),
    );
    expect(data.derived?.[0]).toMatchObject({
      window: 2,
      values: [null, 6, 4, 1, 4],
    });
  });

  it('fits the least-squares line through the points', () => {
    expect(derivedValues([1, 3, 5, 7], 'trend', 0)).toEqual([1, 3, 5, 7]);
    expect(derivedValues([2, 2, 2], 'trend', 0)).toEqual([2, 2, 2]);
    const [first, , , , last] = derivedValues(
      [4, 8, 0, 2, 6],
      'trend',
      0,
    ) as number[];
    // Mean 4, slope -0.2: from 4.4 down to 3.6.
    expect(first).toBeCloseTo(4.4);
    expect(last).toBeCloseTo(3.6);
  });

  it('takes a week of days, a quarter of months, three of anything else by default', () => {
    expect(movingWindow(config(), {})).toBe(7);
    expect(movingWindow(config(), { window: 5 })).toBe(5);
    const monthly = config({}, { groups: [{ ...daily, unit: 'MONTH' }] });
    expect(movingWindow(monthly, {})).toBe(3);
    const yearly = config({}, { groups: [{ ...daily, unit: 'YEAR' }] });
    expect(movingWindow(yearly, {})).toBe(3);
  });

  describe('derivedGap — every reason, and the whole case', () => {
    const cumulative = { kind: 'cumulative' as const, metric: 'orders' };
    const gap = (cfg: AnalysisViewConfig, from = rows, cutShort = false) =>
      derivedGap(cfg, shaped(cfg, from), cumulative, cutShort);

    it('draws over rows that are whole', () => {
      expect(gap(config())).toBeUndefined();
    });

    it('refuses a split', () => {
      const cfg = config({ splitBy: 'wh' }, { groups: [daily, region] });
      expect(gap(cfg, [{ day: day(1), wh: 'e', orders: 1 }])).toBe('split');
    });

    it('refuses an axis that is not time unless it is sorted by the metric', () => {
      const cfg = config({}, { groups: [region] });
      // A running total along categories is a Pareto only in the metric's
      // order (D38); in any other order it is a sum in an order nobody chose.
      expect(gap(cfg, [{ wh: 'e', orders: 1 }])).toBe('not-sorted');
      // A trend and a moving average need a time axis whatever the order.
      expect(
        derivedGap(
          cfg,
          shaped(cfg, [{ wh: 'e', orders: 1 }]),
          { kind: 'trend', metric: 'orders' },
          false,
        ),
      ).toBe('not-time');
    });

    it('refuses shares of a 100% stack', () => {
      const cfg = config(
        { series: [{ metric: 'orders', stack: 'all' }], percentStack: true },
        { type: 'bar' },
      );
      expect(gap(cfg)).toBe('shares');
    });

    it('refuses rows 「只保留」 narrowed', () => {
      const cfg = config(
        {},
        {
          having: {
            op: 'and',
            children: [{ field: 'orders', operator: 'GT', value: 1 }],
          } as unknown as AnalysisViewConfig['having'],
        },
      );
      expect(gap(cfg)).toBe('narrowed');
    });

    it('refuses rows cut short, and the chart says at how many groups', () => {
      expect(gap(config(), rows, true)).toBe('cut-short');
      const data = shaped(config({ derived: [cumulative] }), rows, true);
      expect(data.derived).toBeUndefined();
      expect(data.gaps).toEqual([
        { ...cumulative, gap: 'cut-short', limit: 100 },
      ]);
    });

    it('refuses a hole nobody can put a number on', () => {
      // An average of a day with no rows is no number: the hole stays null.
      const cfg = config({}, { metrics: [average] });
      const data = shaped(cfg, [
        { day: day(1), avg: 4 },
        { day: day(3), avg: 2 },
      ]);
      expect(
        derivedGap(cfg, data, { kind: 'trend', metric: 'avg' }, false),
      ).toBe('holes');
      // Asked to leave gaps, even a count's hole is one.
      const gapped = config({ missing: 'gap' });
      expect(gap(gapped)).toBe('holes');
    });

    it('refuses a running total of a metric that does not add up', () => {
      const cfg = config({}, { metrics: [average] });
      const data = shaped(cfg, [
        { day: day(1), avg: 4 },
        { day: day(2), avg: 2 },
      ]);
      expect(
        derivedGap(cfg, data, { kind: 'cumulative', metric: 'avg' }, false),
      ).toBe('not-additive');
      expect(
        derivedGap(cfg, data, { kind: 'trend', metric: 'avg' }, false),
      ).toBeUndefined();
    });

    it('refuses too few points', () => {
      const cfg = config();
      const one = shaped(cfg, [{ day: day(1), orders: 1 }]);
      expect(
        derivedGap(cfg, one, { kind: 'trend', metric: 'orders' }, false),
      ).toBe('too-few');
      expect(
        derivedGap(
          cfg,
          shaped(cfg),
          { kind: 'moving-average', metric: 'orders' },
          false,
        ),
      ).toBe('too-few');
    });
  });

  it('is told the result was cut short by the projection', () => {
    // Three rows for a limit of two: the probe came back, more groups exist.
    const cfg = {
      ...config({ derived: [{ kind: 'cumulative', metric: 'orders' }] }),
      limit: 2,
    };
    const view = projectAnalysis(
      ordersDefinition(),
      cfg,
      rows.slice(0, 3),
      undefined,
      undefined,
      { timeZone: 'UTC' },
    );
    expect(view.truncated).toBe(true);
    expect((view.chart as CartesianData).gaps).toEqual([
      { kind: 'cumulative', metric: 'orders', gap: 'cut-short', limit: 2 },
    ]);
  });
});

describe('validateChart — batch B rules, each with a sound counterpart', () => {
  const codes = (cartesian: Partial<CartesianSpec>) =>
    validateChart(config(cartesian)).map(issue => issue.code);

  it('takes sound reference lines, bands and derived series', () => {
    expect(
      codes({
        referenceLines: [
          { axis: 'left', value: 3 },
          { axis: 'left', statistic: 'median', metric: 'orders' },
        ],
        referenceBands: [{ axis: 'left', from: 1, to: 5, label: '目标' }],
        extremes: true,
        derived: [
          { kind: 'trend', metric: 'orders' },
          { kind: 'moving-average', metric: 'orders', window: 7 },
        ],
      }),
    ).toEqual([]);
  });

  it('refuses a line at neither a number nor a statistic', () => {
    expect(codes({ referenceLines: [{ axis: 'left' }] })).toEqual([
      'chart.referenceLine.value-missing',
    ]);
  });

  it('refuses an unknown statistic, and one of a metric not drawn on its axis', () => {
    expect(
      codes({
        referenceLines: [
          {
            axis: 'left',
            statistic: 'mode' as 'average',
            metric: 'orders',
          },
        ],
      }),
    ).toEqual(['chart.referenceLine.statistic-unknown']);
    expect(
      codes({
        referenceLines: [
          { axis: 'left', statistic: 'average', metric: 'missing' },
        ],
      }),
    ).toEqual(['chart.referenceLine.metric-not-drawn']);
  });

  it('refuses a band that does not run upward, or hangs on an empty axis', () => {
    expect(
      codes({ referenceBands: [{ axis: 'left', from: 5, to: 5 }] }),
    ).toEqual(['chart.referenceBand.order']);
    expect(
      codes({ referenceBands: [{ axis: 'right', from: 1, to: 5 }] }),
    ).toEqual(['chart.referenceLine.empty-axis']);
  });

  it('refuses an unknown derived kind, a metric not drawn, a window out of range', () => {
    expect(
      codes({ derived: [{ kind: 'forecast' as 'trend', metric: 'orders' }] }),
    ).toEqual(['chart.derived.kind-unknown']);
    expect(codes({ derived: [{ kind: 'trend', metric: 'nope' }] })).toEqual([
      'chart.derived.metric-not-drawn',
    ]);
    expect(
      codes({
        derived: [{ kind: 'moving-average', metric: 'orders', window: 1 }],
      }),
    ).toEqual(['chart.derived.window']);
    expect(
      codes({ derived: [{ kind: 'trend', metric: 'orders', window: 3 }] }),
    ).toEqual(['chart.derived.window']);
  });
});

describe('fitChartSlots keeps what still names a drawn metric', () => {
  it('drops statistic lines and derived series whose metric left, keeps the rest', () => {
    const cfg = config(
      {
        referenceLines: [
          { axis: 'left', value: 1 },
          { axis: 'left', statistic: 'average', metric: 'gone' },
        ],
        referenceBands: [{ axis: 'left', from: 0, to: 2 }],
        extremes: true,
        derived: [
          { kind: 'trend', metric: 'orders' },
          { kind: 'cumulative', metric: 'gone' },
        ],
      },
      { metrics: [count] },
    );
    const fitted = fitChartSlots(cfg.chart, cfg.groups, cfg.metrics);
    expect(fitted.cartesian).toMatchObject({
      referenceLines: [{ axis: 'left', value: 1 }],
      referenceBands: [{ axis: 'left', from: 0, to: 2 }],
      extremes: true,
      derived: [{ kind: 'trend', metric: 'orders' }],
    });
  });
});
