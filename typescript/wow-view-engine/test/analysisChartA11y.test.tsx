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

import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AnalysisView, ChartData, ChartSpec } from '../src/index.js';
import { AnalysisChart, ViewSurface, zhCN } from '../src/ui/index.js';

/**
 * What a chart says to someone who cannot see it.
 *
 * The drawing used to be a 665×374 `<svg>` carrying recharts' default
 * `role="application"`, no name, an empty `<title>` and an empty `<desc>`.
 * The only text under it was axis ticks — not one number the query answered.
 * So the marks are one named image now, and the numbers are read off
 * `ChartData`, the same projection the marks are drawn from, in a table
 * beside it.
 */
afterEach(cleanup);

/**
 * The text a screen reader would reach.
 *
 * An element with `role="img"` is its name and nothing else, whatever is
 * drawn inside it, so this substitutes the name for the subtree — which is
 * what makes "every value appears in the readable text" a real question
 * rather than one the axis ticks and the value labels answer by accident.
 */
function readable(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement;
  for (const styled of clone.querySelectorAll('style,script')) styled.remove();
  for (const hidden of clone.querySelectorAll('[aria-hidden="true"]'))
    hidden.remove();
  for (const image of clone.querySelectorAll('[role="img"]'))
    image.replaceChildren(
      clone.ownerDocument.createTextNode(
        image.getAttribute('aria-label') ?? '',
      ),
    );
  return clone.textContent ?? '';
}

/** Every number the kernel put in the projection, as the reading prints it. */
function valuesOf(data: ChartData): string[] {
  const said = (value: number | string | null | undefined) =>
    value === null || value === undefined ? [] : [value.toLocaleString()];
  switch (data.type) {
    case 'cartesian':
      return data.points.flatMap(point =>
        Object.values(point.values).flatMap(said),
      );
    case 'pie':
      return data.slices.flatMap(slice => said(slice.value));
    case 'heatmap':
      return data.cells.flat().flatMap(said);
    case 'scatter':
      return data.points.flatMap(point => [
        ...said(point.x),
        ...said(point.y),
        ...said(point.size),
      ]);
    case 'funnel':
      return data.stages.flatMap(stage => said(stage.value));
    case 'metric':
      return [
        ...said(data.value),
        ...said(data.compare?.value),
        ...said(data.target),
        ...(data.trend ?? []).flatMap(point => said(point.value)),
      ];
    case 'waterfall':
      return [
        ...data.steps.flatMap(step => [...said(step.value), ...said(step.end)]),
        ...said(data.total),
      ];
    case 'treemap':
      return data.tiles.flatMap(tile =>
        tile.tiles
          ? tile.tiles.flatMap(inner => said(inner.value))
          : said(tile.value),
      );
    case 'boxplot':
      return data.boxes.flatMap(box =>
        [box.low, box.q1, box.median, box.q3, box.high].flatMap(said),
      );
    case 'gauge':
      return [
        ...said(data.value),
        ...said(data.target),
        ...said(data.min),
        ...said(data.max),
      ];
    case 'radar':
    case 'parallel':
      return data.profiles.flatMap(profile => profile.values.flatMap(said));
    case 'sunburst':
    case 'tree': {
      const leaves = (nodes: typeof data.nodes): number[] =>
        nodes.flatMap(node =>
          node.children ? leaves(node.children) : [node.value],
        );
      return leaves(data.nodes).flatMap(said);
    }
    case 'sankey':
      return data.links.flatMap(link => said(link.value));
    case 'calendar':
      return data.days.flatMap(entry => said(entry.value));
    case 'map':
      return data.regions.flatMap(region => said(region.value));
    case 'themeRiver':
      return data.values.flat().flatMap(said);
  }
}

function draw(
  data: ChartData,
  spec?: ChartSpec,
  columns?: AnalysisView['columns'],
) {
  return render(
    <ViewSurface>
      <AnalysisChart data={data} spec={spec} columns={columns} />
    </ViewSurface>,
  );
}

const columns: AnalysisView['columns'] = [
  {
    alias: 'warehouse',
    label: 'Warehouse',
    role: 'group',
    kind: 'enum',
    cell: 'enum',
    options: [
      { value: 'EAST', label: '华东' },
      { value: 'NORTH', label: '华北' },
    ],
  },
  { alias: 'orders', label: 'Orders', role: 'metric' },
];

const bars: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  points: [
    { x: 'EAST', values: { orders: 4318 } },
    { x: 'NORTH', values: { orders: 2764 } },
  ],
  series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
};

const barSpec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
};

