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

import {
  AGGREGATION_LIMITS,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  fitCharts,
  shapeChart,
  validateChart,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type RecordData,
} from '../src/index.js';
import { OTHER_SERIES_KEY } from '../src/analysis/chartRows.js';
import { foldsSplit, splitWholeConfig } from '../src/analysis/splitOther.js';
import { dataViewRuntime } from '../src/runtime/recordRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import {
  analysisConfig,
  nextTask,
  ordersDefinition,
  testEnvironment,
  testSource,
} from './fixtures.js';

/**
 * A split past the palette (D33 Q56, settling Q9): a metric that adds up
 * draws its seven largest series and a grey 「其他」 measured against the
 * axis's whole, from one more query only when it is needed; one that does
 * not is drawn whole and says its colours repeat; and a pie is only ever of
 * a metric that adds up.
 */

const SUM: AnalysisMetric = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVG: AnalysisMetric = { ...SUM, function: 'AVG' } as AnalysisMetric;

function split(
  metric: AnalysisMetric = SUM,
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    groups: [
      { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
      { alias: 'status', field: 'status', type: 'TERMS' },
    ],
    metrics: [metric],
    sort: [{ alias: 'status', direction: 'ASC' }],
    layout: 'chart',
    chart: {
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        splitBy: 'status',
        series: [{ metric: 'amount' }],
      },
    },
    ...overrides,
  });
}

/** Two warehouses, `count` statuses each; status `s<i>` sums to i + 1. */
function rows(count: number): RecordData[] {
  return ['CN', 'US'].flatMap(warehouse =>
    Array.from({ length: count }, (_, index) => ({
      warehouse,
      status: `s${index}`,
      amount: index + 1,
    })),
  );
}

describe('foldsSplit', () => {
  it('asks only of a cartesian split whose metric adds up, past the palette', () => {
    expect(foldsSplit(split(), rows(9))).toBe(true);
    // Eight values each wear a colour of their own.
    expect(foldsSplit(split(), rows(8))).toBe(false);
    // An average of the rest is no average of anything.
    expect(foldsSplit(split(AVG), rows(9))).toBe(false);
    // 「只保留」 dropped groups by their numbers: the whole would count them.
    expect(
      foldsSplit(
        split(SUM, {
          having: {
            type: 'COMPARISON',
            operator: 'GT',
            left: { type: 'METRIC', alias: 'amount' },
            right: { type: 'LITERAL', value: 0 },
          } as never,
        }),
        rows(9),
      ),
    ).toBe(false);
    const unsplit = split();
    unsplit.chart = {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'amount' }] },
    };
    expect(foldsSplit(unsplit, rows(9))).toBe(false);
    expect(foldsSplit({ ...split(), chart: { type: 'pie' } }, rows(9))).toBe(
      false,
    );
  });
});

describe('splitWholeConfig', () => {
  it('groups by the axis alone and asks for every category', () => {
    const whole = splitWholeConfig(ordersDefinition(), split());
    expect(whole.groups.map(group => group.alias)).toEqual(['warehouse']);
    expect(whole.sort).toEqual([]);
    expect(whole.limit).toBe(AGGREGATION_LIMITS.MAX_LIMIT);
    expect(whole.table.totals).toBe(false);
  });
});

describe('shapeChart: a split past the palette', () => {
  const whole = [
    { warehouse: 'CN', amount: 100 },
    { warehouse: 'US', amount: 45 },
  ];

  it('draws the seven largest series, then 「其他」 as the whole less them', () => {
    const data = shapeChart(split(), rows(9), undefined, {
      splitWhole: whole,
    }) as CartesianData;

    expect(data.series).toHaveLength(8);
    // s2 … s8 are the largest; drawn in the order they came.
    expect(data.series.slice(0, 7).map(entry => entry.label)).toEqual([
      's2',
      's3',
      's4',
      's5',
      's6',
      's7',
      's8',
    ]);
    const other = data.series[7];
    expect(other).toMatchObject({ key: OTHER_SERIES_KEY, other: true });
    // 3 + … + 9 = 42 kept: CN's whole of 100 leaves 58, US's 45 leaves 3.
    expect(data.points.map(point => point.values[OTHER_SERIES_KEY])).toEqual([
      58, 3,
    ]);
    expect(data.crowded).toBeUndefined();
  });

  it('draws nothing for a rest it cannot know', () => {
    const data = shapeChart(split(), rows(9), undefined, {
      splitWhole: [{ warehouse: 'CN', amount: 100 }],
    }) as CartesianData;
    expect(data.points.map(point => point.values[OTHER_SERIES_KEY])).toEqual([
      58,
      null,
    ]);
  });

  it('draws every series of a metric that does not add up, and says so', () => {
    const data = shapeChart(split(AVG), rows(9)) as CartesianData;
    expect(data.series).toHaveLength(9);
    expect(data.series.some(entry => entry.other)).toBe(false);
    expect(data.crowded).toBe(true);
  });

  it('draws every series where the whole never came', () => {
    const data = shapeChart(split(), rows(9)) as CartesianData;
    expect(data.series).toHaveLength(9);
    expect(data.crowded).toBe(true);
  });
});

describe('a pie of a metric that does not add up (Q9)', () => {
  it('is greyed with why, and refused when saved', () => {
    const fits = fitCharts({
      groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
      metrics: [AVG],
    });
    expect(fits.pie).toEqual({
      available: false,
      reason: 'chart.fit.needs-share',
    });
    const pie = analysisConfig({
      metrics: [AVG],
      chart: { type: 'pie', pie: { category: 'warehouse', value: 'amount' } },
    });
    expect(validateChart(pie).map(found => found.code)).toEqual([
      'chart.pie.not-additive',
    ]);
  });
});

describe('the runtime asks for the whole only when a split needs it', () => {
  function run(config: AnalysisViewConfig, answer: RecordData[]) {
    const aggregate = vi
      .fn()
      .mockResolvedValueOnce(answer)
      .mockResolvedValueOnce([{ warehouse: 'CN', amount: 100 }]);
    const runtime = dataViewRuntime({
      id: 'split-1',
      definition: ordersDefinition({
        analysis: {
          count: true,
          fields: [
            {
              field: 'warehouse',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            {
              field: 'status',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            {
              field: 'amount',
              groups: [],
              functions: [AggregationFunction.SUM],
            },
          ],
        },
      }),
      config,
      title: 'Split',
      scope: 'personal',
      saved: null,
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: testEnvironment().environment,
      source: testSource({ aggregate }),
      runner: new RequestRunner(),
    });
    return { runtime, aggregate };
  }

  it('runs one more query, grouped by the axis, past the palette', async () => {
    const { runtime, aggregate } = run(split(), rows(9).slice(0, 9));
    runtime.apply();
    await nextTask();
    await nextTask();

    expect(runtime.getSnapshot().issues).toEqual([]);
    expect(aggregate).toHaveBeenCalledTimes(2);
    const second = aggregate.mock.calls[1][0] as { groupBy?: unknown[] };
    expect(second.groupBy).toHaveLength(1);
    const data = runtime.getSnapshot().result?.data;
    if (data?.kind !== 'analysis') throw new Error('expected an analysis');
    expect(data.view.splitWhole).toEqual([{ warehouse: 'CN', amount: 100 }]);
    const chart = data.view.chart as CartesianData;
    expect(chart.series[chart.series.length - 1]?.other).toBe(true);
  });

  it('runs no second query within the palette', async () => {
    const { runtime, aggregate } = run(split(), rows(8));
    runtime.apply();
    await nextTask();
    await nextTask();
    expect(aggregate).toHaveBeenCalledTimes(1);
  });
});
