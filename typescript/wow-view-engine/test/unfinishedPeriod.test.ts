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
  shapeChart,
  type AnalysisGroup,
  type AnalysisViewConfig,
  type CalendarData,
  type CartesianData,
  type ChartData,
  type ChartSpec,
  type RecordData,
  type ThemeRiverData,
} from '../src/index.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import { markWords } from '../src/ui/charts/markWords.js';
import { readChart } from '../src/ui/charts/reading.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';
import {
  calendarOption,
  themeRiverOption,
} from '../src/ui/charts/timeOption.js';
import type { defaultMessages } from '../src/ui/kit/messages.js';
import { formatMessage } from '../src/ui/kit/messages.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { analysisConfig } from './fixtures.js';

/**
 * The period still under way at the end of a time axis (second review
 * R2-P1-7, user ruling 2026-09-26: drawn, marked, read for nothing): the
 * twenty-two days of September so far are no month, and a last bar, a
 * river's end or the lowest day read from them was a fall that was not
 * there. A metric card already leaves it out of its headline; a chart now
 * draws it under 「进行中」 and its sentence says it left it out.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const MONTH: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'paidAt',
  alias: 'month',
  unit: 'MONTH',
};

const month = (n: number) => Date.UTC(2026, n - 1, 1);
const NOW = new Date('2026-09-22T10:00:00Z');

const monthly = (chart: AnalysisViewConfig['chart']): AnalysisViewConfig =>
  analysisConfig({ groups: [MONTH], layout: 'chart', chart });

const BAR: AnalysisViewConfig['chart'] = {
  type: 'bar',
  cartesian: { x: 'month', series: [{ metric: 'orders' }] },
};

const ROWS: RecordData[] = [
  { month: month(7), orders: 300 },
  { month: month(8), orders: 320 },
  { month: month(9), orders: 90 },
];

const catalogue = (messages: typeof defaultMessages) => ({
  label: (key: string, params?: Record<string, string | number>) =>
    formatMessage(messages, key as never, params),
  say: (value: string) => value,
  issue: () => '',
  issues: () => '',
});

const name = (value: unknown) =>
  typeof value === 'number'
    ? `${new Date(value).getUTCMonth() + 1}月`
    : String(value);

const sentence = (data: ChartData, spec: ChartSpec) =>
  readChart(data, spec, {
    messages: catalogue(zhCN),
    label: (alias, value) => (alias === 'month' ? name(value) : String(value)),
    column: alias => alias,
    locale: 'zh-CN',
  }).sentence;

const THEME: ChartTheme = {
  ...CHART_FALLBACK,
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  ground: 'rgb(255, 255, 255)',
  key: 'light',
  resolve: () => 'rgb(30, 60, 160)',
};

describe('the period under way at the end of a time axis', () => {
  it('is named when the last bucket had not ended when asked, and only then', () => {
    const shape = (now?: Date) =>
      shapeChart(monthly(BAR), ROWS, undefined, {
        timeZone: 'UTC',
        now,
      }) as CartesianData;
    expect(shape(NOW).unfinished).toEqual({ at: month(9) });
    expect(shape(new Date('2026-10-01T00:00:00Z')).unfinished).toBeUndefined();
    // Asked with no time, every bucket counts as ended.
    expect(shape().unfinished).toBeUndefined();
  });

  it('is left out of the sentence, which says so', () => {
    const data = shapeChart(monthly(BAR), ROWS, undefined, {
      timeZone: 'UTC',
      now: NOW,
    }) as CartesianData;
    // September's 90 is neither the lowest nor the end of the way it went.
    expect(sentence(data, monthly(BAR).chart)).toBe(
      '共 2 期，从 7月 到 8月，总体上升；最高 8月 320，最低 7月 300。9月还没结束，未计入。',
    );
  });

  it('is shaded under 「进行中」 behind its category, and so named in the tooltip', () => {
    const data = shapeChart(monthly(BAR), ROWS, undefined, {
      timeZone: 'UTC',
      now: NOW,
    }) as CartesianData;
    const option = cartesianOption(
      data,
      {
        spec: monthly(BAR).chart,
        label: (_alias, value) => name(value),
        column: alias => alias,
        locale: 'zh-CN',
        animate: false,
        pickable: false,
        words: markWords(catalogue(zhCN)),
      },
      THEME,
    ) as Loose;
    const bands = (option.series as Loose[]).filter(
      entry => entry.markArea !== undefined,
    );
    expect(bands).toHaveLength(1);
    expect(bands[0]!.markArea.data).toEqual([
      [{ xAxis: '9月', name: '进行中' }, { xAxis: '9月' }],
    ]);
    const html = option.tooltip.formatter([{ dataIndex: 2 }]) as string;
    expect(html).toContain('9月（进行中）');
    expect(option.tooltip.formatter([{ dataIndex: 1 }])).not.toContain(
      '进行中',
    );
  });

  /**
   * The fit hands its caption placements to the series by place — the
   * marks, the totals, then the reference carriers (`cartesianFit`). The
   * band stood before the target band's carrier and took its placement,
   * and 「目标 ≤ 3%」 fell on 「最低 0%」 (CI on the first run of this).
   */
  it('stands after the reference carriers, whose places the fit writes by', () => {
    const chart: AnalysisViewConfig['chart'] = {
      type: 'bar',
      cartesian: {
        x: 'month',
        series: [{ metric: 'orders' }],
        referenceBands: [{ axis: 'left', from: 0, to: 100, label: '目标' }],
      },
    };
    const data = shapeChart(monthly(chart), ROWS, undefined, {
      timeZone: 'UTC',
      now: NOW,
    }) as CartesianData;
    const option = cartesianOption(
      data,
      {
        spec: monthly(chart).chart,
        label: (_alias, value) => name(value),
        column: alias => alias,
        locale: 'zh-CN',
        animate: false,
        pickable: false,
        words: markWords(catalogue(zhCN)),
      },
      THEME,
    ) as Loose;
    const series = option.series as Loose[];
    // One bar series, no totals: the band's carrier is next, the period last.
    expect(series[1]!.markArea.data[0][0].name).toBe('目标');
    expect(series[series.length - 1]!.markArea.data[0][0].name).toBe('进行中');
  });

  it('marks a calendar’s day under way and leaves it out of the lowest', () => {
    const data: CalendarData = {
      type: 'calendar',
      days: [
        { at: 'd20', date: '2026-09-20', value: 800 },
        { at: 'd21', date: '2026-09-21', value: 700 },
        { at: 'd22', date: '2026-09-22', value: 285 },
      ],
      years: [2026],
      low: 285,
      high: 800,
      unfinished: { at: 'd22' },
    };
    const spec: ChartSpec = {
      type: 'calendar',
      calendar: { date: 'day', value: 'v' },
    };
    expect(sentence(data, spec)).toBe(
      '共 2 组，最高 d20 800，最低 d21 700。d22还没结束，未计入。',
    );
    const option = calendarOption(
      data,
      {
        spec,
        label: (_alias, value) => String(value),
        column: alias => alias,
        animate: false,
        pickable: false,
        other: 'Other',
        ongoing: period => `${period}（进行中）`,
      },
      THEME,
    ) as Loose;
    const cells = (option.series as { data: Loose[] }[])[0]!.data;
    expect(cells[2]!.itemStyle).toMatchObject({ borderType: 'dashed' });
    expect(cells[1]).not.toHaveProperty('itemStyle');
    expect(option.tooltip.formatter({ data: { id: 'd2' } })).toContain(
      'd22（进行中）',
    );
  });

  it('names a river’s time under way on its axis and reads the way it went without it', () => {
    const data: ThemeRiverData = {
      type: 'themeRiver',
      times: ['w1', 'w2', 'w3', 'w4'],
      streams: [{ key: 'app', value: 'app' }],
      values: [[10], [12], [14], [1]],
      uncertain: 0,
      unfinished: { at: 'w4' },
    };
    const spec: ChartSpec = {
      type: 'themeRiver',
      themeRiver: { x: 'week', splitBy: 'channel', value: 'v' },
    };
    // The week of one day so far no longer turns the river down.
    expect(sentence(data, spec)).toContain('从 w1 到 w3，总体上升');
    expect(sentence(data, spec)).toContain('w4还没结束，未计入。');
    const option = themeRiverOption(
      data,
      {
        spec,
        label: (_alias, value) => String(value),
        column: alias => alias,
        animate: false,
        pickable: false,
        other: 'Other',
        ongoing: period => `${period}（进行中）`,
      },
      THEME,
    ) as Loose;
    expect(option.singleAxis.axisLabel.formatter(3)).toBe('w4（进行中）');
    expect(option.singleAxis.axisLabel.formatter(2)).toBe('w3');
  });
});
