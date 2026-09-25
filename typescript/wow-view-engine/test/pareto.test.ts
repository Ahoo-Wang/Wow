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
 * A running total and a running share along categories (D38): the Pareto
 * chart — the biggest buyers first, bars of what each paid and a line of the
 * share the first N of them make up. It is drawn only where it is true: the
 * result sorted by the metric the line follows, and every group there (not
 * cut short, nothing 「只保留」 dropped, no group without a number) — the
 * same rule a running total along time keeps (Q53).
 */

import { describe, expect, it } from 'vitest';
import {
  DERIVED_KINDS,
  shapeChart,
  type AnalysisViewConfig,
  type CartesianData,
  type RecordData,
} from '../src/index.js';
import { derivedValues } from '../src/analysis/derived.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import { readChart } from '../src/ui/charts/reading.js';
import { cartesianTooltip } from '../src/ui/charts/cartesianTooltip.js';
import { defaultMessages, formatMessage } from '../src/ui/messages.js';

function pareto(
  overrides: Partial<AnalysisViewConfig> = {},
  kind: 'cumulative' | 'cumulative-share' = 'cumulative-share',
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [{ type: 'TERMS', field: 'buyer', alias: 'buyer' }],
    metrics: [
      {
        type: 'NUMERIC',
        alias: 'paid',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    sort: [{ alias: 'paid', direction: 'DESC' }],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: {
        x: 'buyer',
        series: [{ metric: 'paid' }],
        derived: [{ kind, metric: 'paid' }],
      },
    },
    ...overrides,
  };
}

const ROWS: RecordData[] = [
  { buyer: 'A', paid: 50 },
  { buyer: 'B', paid: 30 },
  { buyer: 'C', paid: 15 },
  { buyer: 'D', paid: 5 },
];

const shaped = (config: AnalysisViewConfig, rows = ROWS, cutShort = false) =>
  shapeChart(config, rows, undefined, {
    timeZone: 'UTC',
    cutShort,
  }) as CartesianData;

describe('a running share along a sorted categorical axis', () => {
  it('is one of the derived kinds', () => {
    expect(DERIVED_KINDS).toContain('cumulative-share');
  });

  it('is each group’s running total as a share of all of them', () => {
    expect(derivedValues([50, 30, 15, 5], 'cumulative-share', 0)).toEqual([
      0.5, 0.8, 0.95, 1,
    ]);
    const data = shaped(pareto());
    expect(data.gaps).toBeUndefined();
    expect(data.derived?.[0]?.values).toEqual([0.5, 0.8, 0.95, 1]);
  });

  it('draws the running total too, in the metric’s own numbers', () => {
    const data = shaped(pareto({}, 'cumulative'));
    expect(data.derived?.[0]?.values).toEqual([50, 80, 95, 100]);
  });

  it('sorted ascending, runs from the smallest', () => {
    const data = shaped(
      pareto({ sort: [{ alias: 'paid', direction: 'ASC' }] }),
      [...ROWS].reverse(),
    );
    expect(data.derived?.[0]?.values).toEqual([0.05, 0.2, 0.5, 1]);
  });

  it.each([
    [
      'not sorted by the metric',
      pareto({ sort: [] }),
      ROWS,
      false,
      'not-sorted',
    ],
    [
      'sorted by the dimension',
      pareto({ sort: [{ alias: 'buyer', direction: 'ASC' }] }),
      ROWS,
      false,
      'not-sorted',
    ],
    ['cut short at the first N', pareto(), ROWS, true, 'cut-short'],
    [
      'narrowed by 「只保留」',
      pareto({
        having: {
          type: 'CONDITION',
          metric: 'paid',
          operator: 'GT',
          value: 1,
        } as AnalysisViewConfig['having'],
      }),
      ROWS,
      false,
      'narrowed',
    ],
    [
      'with a group that has no number',
      pareto(),
      [...ROWS.slice(0, 3), { buyer: 'D', paid: null }],
      false,
      'holes',
    ],
  ] as const)(
    'is not drawn %s, and says why',
    (_what, config, rows, cut, gap) => {
      const data = shaped(config, [...rows], cut);
      expect(data.derived).toBeUndefined();
      expect(data.gaps).toEqual([
        expect.objectContaining({ kind: 'cumulative-share', gap }),
      ]);
    },
  );

  it('is not drawn over an average, which does not add', () => {
    const config = pareto({
      metrics: [
        {
          type: 'NUMERIC',
          alias: 'paid',
          function: 'AVG',
          expression: { type: 'FIELD', field: 'amount' },
        },
      ],
    });
    expect(shaped(config).gaps?.[0]?.gap).toBe('not-additive');
  });
});

describe('the share line as drawn', () => {
  const context: CartesianContext = {
    spec: pareto().chart,
    label: (_alias, value) => `¥${String(value)}`,
    column: alias => alias,
    locale: 'en-US',
    animate: false,
    pickable: false,
    words: {
      derived: line => line.kind,
      statistic: (_of, value) => value,
      high: 'high',
      low: 'low',
      other: 'Other',
    },
  };

  it('stands on an axis of its own, a scale of shares', () => {
    const plan = cartesianPlan(shaped(pareto()), context);
    expect(plan.derived[0]?.side).toBe('right');
    expect(plan.sharesOn('right')).toBe(true);
    expect(plan.sharesOn('left')).toBe(false);
  });

  it('reads as a share in the tooltip and the reading table', () => {
    const data = shaped(pareto());
    const plan = cartesianPlan(data, context);
    const tooltip = cartesianTooltip(plan, {
      foreground: '#000',
      muted: '#666',
      border: '#ccc',
      ground: '#fff',
      palette: [],
      fontFamily: 'sans-serif',
      key: 't',
      resolve: () => '#000',
    } as never) as {
      formatter: (params: { dataIndex: number }[]) => string;
    };
    expect(tooltip.formatter([{ dataIndex: 1 }])).toContain('80.0%');

    const reading = readChart(data, pareto().chart, {
      messages: {
        label: (key, params) => formatMessage(defaultMessages, key, params),
        issue: () => '',
        issues: () => '',
      },
      label: (_alias, value) => `¥${String(value)}`,
      column: alias => alias,
      locale: 'en-US',
    });
    expect(reading.rows.map(row => row[2])).toEqual([
      '50.0%',
      '80.0%',
      '95.0%',
      '100.0%',
    ]);
  });
});
