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
 * The metric one bucket and one year earlier, drawn as computed lines
 * (D88): the source computes each as a hidden `PREVIOUS` metric over the
 * filter's range shifted back; the kernel never does, so without the window
 * the box is greyed as `unsupported`.
 */

import {
  AggregationDateUnit,
  DerivedExpressionType,
  type DerivedAggregationMetric,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  shapeChart,
  type AnalysisViewConfig,
  type CartesianData,
  type DerivedSeries,
  type RecordData,
} from '../src/index.js';
import { derivedValues } from '../src/analysis/derived.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import { cartesianTooltip } from '../src/ui/charts/cartesianTooltip.js';
import { gapReason, markWords } from '../src/ui/charts/markWords.js';
import { CHART_FALLBACK } from '../src/ui/charts/theme.js';
import { defaultMessages, formatMessage } from '../src/ui/kit/messages.js';
import {
  analysisCapability,
  analysisContext as context,
  analysisDefinition,
  analysisKernelConfig,
} from './fixtures/analysis.js';

const previousWindow = analysisDefinition({
  analysis: { ...analysisCapability, windows: ['PREVIOUS'] },
});

function timeline(
  derived: DerivedSeries[],
  unit: 'DAY' | 'WEEK' | 'MONTH' = 'MONTH',
): AnalysisViewConfig {
  return analysisKernelConfig({
    groups: [
      { type: 'DATE_HISTOGRAM', field: 'createdAt', alias: 'month', unit },
    ],
    sort: [],
    chart: {
      type: 'line',
      cartesian: { x: 'month', series: [{ metric: 'orders' }], derived },
    },
  });
}

const hidden = (config: AnalysisViewConfig, definition = previousWindow) =>
  compileAnalysis(definition, config, builtinFieldKinds, context)
    .metrics.slice(config.metrics.length)
    .map(metric => metric as DerivedAggregationMetric);

describe('the previous period and the same period last year', () => {
  it('are asked of the source as PREVIOUS along the date group', () => {
    const metrics = hidden(
      timeline([
        { kind: 'previous', metric: 'orders' },
        { kind: 'previous-year', metric: 'orders' },
      ]),
    );
    expect(metrics.map(metric => [metric.alias, metric.expression])).toEqual([
      [
        'orders__previous',
        {
          type: DerivedExpressionType.PREVIOUS,
          metric: 'orders',
          along: 'month',
        },
      ],
      [
        'orders__previousYear',
        {
          type: DerivedExpressionType.PREVIOUS,
          metric: 'orders',
          along: 'month',
          period: AggregationDateUnit.YEAR,
        },
      ],
    ]);
  });

  it('are not asked without the window, nor a year back over weeks', () => {
    expect(
      hidden(
        timeline([{ kind: 'previous', metric: 'orders' }]),
        analysisDefinition(),
      ),
    ).toEqual([]);
    expect(
      hidden(timeline([{ kind: 'previous-year', metric: 'orders' }], 'WEEK')),
    ).toEqual([]);
    expect(
      hidden(timeline([{ kind: 'previous', metric: 'orders' }], 'WEEK')),
    ).toHaveLength(1);
  });

  const MONTH = 2_678_400_000;
  const shaped = (
    config: AnalysisViewConfig,
    rows: RecordData[],
    cutShort = false,
  ) =>
    shapeChart(config, rows, undefined, {
      timeZone: 'UTC',
      cutShort,
    }) as CartesianData;

  it('are read off the rows, a bucket without an earlier value left out', () => {
    const config = timeline([{ kind: 'previous-year', metric: 'orders' }]);
    const data = shaped(
      config,
      [
        { month: 0, orders: 12, orders__previousYear: 10 },
        { month: MONTH, orders: 15, orders__previousYear: null },
      ],
      true,
    );
    expect(data.gaps).toBeUndefined();
    expect(data.derived?.[0]?.values).toEqual([10, null]);
  });

  it('are greyed as unsupported where the source computed none', () => {
    const data = shaped(timeline([{ kind: 'previous', metric: 'orders' }]), [
      { month: 0, orders: 12 },
    ]);
    expect(data.gaps).toEqual([
      { kind: 'previous', metric: 'orders', gap: 'unsupported' },
    ]);
    expect(
      gapReason(
        {
          label: (key: string, params?: Record<string, unknown>) =>
            formatMessage(defaultMessages, key as never, params as never),
        } as never,
        { kind: 'previous', gap: 'unsupported' },
      ),
    ).toContain('earlier periods');
    expect(derivedValues([1, 2], 'previous', 0)).toEqual([null, null]);
  });

  it('say in the tooltip how the metric moved from them', () => {
    const config = timeline([{ kind: 'previous-year', metric: 'orders' }]);
    const data = shaped(config, [
      { month: 0, orders: 12, orders__previousYear: 10 },
      { month: MONTH, orders: 15, orders__previousYear: 0 },
    ]);
    const words = markWords({
      label: (key: string, params?: Record<string, unknown>) =>
        formatMessage(defaultMessages, key as never, params as never),
    } as never);
    const plan = cartesianPlan(data, {
      spec: config.chart,
      label: (_alias, value) => String(value),
      column: alias => alias,
      locale: 'en-US',
      animate: false,
      pickable: false,
      words,
    } as CartesianContext);
    const tooltip = cartesianTooltip(plan, {
      ...CHART_FALLBACK,
      foreground: '#000',
      muted: '#666',
      ground: '#fff',
      palette: [],
      key: 't',
      resolve: () => '#000',
    }) as { formatter: (params: { dataIndex: number }[]) => string };
    expect(tooltip.formatter([{ dataIndex: 0 }])).toContain(
      'vs Same period last year: +20.0%',
    );
    // From 0 the change is the amount.
    expect(tooltip.formatter([{ dataIndex: 1 }])).toContain(
      'vs Same period last year: +15',
    );
  });
});

describe('the options page', () => {
  const MONTH = 2_678_400_000;
  it('judges a line the source would compute as computed by it', async () => {
    const { draftGap } = await import('../src/analysis/derived.js');
    const config = timeline([{ kind: 'cumulative', metric: 'orders' }]);
    const data = shapeChart(
      config,
      [
        { month: 0, orders: 1 },
        { month: MONTH, orders: 2 },
      ],
      undefined,
      {
        timeZone: 'UTC',
        cutShort: true,
      },
    ) as CartesianData;
    const cumulative = { kind: 'cumulative' as const, metric: 'orders' };
    const previous = { kind: 'previous' as const, metric: 'orders' };
    expect(draftGap(config, data, cumulative, true, [])).toBe('cut-short');
    expect(
      draftGap(config, data, cumulative, true, ['RUNNING']),
    ).toBeUndefined();
    expect(draftGap(config, data, previous, false, [])).toBe('unsupported');
    expect(
      draftGap(config, data, previous, false, ['PREVIOUS']),
    ).toBeUndefined();
  });
});
