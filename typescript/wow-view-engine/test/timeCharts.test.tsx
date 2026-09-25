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
  fitChartSlots,
  fitCharts,
  leadMetric,
  shapeChart,
  switchChartType,
  validateChart,
} from '../src/analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  ChartData,
  ChartSpec,
  RecordData,
} from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { analysisConfig } from './fixtures.js';

afterEach(cleanup);

const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'placedAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'Asia/Shanghai',
};
const MONTH: AnalysisGroup = { ...DAY, alias: 'month', unit: 'MONTH' };
const CHANNEL: AnalysisGroup = {
  type: 'TERMS',
  field: 'channel',
  alias: 'channel',
};
const GMV: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'gmv',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVG: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'avg',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

const config = (
  chart: ChartSpec,
  groups: AnalysisGroup[],
  metrics: AnalysisMetric[] = [GMV],
  overrides: Partial<AnalysisViewConfig> = {},
) =>
  analysisConfig({
    groups,
    metrics: metrics as [AnalysisMetric, ...AnalysisMetric[]],
    chart,
    ...overrides,
  });

const codes = (
  chart: ChartSpec,
  groups: AnalysisGroup[],
  metrics?: AnalysisMetric[],
) => validateChart(config(chart, groups, metrics)).map(issue => issue.code);

/** Midnight in Shanghai of a day: how a daily bucket comes back. */
const shanghai = (day: string) => Date.parse(`${day}T00:00:00+08:00`);

describe('the calendar heatmap (D41)', () => {
  it('fits one date dimension by day', () => {
    expect(fitCharts({ groups: [DAY], metrics: [GMV] }).calendar).toEqual({
      available: true,
    });
    expect(fitCharts({ groups: [MONTH], metrics: [GMV] }).calendar.reason).toBe(
      'chart.fit.needs-day',
    );
    expect(fitCharts({ groups: [], metrics: [GMV] }).calendar.reason).toBe(
      'chart.fit.needs-dimension',
    );
    expect(
      fitCharts({ groups: [DAY, CHANNEL], metrics: [GMV] }).calendar.reason,
    ).toBe('chart.fit.needs-one-dimension');
    // An average shades a day as well as a sum does.
    expect(
      fitCharts({ groups: [DAY], metrics: [AVG] }).calendar.available,
    ).toBe(true);
  });

  it('takes the day dimension, and refuses one that is not by day', () => {
    const chart = fitChartSlots({ type: 'calendar' }, [DAY], [GMV]);
    expect(chart.calendar).toEqual({ date: 'day', value: 'gmv' });
    expect(leadMetric(chart)).toBe('gmv');
    expect(codes(chart, [DAY])).toEqual([]);
    expect(
      codes({ type: 'calendar', calendar: { date: 'month', value: 'gmv' } }, [
        MONTH,
      ]),
    ).toEqual(['chart.calendar.needs-day']);
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'x', value: 'avg' } },
        'calendar',
      ).calendar,
    ).toEqual({ date: '', value: 'avg' });
  });

  it('writes each day in its zone, earliest first, and leaves days with no row out', () => {
    const data = shapeChart(
      config({ type: 'calendar', calendar: { date: 'day', value: 'gmv' } }, [
        DAY,
      ]),
      [
        { day: shanghai('2026-01-02'), gmv: 30 },
        { day: shanghai('2025-12-31'), gmv: 10 },
        { day: shanghai('2026-01-05'), gmv: null },
      ],
    );
    expect(data).toEqual({
      type: 'calendar',
      days: [
        { at: shanghai('2025-12-31'), date: '2025-12-31', value: 10 },
        { at: shanghai('2026-01-02'), date: '2026-01-02', value: 30 },
      ],
      years: [2025, 2026],
      low: 10,
      high: 30,
    });
    // A wall-clock day stays the day it says.
    const wall = shapeChart(
      config({ type: 'calendar', calendar: { date: 'day', value: 'gmv' } }, [
        DAY,
      ]),
      [{ day: '2026-03-08', gmv: 1 }],
    );
    expect(wall?.type === 'calendar' && wall.days[0]?.date).toBe('2026-03-08');
  });
});