describe('the chart is a named image, not an application', () => {
  it('draws into one named image, and the drawing claims no role', () => {
    const { container } = draw(bars, barSpec, columns);
    const plot = container.querySelector('[data-slot="chart-plot"]')!;

    expect(plot.getAttribute('role')).toBe('img');
    expect(plot.getAttribute('aria-label')).toBe('bar: Orders by Warehouse');
    // The library's own layer stays off: its `<svg>` names nothing and is
    // not an application, so the one name is the image's.
    const svg = plot.querySelector('svg')!;
    expect(svg).not.toBeNull();
    expect(svg.getAttribute('role')).toBeNull();
    expect(svg.getAttribute('aria-label')).toBeNull();
  });

  /**
   * The tab stop came with the same default as the role, and it led into a
   * picture with no key handling of its own. Nothing inside the drawing is
   * reachable by keyboard now — the numbers are, as text.
   */
  it('leaves nothing inside the drawing focusable', () => {
    const { container } = draw(bars, barSpec, columns);
    const plot = container.querySelector('[data-slot="chart-plot"]')!;

    expect(plot.querySelector('svg')!.getAttribute('tabindex')).toBeNull();
    expect(
      plot.querySelectorAll(
        '[tabindex]:not([tabindex="-1"]),a[href],button,input,select,textarea',
      ),
    ).toHaveLength(0);
  });

  /** A heatmap and a funnel are pictures too, and say so the same way. */
  it('names the grid and the stages', () => {
    const heatmap = draw(
      { type: 'heatmap', xs: ['EAST'], ys: ['NORTH'], cells: [[7]] },
      {
        type: 'heatmap',
        heatmap: { x: 'warehouse', y: 'warehouse', value: 'orders' },
      },
      columns,
    );
    expect(
      heatmap.container
        .querySelector('[data-chart="heatmap"] [data-slot="chart-plot"]')!
        .getAttribute('aria-label'),
    ).toBe('heatmap: Orders by Warehouse, Warehouse');

    const funnel = draw({
      type: 'funnel',
      stages: [{ label: 'Placed', value: 9 }],
    });
    expect(
      funnel.container
        .querySelector('[data-chart="funnel"] [data-slot="chart-plot"]')!
        .getAttribute('role'),
    ).toBe('img');
  });
});

