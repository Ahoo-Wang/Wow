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

import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  fitChartSlots,
  fitCharts,
  leadMetric,
  shapeChart,
  switchChartType,
  validateChart,
} from '../src/analysis/index.js';
import { chartLevels } from '../src/analysis/hierarchy.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartData,
  ChartSpec,
  RecordData,
} from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { analysisConfig } from './fixtures.js';

afterEach(cleanup);

const terms = (alias: string): AnalysisGroup => ({
  type: 'TERMS',
  field: alias,
  alias,
});
const CATEGORY = terms('category');
const SUB = terms('sub');
const CHANNEL = terms('channel');
const DAY: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'placedAt',
  alias: 'day',
  unit: 'DAY',
  timeZone: 'UTC',
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
  groups: AnalysisGroup[] = [CATEGORY, SUB],
  metrics: AnalysisMetric[] = [GMV],
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

const ROWS: RecordData[] = [
  { category: '家居', sub: '浴巾', gmv: 30 },
  { category: '家居', sub: '床品', gmv: 50 },
  { category: '厨具', sub: '锅', gmv: 40 },
  { category: '厨具', sub: '刀', gmv: 0 },
];

describe('the sunburst, the tree and the sankey (D41)', () => {
  it('fit two to four dimensions and a metric that adds up', () => {
    for (const type of ['sunburst', 'tree', 'sankey'] as const) {
      expect(
        fitCharts({ groups: [CATEGORY, SUB], metrics: [GMV] })[type],
      ).toEqual({ available: true });
      expect(
        fitCharts({ groups: [CATEGORY], metrics: [GMV] })[type].reason,
      ).toBe('chart.fit.needs-two-dimensions');
      expect(
        fitCharts({
          groups: [CATEGORY, SUB, CHANNEL, DAY, terms('x')],
          metrics: [GMV],
        })[type].reason,
      ).toBe('chart.fit.too-many-levels');
      expect(
        fitCharts({ groups: [CATEGORY, SUB], metrics: [AVG] })[type].reason,
      ).toBe('chart.fit.needs-additive');
    }
  });

  it('keeps the analyst’s level order, every dimension a level, and a size that adds', () => {
    expect(chartLevels(['sub', 'gone', 'sub'], ['category', 'sub'])).toEqual([
      'sub',
      'category',
    ]);
    expect(
      fitChartSlots(
        { type: 'sankey', sankey: { levels: ['sub'], value: 'avg' } },
        [CATEGORY, SUB],
        [AVG, GMV],
      ).sankey,
    ).toEqual({ levels: ['sub', 'category'], value: 'gmv' });
    const sunburst: ChartSpec = {
      type: 'sunburst',
      sunburst: { levels: ['category', 'sub'], value: 'gmv' },
    };
    expect(leadMetric(sunburst)).toBe('gmv');
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'category', value: 'orders' } },
        'tree',
      ).tree,
    ).toEqual({ levels: [], value: 'orders' });
    expect(switchChartType(sunburst, 'sankey').sankey).toEqual({
      levels: [],
      value: 'gmv',
    });
  });

  it('refuses one level, a level twice, five levels and a size that does not add', () => {
    expect(
      codes({
        type: 'tree',
        tree: { levels: ['category', 'sub'], value: 'gmv' },
      }),
    ).toEqual([]);
    expect(
      codes({ type: 'tree', tree: { levels: ['category'], value: 'gmv' } }),
    ).toEqual(['chart.tree.too-few-levels', 'chart.group.unconsumed']);
    expect(
      codes({
        type: 'sunburst',
        sunburst: { levels: ['category', 'category', 'sub'], value: 'gmv' },
      }),
    ).toEqual(['chart.sunburst.same-levels']);
    const five = ['a', 'b', 'c', 'd', 'e'].map(terms);
    expect(
      codes(
        {
          type: 'sankey',
          sankey: { levels: five.map(group => group.alias), value: 'gmv' },
        },
        five,
      ),
    ).toEqual(['chart.sankey.too-many-levels']);
    expect(
      codes(
        {
          type: 'sankey',
          sankey: { levels: ['category', 'sub'], value: 'avg' },
        },
        undefined,
        [AVG],
      ),
    ).toEqual(['chart.sankey.not-additive']);
  });

  it('builds a whole level by level, largest first, the parents the sums', () => {
    const data = shapeChart(
      config({
        type: 'sunburst',
        sunburst: { levels: ['category', 'sub'], value: 'gmv' },
      }),
      ROWS,
    );
    expect(data).toEqual({
      type: 'sunburst',
      depth: 2,
      omitted: 1,
      nodes: [
        {
          group: '家居',
          value: 80,
          path: { category: '家居' },
          children: [
            {
              group: '床品',
              value: 50,
              path: { category: '家居', sub: '床品' },
            },
            {
              group: '浴巾',
              value: 30,
              path: { category: '家居', sub: '浴巾' },
            },
          ],
        },
        {
          group: '厨具',
          value: 40,
          path: { category: '厨具' },
          children: [
            { group: '锅', value: 40, path: { category: '厨具', sub: '锅' } },
          ],
        },
      ],
    });
  });

  it('runs a date level forward', () => {
    const data = shapeChart(
      config({ type: 'tree', tree: { levels: ['day', 'sub'], value: 'gmv' } }, [
        DAY,
        SUB,
      ]),
      [
        { day: Date.UTC(2026, 0, 2), sub: 'a', gmv: 9 },
        { day: Date.UTC(2026, 0, 1), sub: 'a', gmv: 1 },
      ],
    );
    expect(data?.type === 'tree' && data.nodes.map(node => node.group)).toEqual(
      [Date.UTC(2026, 0, 1), Date.UTC(2026, 0, 2)],
    );
  });

  it('flows each row between neighbouring levels, a value on two levels two nodes', () => {
    const data = shapeChart(
      config(
        {
          type: 'sankey',
          sankey: { levels: ['channel', 'category', 'sub'], value: 'gmv' },
        },
        [CHANNEL, CATEGORY, SUB],
      ),
      [
        { channel: 'app', category: 'app', sub: 'x', gmv: 5 },
        { channel: 'web', category: 'app', sub: 'y', gmv: 3 },
        { channel: 'app', category: 'b', sub: 'x', gmv: 2 },
      ],
    );
    expect(data).toEqual({
      type: 'sankey',
      omitted: 0,
      nodes: [
        { level: 0, group: 'app', value: 7 },
        { level: 0, group: 'web', value: 3 },
        { level: 1, group: 'app', value: 8 },
        { level: 1, group: 'b', value: 2 },
        { level: 2, group: 'x', value: 7 },
        { level: 2, group: 'y', value: 3 },
      ],
      links: [
        { from: 0, to: 2, value: 5 },
        { from: 2, to: 4, value: 5 },
        { from: 1, to: 2, value: 3 },
        { from: 2, to: 5, value: 3 },
        { from: 0, to: 3, value: 2 },
        { from: 3, to: 4, value: 2 },
      ],
    });
  });
});