describe('the theme river (D41)', () => {
  it('fits a date and one more dimension, and a metric that adds up', () => {
    expect(
      fitCharts({ groups: [MONTH, CHANNEL], metrics: [GMV] }).themeRiver,
    ).toEqual({ available: true });
    expect(
      fitCharts({ groups: [MONTH], metrics: [GMV] }).themeRiver.reason,
    ).toBe('chart.fit.needs-two-dimensions');
    expect(
      fitCharts({
        groups: [CHANNEL, { ...CHANNEL, alias: 'other' }],
        metrics: [GMV],
      }).themeRiver.reason,
    ).toBe('chart.fit.needs-date-and-split');
    expect(
      fitCharts({ groups: [MONTH, CHANNEL], metrics: [AVG] }).themeRiver.reason,
    ).toBe('chart.fit.needs-additive');
  });

  it('runs along the date, streams the other dimension, and refuses the rest', () => {
    const chart = fitChartSlots(
      { type: 'themeRiver' },
      [CHANNEL, MONTH],
      [AVG, GMV],
    );
    expect(chart.themeRiver).toEqual({
      x: 'month',
      splitBy: 'channel',
      value: 'gmv',
    });
    expect(leadMetric(chart)).toBe('gmv');
    expect(codes(chart, [CHANNEL, MONTH], [AVG, GMV])).toEqual([]);
    expect(
      codes(
        {
          type: 'themeRiver',
          themeRiver: { x: 'channel', splitBy: 'channel', value: 'avg' },
        },
        [CHANNEL, MONTH],
        [AVG, GMV],
      ),
    ).toEqual([
      'chart.themeRiver.needs-date',
      'chart.themeRiver.same-axes',
      'chart.themeRiver.not-additive',
      'chart.group.unconsumed',
    ]);
    expect(
      switchChartType({ type: 'bar' }, 'themeRiver').themeRiver,
    ).toBeUndefined();
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'channel', value: 'gmv' } },
        'themeRiver',
      ).themeRiver,
    ).toEqual({ x: '', splitBy: '', value: 'gmv' });
  });

  const months = ['2026-01-01', '2026-02-01', '2026-03-01'].map(shanghai);
  const river = (rows: RecordData[], overrides = {}) =>
    shapeChart(
      config(
        {
          type: 'themeRiver',
          themeRiver: { x: 'month', splitBy: 'channel', value: 'gmv' },
        },
        [MONTH, CHANNEL],
        [GMV],
        overrides,
      ),
      rows,
    );

  it('fills a known-empty point with 0 and counts a point it cannot know', () => {
    const rows = [
      { month: months[2], channel: 'app', gmv: 5 },
      { month: months[0], channel: 'app', gmv: 3 },
      { month: months[0], channel: 'web', gmv: 9 },
    ];
    // Every group there is: February had none, and nothing is guessed.
    expect(river(rows)).toEqual({
      type: 'themeRiver',
      times: months,
      streams: [
        { key: 'web', value: 'web' },
        { key: 'app', value: 'app' },
      ],
      values: [
        [9, 3],
        [0, 0],
        [0, 5],
      ],
      uncertain: 0,
    });
    // Groups kept by 「只保留」: a missing one may be any number.
    const kept = river(rows, {
      having: {
        op: 'and',
        children: [{ field: 'gmv', operator: 'GT', value: 0 }],
      },
    });
    expect(kept?.type === 'themeRiver' && kept.uncertain).toBe(3);
  });

  it('folds the smallest streams past the palette into one 「其他」', () => {
    const rows = Array.from({ length: 10 }, (_, at) => ({
      month: months[0],
      channel: `c${at}`,
      gmv: 10 - at,
    }));
    const data = river(rows);
    expect(data?.type === 'themeRiver' && data.streams).toHaveLength(8);
    expect(data?.type === 'themeRiver' && data.streams[7]).toMatchObject({
      other: true,
    });
    // The rest: 3 + 2 + 1.
    expect(data?.type === 'themeRiver' && data.values[0]?.[7]).toBe(6);
  });
});

const readingRows = (container: ParentNode) =>
  [
    ...(container
      .querySelector('[data-slot="chart-reading"] table')
      ?.querySelectorAll('tbody tr') ?? []),
  ].map(row => [...row.children].map(cell => cell.textContent));

async function drawn(data: ChartData, spec: ChartSpec) {
  const view = render(
    <ViewSurface>
      <AnalysisChart data={data} spec={spec} />
    </ViewSurface>,
  );
  const frame = await waitFor(() => {
    const found = view.container.querySelector<HTMLElement>(
      `[data-slot="chart"][data-chart="${data.type}"]`,
    );
    expect(found?.querySelector('[data-slot="chart-plot"] svg')).toBeTruthy();
    return found!;
  });
  return { ...view, frame };
}

describe('a calendar or a river drawn and read', () => {
  it('lays out a year of days and reads each', async () => {
    const { container, frame } = await drawn(
      {
        type: 'calendar',
        days: [
          { at: '2026-09-01', date: '2026-09-01', value: 4 },
          { at: '2026-09-02', date: '2026-09-02', value: 9 },
        ],
        years: [2026],
        low: 4,
        high: 9,
      },
      { type: 'calendar', calendar: { date: 'day', value: 'gmv' } },
    );
    expect(frame.getAttribute('data-years')).toBe('1');
    expect(frame.getAttribute('data-marks')).toBe('2');
    expect(readingRows(container)).toEqual([
      ['2026-09-01', '4'],
      ['2026-09-02', '9'],
    ]);
    expect(container.textContent).toContain('highest 2026-09-02, 9');
  });

  it('draws the streams with their legend, and says what it guessed', async () => {
    const { container, frame } = await drawn(
      {
        type: 'themeRiver',
        times: ['2026-01', '2026-02'],
        streams: [
          { key: 'web', value: 'web' },
          { key: 'app', value: 'app' },
        ],
        values: [
          [9, 3],
          [4, 5],
        ],
        uncertain: 2,
      },
      {
        type: 'themeRiver',
        themeRiver: { x: 'month', splitBy: 'channel', value: 'gmv' },
      },
    );
    expect(frame.getAttribute('data-marks')).toBe('2');
    expect(
      frame.querySelector('[data-slot="themeRiver-notes"]')?.textContent,
    ).toContain('2 points have no row');
    expect(
      frame.querySelector('[data-slot="chart-legend"]')?.textContent,
    ).toContain('app');
    expect(readingRows(container)).toEqual([
      ['2026-01', '9', '3'],
      ['2026-02', '4', '5'],
    ]);
    expect(container.textContent).toContain(
      '2 streams over 2 periods from 2026-01 to 2026-02',
    );
  });
});
