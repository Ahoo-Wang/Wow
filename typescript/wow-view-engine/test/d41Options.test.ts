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
import type {
  BoxplotData,
  CalendarData,
  ThemeRiverData,
  HierarchyData,
  ParallelData,
  RadarData,
  SankeyData,
} from '../src/analysis/index.js';
import type { ChartSpec } from '../src/model/index.js';
import { boxplotOption } from '../src/ui/charts/boxplotOption.js';
import {
  sankeyOption,
  sunburstOption,
  treeOption,
} from '../src/ui/charts/hierarchyOption.js';
import { parallelOption, radarOption } from '../src/ui/charts/profileOption.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';
import {
  calendarOption,
  themeRiverOption,
} from '../src/ui/charts/timeOption.js';

/**
 * What the D41 families' options say where the library asks them — the
 * tooltip, an axis tick, a label — read straight off the option, since a
 * jsdom chart raises no tooltip. Every tooltip is the registry's markup,
 * its swatch an SVG `fill` and never an inline style (CSP, #3410).
 */
const theme: ChartTheme = {
  ...CHART_FALLBACK,
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  axis: { color: 'rgb(115, 115, 115)' },
  grid: { ...CHART_FALLBACK.grid, color: 'rgb(229, 229, 229)' },
  ground: 'rgb(255, 255, 255)',
  text: { ...CHART_FALLBACK.text, family: 'Geist' },
  key: 'test',
  resolve: color =>
    ({
      'var(--chart-1)': 'rgb(38, 117, 211)',
      'var(--chart-2)': 'rgb(235, 104, 52)',
    })[color] ?? 'rgb(0, 131, 0)',
};

const label = (_alias: string | undefined, value: unknown, compact?: boolean) =>
  `${compact ? '~' : ''}${String(value)}`;
const column = (alias: string | undefined) => alias && `title:${alias}`;
const base = { label, column, animate: false, pickable: true };

type Formatter = (params: unknown) => string;
const tooltipOf = (option: Record<string, unknown>) =>
  (option.tooltip as { formatter: Formatter }).formatter;
const noInlineStyle = (html: string) => expect(html).not.toContain('style=');