/** The first slot, as jsdom's charts read it (`CHART_FALLBACK`). */
const FIRST = 'rgb(38, 117, 211)';

function draw(data: ChartData, spec: ChartSpec, onPick = vi.fn()) {
  const view = render(
    <ViewSurface>
      <AnalysisChart data={data} spec={spec} onPick={onPick} cutShort />
    </ViewSurface>,
  );
  return { ...view, onPick };
}

const readingRows = (container: ParentNode) =>
  [
    ...(container
      .querySelector('[data-slot="chart-reading"] table')
      ?.querySelectorAll('tbody tr') ?? []),
  ].map(row => [...row.children].map(cell => cell.textContent));

const frame = (container: HTMLElement, type: string) =>
  waitFor(() => {
    const found = container.querySelector<HTMLElement>(
      `[data-slot="chart"][data-chart="${type}"]`,
    );
    expect(found?.querySelector('[data-slot="chart-plot"] svg')).toBeTruthy();
    return found!;
  });

describe('a hierarchy or a flow drawn and read', () => {
  const shaped = (type: 'sunburst' | 'tree') =>
    shapeChart(
      config({
        type,
        [type]: { levels: ['category', 'sub'], value: 'gmv' },
      }),
      ROWS,
    )!;

  it('reads a sunburst’s innermost parts under their parents, and says what it left out', async () => {
    const spec: ChartSpec = {
      type: 'sunburst',
      sunburst: { levels: ['category', 'sub'], value: 'gmv' },
    };
    const { container } = draw(shaped('sunburst'), spec);
    const chart = await frame(container, 'sunburst');
    expect(chart.getAttribute('data-marks')).toBe('3');
    expect(readingRows(container)).toEqual([
      ['家居', '床品', '50', '41.7%'],
      ['家居', '浴巾', '30', '25.0%'],
      ['厨具', '锅', '40', '33.3%'],
    ]);
    const notes = container.querySelector('[data-slot="sunburst-notes"]');
    expect(notes?.textContent).toContain('Shares of the groups shown');
    expect(notes?.textContent).toContain('1 groups not above zero');
    expect(container.textContent).toContain('highest 家居 · 床品, 50');
  });

  it('draws a tree with every part named', async () => {
    const spec: ChartSpec = {
      type: 'tree',
      tree: { levels: ['category', 'sub'], value: 'gmv' },
    };
    const { container } = draw(shaped('tree'), spec);
    const chart = await frame(container, 'tree');
    expect(chart.getAttribute('data-marks')).toBe('3');
    const text = chart.querySelector('svg')!.textContent ?? '';
    for (const name of ['家居', '床品', '浴巾', '厨具', '锅'])
      expect(text).toContain(name);
  });

  it('reads a sankey’s bands largest first, each band in its source’s colour', async () => {
    const spec: ChartSpec = {
      type: 'sankey',
      sankey: { levels: ['category', 'sub'], value: 'gmv' },
    };
    const data = shapeChart(config(spec), ROWS)!;
    const { container, onPick } = draw(data, spec);
    const chart = await frame(container, 'sankey');
    expect(chart.getAttribute('data-marks')).toBe('3');
    expect(readingRows(container)).toEqual([
      ['家居', '床品', '50'],
      ['厨具', '锅', '40'],
      ['家居', '浴巾', '30'],
    ]);
    expect(container.textContent).toContain(
      '3 flows; the largest 家居 → 床品, 50.',
    );
    // A band is a path stroked with its source's colour, see-through.
    const band = await waitFor(() => {
      const found = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ].find(
        path =>
          path.getAttribute('fill') === FIRST &&
          Number(path.getAttribute('fill-opacity') ?? 1) < 1,
      );
      expect(found).toBeDefined();
      return found!;
    });
    expect(band).toBeDefined();
    expect(onPick).not.toHaveBeenCalled();
    press(container, band);
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({ category: '家居', sub: '床品' });
  });
});