describe('every data value in the result appears in the readable text', () => {
  const cases: [string, ChartData, ChartSpec | undefined][] = [
    ['bars', bars, barSpec],
    [
      'a pivoted line, nulls included',
      {
        type: 'cartesian',
        chart: 'line',
        points: [
          { x: 'EAST', values: { a: 611, b: null } },
          { x: 'NORTH', values: { a: 92, b: 5177 } },
        ],
        series: [
          { key: 'a', label: 'a', metric: 'orders', value: 'EAST' },
          { key: 'b', label: 'b', metric: 'orders', value: 'NORTH' },
        ],
      },
      {
        type: 'line',
        cartesian: {
          x: 'warehouse',
          splitBy: 'warehouse',
          series: [{ metric: 'orders' }],
        },
      },
    ],
    [
      'a pie with a merged remainder',
      {
        type: 'pie',
        slices: [
          { category: 'EAST', value: 3251 },
          { category: null, value: 88, other: true },
        ],
      },
      { type: 'pie', pie: { category: 'warehouse', value: 'orders' } },
    ],
    [
      'a heatmap with a hole in it',
      {
        type: 'heatmap',
        xs: ['EAST', 'NORTH'],
        ys: ['EAST'],
        cells: [[1234, null]],
      },
      undefined,
    ],
    [
      'a scatter with a third dimension',
      {
        type: 'scatter',
        points: [{ category: 'EAST', x: 17, y: 4501, size: 63 }],
      },
      undefined,
    ],
    [
      'a funnel that converts',
      {
        type: 'funnel',
        stages: [
          { label: 'Placed', value: 9412 },
          { label: 'Paid', value: 3106, conversion: 0.33 },
        ],
      },
      undefined,
    ],
    [
      'a metric card with a target, a comparison and a trend',
      {
        type: 'metric',
        value: 8201,
        compare: { value: 7154, delta: 1047 },
        target: 9000,
        trend: [
          { x: 'Mon', value: 311 },
          { x: 'Tue', value: 4022 },
        ],
      },
      undefined,
    ],
    [
      'a waterfall that rises, falls and totals',
      {
        type: 'waterfall',
        steps: [
          { x: 'EAST', value: 4318, start: 0, end: 4318 },
          { x: 'NORTH', value: -1764, start: 4318, end: 2554 },
        ],
        total: 2554,
      },
      { type: 'waterfall', waterfall: { x: 'warehouse', value: 'orders' } },
    ],
    [
      'a treemap nested two levels',
      {
        type: 'treemap',
        tiles: [
          {
            group: 'EAST',
            value: 5000,
            tiles: [
              { group: 'EAST', value: 3120 },
              { group: 'NORTH', value: 1880 },
            ],
          },
        ],
        nested: true,
        omitted: 0,
      },
      {
        type: 'treemap',
        treemap: {
          category: 'warehouse',
          parent: 'warehouse',
          value: 'orders',
        },
      },
    ],
    [
      'a boxplot',
      {
        type: 'boxplot',
        boxes: [
          { group: 'EAST', low: 12, q1: 130, median: 208, q3: 377, high: 5120 },
        ],
        omitted: 0,
        approximate: true,
      },
      {
        type: 'boxplot',
        boxplot: {
          category: 'warehouse',
          low: 'orders',
          q1: 'orders',
          median: 'orders',
          q3: 'orders',
          high: 'orders',
        },
      },
    ],
    [
      'a gauge against a target',
      {
        type: 'gauge',
        value: 8120,
        target: 9000,
        reached: 0.9,
        min: 0,
        max: 10000,
      },
      { type: 'gauge', gauge: { metric: 'orders', target: 9000 } },
    ],
    [
      'a radar',
      {
        type: 'radar',
        metrics: ['orders', 'orders', 'orders'],
        profiles: [{ group: 'EAST', values: [4318, 2764, 1511] }],
        omitted: 0,
      },
      {
        type: 'radar',
        radar: {
          category: 'warehouse',
          metrics: ['orders', 'orders', 'orders'],
        },
      },
    ],
    [
      'a sunburst',
      {
        type: 'sunburst',
        nodes: [
          {
            group: 'EAST',
            value: 5000,
            path: { warehouse: 'EAST' },
            children: [
              {
                group: 'NORTH',
                value: 3120,
                path: { warehouse: 'EAST', region: 'NORTH' },
              },
              {
                group: 'EAST',
                value: 1880,
                path: { warehouse: 'EAST', region: 'EAST' },
              },
            ],
          },
        ],
        depth: 2,
        omitted: 0,
      },
      {
        type: 'sunburst',
        sunburst: { levels: ['warehouse', 'region'], value: 'orders' },
      },
    ],
    [
      'a map',
      {
        type: 'map',
        regions: [{ group: 'EAST', value: 4318 }],
        low: 4318,
        high: 4318,
        omitted: 0,
      },
      { type: 'map', map: { region: 'warehouse', value: 'orders' } },
    ],
    [
      'a calendar',
      {
        type: 'calendar',
        days: [
          { at: '2026-09-01', date: '2026-09-01', value: 4318 },
          { at: '2026-09-02', date: '2026-09-02', value: 2764 },
        ],
        years: [2026],
        low: 2764,
        high: 4318,
      },
      { type: 'calendar', calendar: { date: 'warehouse', value: 'orders' } },
    ],
    [
      'a theme river',
      {
        type: 'themeRiver',
        times: ['2026-09-01', '2026-09-02'],
        streams: [{ key: 'EAST', value: 'EAST' }],
        values: [[4318], [2764]],
        uncertain: 0,
      },
      {
        type: 'themeRiver',
        themeRiver: { x: 'warehouse', splitBy: 'region', value: 'orders' },
      },
    ],
    [
      'a sankey',
      {
        type: 'sankey',
        nodes: [
          { level: 0, group: 'EAST', value: 4318 },
          { level: 1, group: 'NORTH', value: 4318 },
        ],
        links: [{ from: 0, to: 1, value: 4318 }],
        omitted: 0,
      },
      {
        type: 'sankey',
        sankey: { levels: ['warehouse', 'region'], value: 'orders' },
      },
    ],
    [
      'parallel coordinates',
      {
        type: 'parallel',
        metrics: ['orders', 'orders', 'orders'],
        profiles: [{ group: 'NORTH', values: [4318, 2764, 1511] }],
        omitted: 0,
      },
      {
        type: 'parallel',
        parallel: {
          category: 'warehouse',
          metrics: ['orders', 'orders', 'orders'],
        },
      },
    ],
  ];

  it.each(cases)('%s', (_name, data, spec) => {
    const { container } = draw(data, spec, columns);
    const text = readable(container);
    const values = valuesOf(data);

    expect(values.length).toBeGreaterThan(0);
    for (const value of values) expect(text).toContain(value);
  });

  /**
   * The table is the reading, so it carries the category beside the number:
   * a column of bare numbers would satisfy the loop above and answer
   * nothing about which warehouse each one belongs to.
   */
  it('says each number under the category it belongs to', () => {
    const { container } = draw(bars, barSpec, columns);
    const reading = container.querySelector('[data-slot="chart-reading"]')!;

    // A **surviving class assertion**: `sr-only` is the one way to say
    // "in the accessible tree, not on screen", and there is no state to
    // read it back from.
    expect(reading.className).toContain('sr-only');
    // The series is headed by its column, not by the alias the query
    // carried: the same title the legend and the tooltip show (D20).
    expect(
      [...reading.querySelectorAll('th[scope="col"]')].map(c => c.textContent),
    ).toEqual(['Warehouse', 'Orders']);
    expect(
      [...reading.querySelectorAll('tbody tr')].map(row =>
        [...row.children].map(cell => cell.textContent),
      ),
    ).toEqual([
      ['华东', '4,318'],
      ['华北', '2,764'],
    ]);
  });

  /** An axis that prints ratios as percentages is read the same way. */
  it('prints a measure in the format its own axis uses', () => {
    const { container } = draw(
      {
        type: 'cartesian',
        chart: 'bar',
        points: [{ x: 'EAST', values: { rate: 0.25 } }],
        series: [{ key: 'rate', label: 'rate', metric: 'rate' }],
      },
      {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'rate', axis: 'right' }],
          yAxis: { right: { format: 'percent' } },
        },
      },
      columns,
    );

    expect(readable(container)).toContain('25%');
  });

  /** Nothing to draw is nothing to read; an empty table is noise. */
  it('draws no table when the projection holds no row', () => {
    const { container } = draw({ type: 'pie', slices: [] });

    expect(container.querySelector('[data-slot="chart-reading"]')).toBeNull();
  });
});