describe('the D41 options say what the library asks', () => {
  it('a boxplot: each number by its column, highest first; the ticks short', () => {
    const data: BoxplotData = {
      type: 'boxplot',
      boxes: [{ group: 'CN', low: 1, q1: 2, median: 3, q3: 4, high: 5 }],
      omitted: 0,
      approximate: true,
    };
    const spec: ChartSpec = {
      type: 'boxplot',
      boxplot: {
        category: 'w',
        low: 'min',
        q1: 'p25',
        median: 'p50',
        q3: 'p75',
        high: 'max',
      },
    };
    const option = boxplotOption(
      data,
      { ...base, spec, highlight: row => row.w === 'US' },
      theme,
    );
    const html = tooltipOf(option)({ dataIndex: 0 });
    expect(html.indexOf('title:max')).toBeLessThan(html.indexOf('title:min'));
    noInlineStyle(html);
    expect(tooltipOf(option)({ dataIndex: 9 })).toBe('');
    const y = option.yAxis as { axisLabel: { formatter: Formatter } };
    expect(y.axisLabel.formatter(3)).toBe('~3');
    const x = option.xAxis as { axisLabel: { formatter: Formatter } };
    expect(x.axisLabel.formatter('x'.repeat(40))).toHaveLength(24);
    // A highlight on a group not drawn fades nothing.
    const series = option.series as { data: object[] }[];
    expect(series[0]!.data[0]).not.toHaveProperty('itemStyle');
  });

  it('a radar and parallel axes: every axis in the tooltip, short ticks', () => {
    const profiles = [
      { group: 'CN', values: [1, -2, 3] },
      { group: 'US', values: [3, 2, 1] },
    ];
    const radar: RadarData = {
      type: 'radar',
      metrics: ['a', 'b', 'c'],
      profiles,
      omitted: 0,
    };
    const radarSpec: ChartSpec = {
      type: 'radar',
      radar: { category: 'w', metrics: ['a', 'b', 'c'] },
    };
    const lit = { highlight: (row: Record<string, unknown>) => row.w === 'CN' };
    const option = radarOption(
      radar,
      { ...base, spec: radarSpec, ...lit },
      theme,
    );
    const html = tooltipOf(option)({ dataIndex: 1 });
    for (const title of ['title:a', 'title:b', 'title:c'])
      expect(html).toContain(title);
    noInlineStyle(html);
    expect(tooltipOf(option)({ dataIndex: 5 })).toBe('');
    // An axis runs from a round number under a negative value.
    const indicator = (option.radar as { indicator: { min: number }[] })
      .indicator;
    expect(indicator.map(axis => axis.min)).toEqual([0, -2, 0]);
    const parallel: ParallelData = {
      type: 'parallel',
      metrics: ['a', 'b', 'c'],
      profiles,
      omitted: 0,
    };
    const parallelSpec: ChartSpec = {
      type: 'parallel',
      parallel: { category: 'w', metrics: ['a', 'b', 'c'] },
    };
    const lines = parallelOption(
      parallel,
      { ...base, spec: parallelSpec, ...lit },
      theme,
    );
    expect(tooltipOf(lines)({ dataIndex: 0 })).toContain('CN');
    const axes = lines.parallelAxis as {
      axisLabel: { formatter: Formatter };
    }[];
    expect(axes[0]!.axisLabel.formatter(2)).toBe('~2');
  });

  const hierarchy = (type: 'sunburst' | 'tree'): HierarchyData => ({
    type,
    depth: 2,
    omitted: 0,
    nodes: [
      {
        group: 'A',
        value: 3,
        path: { c: 'A' },
        children: [
          { group: 'a1', value: 2, path: { c: 'A', s: 'a1' } },
          { group: 'a2', value: 1, path: { c: 'A', s: 'a2' } },
        ],
      },
    ],
  });

  it('a sunburst and a tree: a part by its path and share, labels short', () => {
    for (const type of ['sunburst', 'tree'] as const) {
      const spec: ChartSpec = {
        type,
        [type]: { levels: ['c', 's'], value: 'v' },
      };
      const make = type === 'sunburst' ? sunburstOption : treeOption;
      const option = make(
        hierarchy(type),
        { ...base, spec, highlight: row => row.s === 'a2' },
        theme,
      );
      const html = tooltipOf(option)({ data: { id: 'h1' } });
      expect(html).toContain('A · a1');
      expect(html).toContain('2 · 66.7%');
      noInlineStyle(html);
      // The tree's root the library needs is no part.
      expect(tooltipOf(option)({ data: { id: 'root' } })).toBe('');
      expect(tooltipOf(option)({ data: undefined })).toBe('');
    }
    const tree = treeOption(
      hierarchy('tree'),
      {
        ...base,
        spec: { type: 'tree', tree: { levels: ['c', 's'], value: 'v' } },
      },
      theme,
    );
    const series = (tree.series as { label: { formatter: Formatter } }[])[0]!;
    expect(series.label.formatter({ data: { id: 'h2' } })).toBe('a2  ~1');
    expect(series.label.formatter({ data: {} })).toBe('');
  });

  it('a sankey: a band from and to, a node by its level’s column', () => {
    const data: SankeyData = {
      type: 'sankey',
      nodes: [
        { level: 0, group: 'app', value: 5 },
        { level: 1, group: 'card', value: 5 },
      ],
      links: [{ from: 0, to: 1, value: 5 }],
      omitted: 0,
    };
    const spec: ChartSpec = {
      type: 'sankey',
      sankey: { levels: ['channel', 'pay'], value: 'v' },
    };
    const option = sankeyOption(
      data,
      { ...base, spec, highlight: row => row.channel === 'web' },
      theme,
    );
    const tooltip = tooltipOf(option);
    expect(tooltip({ dataType: 'edge', dataIndex: 0 })).toContain('app → card');
    expect(tooltip({ dataType: 'node', dataIndex: 1 })).toContain('title:pay');
    expect(tooltip({ dataType: 'edge', dataIndex: 4 })).toBe('');
    expect(tooltip({ dataType: 'node', dataIndex: 4 })).toBe('');
    noInlineStyle(tooltip({ dataType: 'edge', dataIndex: 0 }));
    const series = (option.series as { label: { formatter: Formatter } }[])[0]!;
    expect(series.label.formatter({ dataIndex: 0 })).toBe('app  ~5');
  });

  it('a calendar and a river: a day by its bucket, a bucket by every stream', () => {
    const days: CalendarData = {
      type: 'calendar',
      days: [
        { at: 'd1', date: '2026-09-01', value: 4 },
        { at: 'd2', date: '2026-09-02', value: 9 },
      ],
      years: [2026],
      low: 4,
      high: 9,
    };
    const calendar = calendarOption(
      days,
      {
        ...base,
        spec: { type: 'calendar', calendar: { date: 'day', value: 'v' } },
        locale: 'zh-CN',
        other: 'Other',
        highlight: row => row.day === 'd2',
      },
      theme,
    );
    const html = tooltipOf(calendar)({ data: { id: 'd1' } });
    expect(html).toContain('d2');
    expect(html).toContain('title:v');
    noInlineStyle(html);
    expect(tooltipOf(calendar)({ data: { id: 'x' } })).toBe('');
    // The day not pressed on a board is faint.
    const cells = (calendar.series as { data: object[] }[])[0]!.data;
    expect(cells[0]).toHaveProperty('itemStyle');
    expect(cells[1]).not.toHaveProperty('itemStyle');
    const river: ThemeRiverData = {
      type: 'themeRiver',
      times: ['t0', 't1'],
      streams: [
        { key: 'app', value: 'app' },
        { key: '\u0001o', other: true },
      ],
      values: [
        [3, 1],
        [4, 2],
      ],
      uncertain: 0,
    };
    const option = themeRiverOption(
      river,
      {
        ...base,
        spec: {
          type: 'themeRiver',
          themeRiver: { x: 'week', splitBy: 'channel', value: 'v' },
        },
        other: 'Other',
        highlight: row => row.channel === 'web',
      },
      theme,
    );
    const at = tooltipOf(option)([{ value: [1, 4, 'app'] }]);
    expect(at).toContain('t1');
    expect(at).toContain('Other');
    noInlineStyle(at);
    expect(tooltipOf(option)([])).toBe('');
    const axis = option.singleAxis as { axisLabel: { formatter: Formatter } };
    expect(axis.axisLabel.formatter(1)).toBe('t1');
    expect(axis.axisLabel.formatter(0.5)).toBe('');
  });
});