/**
 * A press at the middle of a shape's box, where the library reads it: the
 * event's offset given, since jsdom leaves it at nothing.
 */
function press(container: HTMLElement, mark: Element) {
  const corners = [
    ...(mark.getAttribute('d') ?? '').matchAll(
      /[MLC]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
    ),
  ].map(([, x, y]) => [Number(x), Number(y)] as const);
  const [dx, dy] = /translate\((-?[\d.]+)[\s,]+(-?[\d.]+)\)/
    .exec(mark.getAttribute('transform') ?? '')
    ?.slice(1)
    .map(Number) ?? [0, 0];
  const xs = corners.map(([x]) => x + dx!);
  const ys = corners.map(([, y]) => y + dy!);
  const at = {
    clientX: (Math.min(...xs) + Math.max(...xs)) / 2,
    clientY: (Math.min(...ys) + Math.max(...ys)) / 2,
  };
  const surface = container.querySelector(
    '[data-slot="chart-plot"] > div > div',
  )!;
  for (const type of ['mousemove', 'mousedown', 'mouseup', 'click']) {
    const event = new MouseEvent(type, { bubbles: true, ...at });
    Object.defineProperties(event, {
      offsetX: { value: at.clientX },
      offsetY: { value: at.clientY },
    });
    fireEvent(surface, event);
  }
}