/**
 * The metric card is the one family whose answer was never a picture: it
 * prints its value and its signed comparison as text, and that has to stay
 * text rather than disappear into a `role="img"`.
 */
describe('the metric card still says its value out loud', () => {
  it('keeps the value and the delta as ordinary text', () => {
    const { container } = draw(
      {
        type: 'metric',
        value: 8201,
        compare: { value: 7154, delta: 1047 },
        trend: [{ x: 'Mon', value: 311 }],
      },
      { type: 'metric', metric: { metric: 'orders' } },
      columns,
    );
    const card = container.querySelector('[data-slot="metric-card"]')!;

    expect(card.getAttribute('role')).toBeNull();
    expect(card.textContent).toContain('8,201');
    expect(card.textContent).toContain('+1,047');
    // Only the sparkline is a picture, and it carries a name of its own.
    expect(
      card
        .querySelector('[data-chart="sparkline"] [data-slot="chart-plot"]')!
        .getAttribute('aria-label'),
    ).toBe('metric: Orders, over time');
  });

  /**
   * The target bar used to be a `div` sized by an inline width: a shape with
   * no role, no value and no bounds, which said how far along the target the
   * value was to a pair of eyes and to nothing else.
   */
  it('reads the target bar as the two numbers behind it', () => {
    const { container } = draw(
      { type: 'metric', value: 8201, target: 9000 },
      { type: 'metric', metric: { metric: 'orders' } },
      columns,
    );
    const bar = container.querySelector('[role="progressbar"]')!;

    expect(bar.getAttribute('aria-label')).toBe('Toward target');
    expect(bar.getAttribute('aria-valuetext')).toBe('8,201 of 9,000');
    // The position is the percentage the role announces; the wording above
    // is what replaces it.
    expect(Number(bar.getAttribute('aria-valuenow'))).toBeCloseTo(91.12, 2);
  });

  /** Nothing to fall short of still has to be drawable. */
  it('draws a target of zero without dividing by it', () => {
    const { container } = draw(
      { type: 'metric', value: 12, target: 0 },
      { type: 'metric', metric: { metric: 'orders' } },
      columns,
    );

    expect(
      container
        .querySelector('[role="progressbar"]')!
        .getAttribute('aria-valuenow'),
    ).toBe('100');
  });
});

describe('the wording is in both catalogues', () => {
  it('names the figure and its table in Chinese', () => {
    const { container } = render(
      <ViewSurface messages={zhCN}>
        <AnalysisChart data={bars} spec={barSpec} columns={columns} />
      </ViewSurface>,
    );

    expect(
      container
        .querySelector('[data-slot="chart-plot"]')!
        .getAttribute('aria-label'),
    ).toBe('柱状图：Orders，按Warehouse');
    expect(
      container.querySelector('[data-slot="chart-reading"] caption')!
        .textContent,
    ).toBe('柱状图：Orders，按Warehouse，数据表');
  });
});
