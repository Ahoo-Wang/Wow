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
import type { CartesianData, ChartSpec, MetricCardData } from '../src/index.js';
import { cartesianFit } from '../src/ui/charts/cartesianFit.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import type { FilledNote } from '../src/ui/charts/family.js';
import { readChart } from '../src/ui/charts/reading.js';
import { sparklineOption } from '../src/ui/charts/sparklineOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import { zhCN } from '../src/ui/index.js';
import { defaultMessages, formatMessage } from '../src/ui/messages.js';

/**
 * A 0 the chart filled in — a day the rows lack, a split combination they
 * lack — is not a value anyone measured (decisions.md D23, Q14, the user's
 * ruling as recommended). It stays a mark: the line drops to it, the bar
 * stands at nothing. It writes no value label, since a screen of 「0」
 * buried the bars that have a number, and its tooltip and the reading table
 * say what it is: 「0（这一天没有记录）」, in the unit's own words, and
 * 「0（这一组没有记录）」 for a combination along categories.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const theme: ChartTheme = {
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
  key: 't',
  resolve: () => 'rgb(30, 60, 160)',
};

/** The catalogue's own wording, by the unit the axis carries. */
const note =
  (unit: string | undefined): FilledNote =>
  (_alias, value) =>
    formatMessage(
      zhCN,
      unit === undefined
        ? 'label.chart.filled.group'
        : (`label.chart.filled.${unit}` as never),
      { value },
    );

const daily: CartesianData = {
  type: 'cartesian',
  chart: 'bar',
  points: [
    { x: 'd1', values: { SH: 4, BJ: 0 }, filled: ['BJ'] },
    { x: 'd2', values: { SH: 0, BJ: 0 }, filled: ['SH', 'BJ'] },
    { x: 'd3', values: { SH: 0, BJ: 3 } },
  ],
  series: [
    { key: 'SH', label: 'SH', metric: 'orders', value: 'SH' },
    { key: 'BJ', label: 'BJ', metric: 'orders', value: 'BJ' },
  ],
};

const context = (spec: ChartSpec): CartesianContext => ({
  spec,
  label: (_alias, value) => String(value),
  column: alias => alias,
  locale: 'zh-CN',
  animate: false,
  pickable: false,
  filled: note('DAY'),
});

const split = (stack?: string): ChartSpec => ({
  type: 'bar',
  labels: true,
  cartesian: {
    x: 'day',
    splitBy: 'wh',
    series: [{ metric: 'orders', ...(stack ? { stack } : {}) }],
  },
});

describe('a filled-in 0 on a bar chart', () => {
  const option = cartesianOption(daily, context(split()), theme) as Loose;
  const [sh, bj] = option.series;

  it('stays a mark at nothing', () => {
    expect(sh.data).toEqual([4, 0, 0]);
    expect(bj.data).toEqual([0, 0, 3]);
  });

  it('writes no label, where a measured 0 still writes one', () => {
    expect(bj.label.formatter({ value: 0, dataIndex: 0 })).toBe('');
    expect(sh.label.formatter({ value: 0, dataIndex: 1 })).toBe('');
    // The 3rd day measured SH at 0: that is a number the source gave.
    expect(sh.label.formatter({ value: 0, dataIndex: 2 })).toBe('0');
    expect(sh.label.formatter({ value: 4, dataIndex: 0 })).toBe('4');
  });

  it('says in the tooltip that there were no records that day', () => {
    const html: string = option.tooltip.formatter([{ dataIndex: 1 }]);
    expect(html).toContain('0（这一天没有记录）');
    const measured: string = option.tooltip.formatter([{ dataIndex: 2 }]);
    expect(measured).not.toContain('没有记录');
  });

  it('keeps no room for labels it will not write', () => {
    const plan = cartesianPlan(daily, context(split()));
    expect(plan.outerTexts).toEqual(['4', '', '0', '', '', '3']);
  });
});

describe('a filled-in 0 in a stack', () => {
  const plan = cartesianPlan(daily, context(split('s')));

  it('writes no part and no total over a stack of nothing measured', () => {
    expect(plan.insideText(plan.series[1], 0)).toBe('');
    expect(plan.totals[0].texts).toEqual(['4', '', '3']);
  });

  it('writes no part where the size alone would allow one', () => {
    const patch = cartesianFit(
      plan,
      1200,
      600,
      text => text.length * 7,
    ) as Loose;
    expect(patch.series[1].label.formatter({ dataIndex: 1 })).toBe('');
  });
});

describe('what the reading table says of a filled-in 0', () => {
  const messages = {
    label: (key: string, params?: Record<string, string | number>) =>
      formatMessage(defaultMessages, key as never, params),
    issue: () => '',
    issues: () => '',
  };

  it('says it as the tooltip does', () => {
    const reading = readChart(daily, split(), {
      messages,
      label: (_alias, value) => String(value),
      column: () => undefined,
      locale: 'en',
      filled: note('DAY'),
    });
    expect(reading.rows[1]).toEqual([
      'd2',
      '0（这一天没有记录）',
      '0（这一天没有记录）',
    ]);
    expect(reading.rows[2]).toEqual(['d3', '0', '3']);
  });

  it('says a combination along categories is a group with no records', () => {
    const reading = readChart(
      { ...daily, points: daily.points.slice(0, 1) },
      split(),
      {
        messages,
        label: (_alias, value) => String(value),
        column: () => undefined,
        locale: 'en',
        filled: note(undefined),
      },
    );
    expect(reading.rows[0]).toEqual(['d1', '4', '0（这一组没有记录）']);
  });
});

describe('a metric card’s trend', () => {
  const trend: NonNullable<MetricCardData['trend']> = [
    { x: 'm1', value: 3 },
    { x: 'm2', value: 0, filled: true },
  ];

  it('says a filled-in month in the tooltip, in the month’s words', () => {
    const option = sparklineOption(
      trend,
      {
        label: (_alias, value) => String(value),
        name: 'Trend',
        animate: false,
        filled: note('MONTH'),
      },
      theme,
    ) as Loose;
    expect(option.tooltip.formatter([{ dataIndex: 1 }])).toContain(
      '0（这个月没有记录）',
    );
    expect(option.tooltip.formatter([{ dataIndex: 0 }])).not.toContain(
      '没有记录',
    );
  });
});

describe('the words, in both catalogues', () => {
  it('has a phrase for every unit, and for a group', () => {
    for (const unit of [
      'YEAR',
      'QUARTER',
      'MONTH',
      'WEEK',
      'DAY',
      'HOUR',
      'MINUTE',
      'SECOND',
      'group',
    ]) {
      const key = `label.chart.filled.${unit}` as const;
      expect(zhCN[key]).toContain('{value}');
      expect(defaultMessages[key]).toContain('{value}');
    }
    expect(
      formatMessage(defaultMessages, 'label.chart.filled.WEEK', { value: '0' }),
    ).toBe('0 (no records this week)');
  });
});
