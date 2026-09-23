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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
  type DataViewDefinition,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { defaultMessages } from '../src/ui/messages.js';
import {
  seriesDragAccessibility,
  seriesDrop,
} from '../src/ui/analysis/drag.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { formattersFor } from './fixtures/columns.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * A definition wide enough for the options to have something to choose:
 * two dimensions the cartesian slots can swap, two metrics a series can be
 * added from, and an average, which is the metric a pie may not cap.
 */
function richDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM'] },
    ],
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
          functions: [AggregationFunction.SUM, AggregationFunction.AVG],
        },
      ],
    },
  });
}

const WAREHOUSE: AnalysisGroup = {
  alias: 'warehouse',
  field: 'warehouse',
  type: 'TERMS',
};
const STATUS: AnalysisGroup = {
  alias: 'status',
  field: 'status',
  type: 'TERMS',
};
const ORDERS: AnalysisMetric = { alias: 'orders', type: 'COUNT' };
const TOTAL: AnalysisMetric = {
  alias: 'total',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVERAGE: AnalysisMetric = {
  alias: 'average',
  type: 'NUMERIC',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

/** The analysis config every case below varies one part of. */
function config(overrides: Partial<AnalysisViewConfig>): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [WAREHOUSE],
    metrics: [ORDERS, TOTAL],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: { type: 'bar', cartesian: { x: 'warehouse', series: [ORDERS] } },
    ...overrides,
  } as AnalysisViewConfig;
}

/** One group per warehouse, with every alias the cases below may name. */
const GROUPED: RecordData[] = [
  { warehouse: 'CN', status: 'PENDING', orders: 2, total: 30, average: 15 },
  { warehouse: 'US', status: 'SHIPPED', orders: 1, total: 10, average: 10 },
];

/**
 * The workbench with one analysis view open, its aggregation answered by a
 * spy: what the options do to the rows on screen is one thing, and whether
 * they went back to the backend for them is another (D20 — presentation
 * redraws, and only a query runs).
 */
async function open(
  chart: ChartSpec,
  overrides: Partial<AnalysisViewConfig> = {},
  rows: RecordData[] = GROUPED,
) {
  const source: ViewSource = testSource({
    // The totals row is a query of its own — the same one without the
    // grouping — so the spy answers it with the one row it asks for.
    aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
      Promise.resolve(
        (query.groupBy?.length ?? 0) > 0
          ? rows.map(row => ({ ...row }))
          : [{ orders: 3, total: 40, average: 13 }],
      ),
    ) as ViewSource['aggregate'],
  });
  const instance: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config: config({ chart, ...overrides }),
  };
  const engine = new ViewEngine({
    definitions: [richDefinition()],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  const user = userEvent.setup();
  // The way into the panel is the result toolbar, which exists once a
  // result with groups in it has landed.
  await user.click(await screen.findByRole('button', { name: 'Visualize' }));
  return {
    engine,
    user,
    queries: () => vi.mocked(source.aggregate).mock.calls.length,
    draft: () =>
      engine.openRuntimes()[0]!.getSnapshot().draft as AnalysisViewConfig,
  };
}

const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]');
const picker = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-picker"]');

/**
 * The labelled button under the tiles, which is where level two opens: named
 * by the chosen type, in the words the options page is headed with.
 */
function gear(name: string): HTMLElement {
  return screen.getByRole('button', { name: `${name} options` });
}

/** Chooses one item of a named `CompactSelect`. */
async function choose(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  item: string,
) {
  await user.click(screen.getByLabelText(label));
  await user.click(await screen.findByRole('option', { name: item }));
}

/** What a named select shows as chosen. */
const chosen = (label: string) => screen.getByLabelText(label).textContent;

/** The series rows in the order they are drawn, by the name each wears. */
const seriesNames = () =>
  [...panel()!.querySelectorAll('[data-slot="series-card"]')].map(
    row => row.querySelector('[data-slot="item-title"]')?.textContent,
  );

describe('the chart options', () => {
  it('opens from the button under the tiles and goes back to the types', async () => {
    const { user, queries } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    // Level one first: the way on is a button of its own under the tiles,
    // named after the chosen type — a tile holds no button.
    expect(picker()).not.toBeNull();
    expect(panel()).toBeNull();
    const ran = queries();

    await user.click(gear('bar'));

    const options = panel()!;
    expect(options.getAttribute('data-chart-type')).toBe('bar');
    expect(
      within(options).getByRole('heading', { level: 2, name: 'bar options' }),
    ).toBeDefined();

    await user.click(
      within(options).getByRole('button', { name: 'Back to the chart types' }),
    );
    await waitFor(() => expect(picker()).not.toBeNull());
    expect(panel()).toBeNull();
    // Neither level asked the backend anything.
    expect(queries()).toBe(ran);
  });

  it('lays each type out on the pages it has', async () => {
    const { user } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });

    await user.click(gear('bar'));
    expect(
      within(panel()!)
        .getAllByRole('tab')
        .map(tab => tab.textContent),
    ).toEqual(['Data', 'Display', 'Axes']);

    // A pie has no numeric axis to set, so it has two pages.
    await user.click(
      within(panel()!).getByRole('button', { name: 'Back to the chart types' }),
    );
    await user.click(await screen.findByRole('radio', { name: 'pie' }));
    await user.click(gear('pie'));
    await waitFor(() =>
      expect(
        within(panel()!)
          .getAllByRole('tab')
          .map(tab => tab.textContent),
      ).toEqual(['Data', 'Display']),
    );
  });

  it('gives a one-page type no tab strip at all', async () => {
    // A scatter's only choices are which metrics it plots, and the table's
    // only choice is its totals row: one page each, and a strip of one tab
    // is a control that cannot be used.
    const { user } = await open({
      type: 'scatter',
      scatter: { category: 'warehouse', x: 'orders', y: 'total' },
    });

    await user.click(gear('scatter'));
    expect(within(panel()!).queryAllByRole('tab')).toEqual([]);
    expect(within(panel()!).getByLabelText('One point per')).toBeDefined();
  });

  it('names every slot by its column, never by the alias', async () => {
    const { user } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });

    await user.click(gear('bar'));
    // Every series card is headed by its column — 「金额的合计」, never the
    // alias `total`, which names the query.
    expect(seriesNames()).toEqual(['Record count', 'Sum of Amount']);
    // The row is one line, so a long column title is cut off in it; the
    // whole of it is on the element for a pointer to read.
    expect(
      [...panel()!.querySelectorAll('[data-slot="series-card"]')].map(row =>
        row.querySelector('[data-slot="item-title"]')?.getAttribute('title'),
      ),
    ).toEqual(seriesNames());

    // A position slot lists the dimensions, and only those.
    await user.click(screen.getByLabelText('Horizontal axis'));
    expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual(['Warehouse']);
  });

  it('removes a series and adds it back from the metrics not drawn', async () => {
    const { user, draft, queries } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });
    await user.click(gear('bar'));
    const ran = queries();

    fireEvent.click(
      within(panel()!).getByRole('button', {
        name: 'Remove series Record count',
      }),
    );
    await waitFor(() => expect(seriesNames()).toEqual(['Sum of Amount']));
    expect(draft().chart.cartesian?.series).toEqual([{ metric: 'total' }]);
    // The last series cannot go: a chart of nothing is not a chart.
    expect(
      within(panel()!)
        .getByRole('button', { name: 'Remove series Sum of Amount' })
        .hasAttribute('disabled'),
    ).toBe(true);

    // The menu offers exactly the metrics not on the chart.
    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Add series' }),
    );
    const offered = await screen.findAllByRole('menuitem');
    expect(offered.map(item => item.textContent)).toEqual(['Record count']);
    fireEvent.click(offered[0]!);

    await waitFor(() =>
      expect(seriesNames()).toEqual(['Sum of Amount', 'Record count']),
    );
    expect(queries()).toBe(ran);
  });

  /**
   * Which series comes first is read off a stack from the bottom up and off
   * a legend from its first entry, so the order is a setting. The pointer
   * path is a browser story — `@dnd-kit/dom` picks its target by measuring,
   * and in jsdom every box is 0×0 at the origin — and the keyboard path is
   * the same move made without one.
   */
  it('carries a series into another place, and says where it landed', async () => {
    const { user, draft, queries } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total', axis: 'right' }],
      },
    });
    await user.click(gear('bar'));
    const ran = queries();

    within(panel()!)
      .getByRole('button', { name: 'Reorder Record count' })
      .focus();
    await user.keyboard('{ArrowDown}');

    await waitFor(() =>
      expect(seriesNames()).toEqual(['Sum of Amount', 'Record count']),
    );
    // The order is the whole of the change: the axis the second series was
    // measured on travels with it.
    expect(draft().chart.cartesian?.series).toEqual([
      { metric: 'total', axis: 'right' },
      { metric: 'orders' },
    ]);
    expect(
      document.querySelector('[data-slot="series-announcement"]')!.textContent,
    ).toBe('Record count moved to position 2 of 2');
    // A redraw of the rows on hand, like every other chart option.
    expect(queries()).toBe(ran);
  });

  it('writes nothing when a series is carried past the end', async () => {
    const { user, draft } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });
    await user.click(gear('bar'));

    within(panel()!)
      .getByRole('button', { name: 'Reorder Record count' })
      .focus();
    await user.keyboard('{ArrowUp}');

    expect(draft().chart.cartesian?.series).toEqual([
      { metric: 'orders' },
      { metric: 'total' },
    ]);
    expect(
      document.querySelector('[data-slot="series-announcement"]')!.textContent,
    ).toBe('');
  });

  /** A chart of one series is first and last at once: nothing to reorder. */
  it('refuses the handle while one series is all there is', async () => {
    const { user } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    await user.click(gear('bar'));

    expect(
      within(panel()!)
        .getByRole('button', { name: 'Reorder Record count' })
        .hasAttribute('disabled'),
    ).toBe(true);
  });

  it('swaps the two dimension slots when one is chosen for the other', async () => {
    const { user, draft } = await open(
      {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          splitBy: 'status',
          series: [{ metric: 'orders' }],
        },
      },
      { groups: [WAREHOUSE, STATUS], metrics: [ORDERS] },
    );
    await user.click(gear('bar'));
    expect(chosen('Horizontal axis')).toContain('Warehouse');
    expect(chosen('Split by')).toContain('Status');

    // Choosing the split's dimension for the axis swaps the two rather than
    // leaving a chart that splits by what it is already plotted along.
    await choose(user, 'Horizontal axis', 'Status');

    await waitFor(() =>
      expect(draft().chart.cartesian).toMatchObject({
        x: 'status',
        splitBy: 'warehouse',
      }),
    );
    expect(chosen('Split by')).toContain('Warehouse');
  });
});

describe('the chart options’ display page', () => {
  it('stacks every series at once and reads the one choice back', async () => {
    const { user, draft, queries } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total', axis: 'right' }],
      },
    });
    await user.click(gear('bar'));
    const ran = queries();
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    const box = () =>
      within(panel()!).getByRole('checkbox', { name: 'Stacked' });
    expect(box().getAttribute('aria-checked')).toBe('false');

    fireEvent.click(box());

    // Stacking is one choice for the whole chart: every series joins.
    await waitFor(() =>
      expect(
        draft().chart.cartesian?.series.map(series => series.stack),
      ).toEqual(['all', 'all']),
    );
    // And every series comes back onto the one axis: piled segments are
    // being added up, and two scales do not add. Drawn across two axes it
    // was one stack per axis, at the same place and the same width, so the
    // taller simply hid the other.
    expect(draft().chart.cartesian?.series[1]).not.toHaveProperty('axis');
    expect(box().getAttribute('aria-checked')).toBe('true');
    expect(queries()).toBe(ran);
    // Presentation, so there is nothing waiting to be run: the "Analysis"
    // toggle wears no dot (D20, `ANALYSIS_PRESENTATION_MEMBERS`).
    expect(
      document
        .querySelector('[data-slot="editor-toggle"]')
        ?.querySelector('[data-slot="pending-dot"]'),
    ).toBeFalsy();
  });

  it('leaves stacking alone where there is nothing to stack', async () => {
    const { user } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    await user.click(gear('bar'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    // One series and no split is one run of marks; a stack of one is not a
    // choice, so the box is there and disabled rather than absent.
    const box = within(panel()!).getByRole('checkbox', { name: 'Stacked' });
    expect(box.getAttribute('aria-disabled')).toBe('true');
    // And it says why: a control that refuses the press without a word is
    // the analyst wondering what is broken.
    expect(describedText(box)).toBe(
      'Stacking needs two or more bar or area series',
    );
  });

  it('offers no stacking on a line, and writes its values only when asked', async () => {
    const { user, draft } = await open({
      type: 'line',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });
    await user.click(gear('line'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    // A stacked line stood its points at the running sum while their labels
    // said their own values (audit P0-2): a line never stacks, so there is
    // no box to tick.
    expect(
      within(panel()!).queryByRole('checkbox', { name: 'Stacked' }),
    ).toBeNull();
    // A line answers where the numbers go, and a number on every point
    // drowned it (audit P1-3): its values are off until asked for.
    const box = () =>
      within(panel()!).getByRole('checkbox', { name: 'Value labels' });
    expect(box().getAttribute('aria-checked')).toBe('false');
    const written = () =>
      document.querySelectorAll('[data-slot="chart-plot"] svg text[stroke]');
    expect(written()).toHaveLength(0);
    fireEvent.click(box());
    await waitFor(() => expect(draft().chart.labels).toBe(true));
    await waitFor(() => expect(written().length).toBeGreaterThan(0));
  });

  it('stacks a combo’s bars and leaves its line where it is', async () => {
    const { user, draft } = await open(
      {
        type: 'combo',
        cartesian: {
          x: 'warehouse',
          series: [
            { metric: 'orders', type: 'bar' },
            { metric: 'average', type: 'bar' },
            { metric: 'total', type: 'line', axis: 'right' },
          ],
        },
      },
      { metrics: [ORDERS, TOTAL, AVERAGE] },
    );
    await user.click(gear('combo'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    const box = () =>
      within(panel()!).getByRole('checkbox', { name: 'Stacked' });
    expect(box().getAttribute('aria-disabled')).not.toBe('true');
    fireEvent.click(box());
    await waitFor(() =>
      expect(draft().chart.cartesian?.series).toEqual([
        { metric: 'orders', type: 'bar', stack: 'all' },
        { metric: 'average', type: 'bar', stack: 'all' },
        { metric: 'total', type: 'line', axis: 'right' },
      ]),
    );
    expect(box().getAttribute('aria-checked')).toBe('true');
  });

  it('adds a reference line, writes its value and takes it away', async () => {
    const { user, draft, queries } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    await user.click(gear('bar'));
    const ran = queries();
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Add reference line' }),
    );
    // A new line starts on the axis every chart has, at nothing in
    // particular: a line has to be somewhere before it can be moved.
    await waitFor(() =>
      expect(draft().chart.cartesian?.referenceLines).toEqual([
        { axis: 'left', value: 0 },
      ]),
    );
    const card = panel()!.querySelector<HTMLElement>(
      '[data-slot="reference-line-card"]',
    )!;
    // Value, caption and remove are named after what they are, so the row
    // itself says which line they belong to.
    expect(card.getAttribute('role')).toBe('group');
    expect(card.getAttribute('aria-label')).toBe('Reference line 1');
    // One axis, so there is no axis to choose between.
    expect(within(card).queryByLabelText('Axis of the reference line')).toBe(
      null,
    );

    fireEvent.change(
      within(card).getByLabelText('Value of the reference line'),
      {
        target: { value: '25' },
      },
    );
    fireEvent.change(within(card).getByLabelText('Caption'), {
      target: { value: 'Target' },
    });
    await waitFor(() =>
      expect(draft().chart.cartesian?.referenceLines).toEqual([
        { axis: 'left', value: 25, label: 'Target' },
      ]),
    );

    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Remove reference line' }),
    );
    // The last line gone takes the member with it rather than leaving an
    // empty list behind.
    await waitFor(() =>
      expect(draft().chart.cartesian).not.toHaveProperty('referenceLines'),
    );
    expect(queries()).toBe(ran);
  });

  it('caps a pie’s slices only where the value adds up, and refuses one', async () => {
    const { user, draft } = await open(
      { type: 'pie', pie: { category: 'warehouse', value: 'average' } },
      { metrics: [ORDERS, AVERAGE] },
    );
    await user.click(gear('pie'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    // An average of the merged categories cannot be worked out from their
    // averages, so there is no tail to merge and no control to offer.
    expect(within(panel()!).queryByLabelText('Slices at most')).toBe(null);

    await user.click(within(panel()!).getByRole('tab', { name: 'Data' }));
    await choose(user, 'Value', 'Record count');
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));
    const cap = () => within(panel()!).getByLabelText('Slices at most');
    await waitFor(() => expect(cap()).toBeDefined());

    // One slice plus "other" is not a pie anybody asked for.
    fireEvent.change(cap(), { target: { value: '1' } });
    expect(draft().chart.pie).not.toHaveProperty('maxSlices');

    fireEvent.change(cap(), { target: { value: '3' } });
    await waitFor(() => expect(draft().chart.pie?.maxSlices).toBe(3));

    // Nor is a ninth slice: the palette has eight colours, and the field
    // says what an empty box means.
    fireEvent.change(cap(), { target: { value: '9' } });
    expect(draft().chart.pie?.maxSlices).toBe(3);
    expect(
      within(panel()!).getByText(
        'The rest merge into “Other”. At most 8, one colour each — and 8 when left empty.',
      ),
    ).toBeDefined();
  });

  it('turns a pie into a donut and stands its legend on the right', async () => {
    const { user, draft } = await open({
      type: 'pie',
      pie: { category: 'warehouse', value: 'orders' },
    });
    await user.click(gear('pie'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    fireEvent.click(within(panel()!).getByRole('checkbox', { name: 'Donut' }));
    await waitFor(() => expect(draft().chart.pie?.donut).toBe(true));

    await choose(user, 'Legend', 'Right');
    await waitFor(() => expect(draft().chart.legend).toBe('right'));
    // A pie always has one, so the legend is still drawn — on its side.
    await waitFor(() =>
      expect(
        document
          .querySelector('[data-slot="chart"]')
          ?.getAttribute('data-legend'),
      ).toBe('right'),
    );
    expect(document.querySelector('[data-slot="chart-legend"]')).not.toBeNull();
    // And the hole is drawn: the frame says what it draws.
    expect(
      document.querySelector('[data-slot="chart"]')?.getAttribute('data-chart'),
    ).toBe('donut');
  });

  it('turns a cartesian chart on its side and smooths its lines', async () => {
    const { user, draft } = await open({
      type: 'line',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    await user.click(gear('line'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Horizontal' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian?.orientation).toBe('horizontal'),
    );
    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Smooth lines' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian?.series.every(s => s.smooth)).toBe(true),
    );

    // Unchecked again, neither leaves a `false` behind: the spec says what
    // was asked for and nothing else.
    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Horizontal' }),
    );
    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Smooth lines' }),
    );
    await waitFor(() =>
      expect(draft().chart.cartesian).toEqual({
        x: 'warehouse',
        series: [{ metric: 'orders' }],
      }),
    );
  });

  it('gives a metric card a target and a number format', async () => {
    const { user, draft } = await open(
      { type: 'metric', metric: { metric: 'orders' } },
      { groups: [], metrics: [ORDERS, TOTAL] },
      [{ orders: 3, total: 40 }],
    );
    await user.click(gear('metric'));
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    fireEvent.change(within(panel()!).getByLabelText('Target value'), {
      target: { value: '10' },
    });
    await waitFor(() => expect(draft().chart.metric?.target).toBe(10));
    // The bar toward the target is the registry's progressbar, not a div
    // with a width on it.
    expect(document.querySelector('[role="progressbar"]')).not.toBeNull();

    fireEvent.click(within(panel()!).getByRole('button', { name: 'Compact' }));
    await waitFor(() => expect(draft().chart.metric?.format).toBe('compact'));
    // "Auto" is the absence of a format rather than a format of its own.
    fireEvent.click(within(panel()!).getByRole('button', { name: 'Auto' }));
    await waitFor(() =>
      expect(draft().chart.metric).not.toHaveProperty('format'),
    );
  });
});

describe('the chart options’ axes page', () => {
  it('writes an axis and drops it once nothing is left on it', async () => {
    const { user, draft, queries } = await open({
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
    });
    await user.click(gear('bar'));
    const ran = queries();
    await user.click(within(panel()!).getByRole('tab', { name: 'Axes' }));

    // Only the axis a series sits on: an axis with nothing on it is not
    // drawn, so there is nothing to set about it.
    expect(
      [...panel()!.querySelectorAll('[data-slot^="chart-options-axis-"]')].map(
        section => section.getAttribute('data-slot'),
      ),
    ).toEqual(['chart-options-axis-left']);

    const axis = panel()!.querySelector<HTMLElement>(
      '[data-slot="chart-options-axis-left"]',
    )!;
    fireEvent.change(within(axis).getByLabelText('Axis title'), {
      target: { value: 'Money' },
    });
    fireEvent.change(within(axis).getByLabelText('Minimum'), {
      target: { value: '0' },
    });
    fireEvent.change(within(axis).getByLabelText('Maximum'), {
      target: { value: '100' },
    });
    fireEvent.click(within(axis).getByRole('button', { name: 'Compact' }));

    await waitFor(() =>
      expect(draft().chart.cartesian?.yAxis).toEqual({
        left: { label: 'Money', min: 0, max: 100, format: 'compact' },
      }),
    );
    // The title reaches the drawing.
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('[data-slot="chart-plot"] svg text')].map(
          text => text.textContent,
        ),
      ).toContain('Money'),
    );

    fireEvent.change(within(axis).getByLabelText('Axis title'), {
      target: { value: '' },
    });
    fireEvent.change(within(axis).getByLabelText('Minimum'), {
      target: { value: '' },
    });
    fireEvent.change(within(axis).getByLabelText('Maximum'), {
      target: { value: '' },
    });
    fireEvent.click(within(axis).getByRole('button', { name: 'Auto' }));

    // An axis with nothing said about it is no axis object at all, and the
    // last one leaving takes `yAxis` with it.
    await waitFor(() =>
      expect(draft().chart.cartesian).not.toHaveProperty('yAxis'),
    );
    expect(queries()).toBe(ran);
  });
});

describe('the chart options of the other families', () => {
  it('names each series’ mark and the axis it is measured on', async () => {
    const { user, draft } = await open({
      type: 'combo',
      cartesian: {
        x: 'warehouse',
        series: [
          { metric: 'orders', type: 'bar' },
          { metric: 'total', type: 'bar' },
        ],
      },
    });
    await user.click(gear('combo'));

    // Only a combo asks each series how it is drawn; the others draw them
    // all the one way the type names.
    await choose(user, 'Drawn as, for Sum of Amount', 'Line');
    await waitFor(() =>
      expect(draft().chart.cartesian?.series[1]).toMatchObject({
        type: 'line',
      }),
    );

    await choose(user, 'Axis for Sum of Amount', 'Right axis');
    await waitFor(() =>
      expect(draft().chart.cartesian?.series[1]).toMatchObject({
        axis: 'right',
      }),
    );
    // A second axis now exists, so the axes page has a section for it and a
    // reference line has an axis to be hung on.
    await user.click(within(panel()!).getByRole('tab', { name: 'Axes' }));
    expect(
      [...panel()!.querySelectorAll('[data-slot^="chart-options-axis-"]')].map(
        section => section.getAttribute('data-slot'),
      ),
    ).toEqual(['chart-options-axis-left', 'chart-options-axis-right']);

    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));
    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Add reference line' }),
    );
    await waitFor(() =>
      expect(
        within(panel()!).getByLabelText('Axis of the reference line'),
      ).toBeDefined(),
    );
  });

  it('lets a scatter drop the measure it sizes its points by', async () => {
    const { user, draft } = await open({
      type: 'scatter',
      scatter: {
        category: 'warehouse',
        x: 'orders',
        y: 'total',
        size: 'total',
      },
    });
    await user.click(gear('scatter'));

    expect(chosen('Point size')).toContain('Sum of Amount');
    await choose(user, 'Point size', 'None');
    await waitFor(() =>
      expect(draft().chart.scatter).not.toHaveProperty('size'),
    );

    // The two measures swap rather than doubling up, as the two dimensions
    // of a split do.
    await choose(user, 'Across', 'Sum of Amount');
    await waitFor(() =>
      expect(draft().chart.scatter).toMatchObject({
        x: 'total',
        y: 'orders',
      }),
    );
  });

  it('orders a funnel of metrics by hand and reads its conversion', async () => {
    const { user, draft } = await open(
      {
        type: 'funnel',
        funnel: {
          stages: {
            from: 'metrics',
            items: [{ metric: 'orders' }, { metric: 'total' }],
          },
        },
      },
      { groups: [], metrics: [ORDERS, TOTAL] },
      [{ orders: 3, total: 40 }],
    );
    await user.click(gear('funnel'));

    const stages = () =>
      [...panel()!.querySelectorAll('[data-slot="stage-card"]')].map(card =>
        card.getAttribute('data-stage'),
      );
    expect(stages()).toEqual(['orders', 'total']);
    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Move Record count down' }),
    );
    await waitFor(() => expect(stages()).toEqual(['total', 'orders']));

    // A stage is a step of a business — 「下单」 — and the metric's column
    // title says what was measured, which is rarely the same sentence. The
    // name is typed where it is drawn, and an emptied box takes it back.
    const box = within(panel()!).getByRole('textbox', {
      name: 'Name of the stage Record count',
    });
    await user.type(box, 'Placed');
    await waitFor(() =>
      expect(draft().chart.funnel?.stages).toMatchObject({
        items: [{ metric: 'total' }, { metric: 'orders', label: 'Placed' }],
      }),
    );
    // The buttons on the card name the stage as the analyst renamed it.
    expect(
      within(panel()!).getByRole('button', { name: 'Move Placed up' }),
    ).toBeDefined();

    await user.clear(box);
    await waitFor(() =>
      expect(
        (draft().chart.funnel?.stages as { items: object[] }).items[1],
      ).toEqual({ metric: 'orders' }),
    );

    // Metric stages are each their own count already, so there is nothing
    // to accumulate and no such box.
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));
    expect(
      within(panel()!).queryByRole('checkbox', { name: /^Cumulative/ }),
    ).toBeNull();
    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'First stage' }),
    );
    await waitFor(() => expect(draft().chart.funnel?.conversion).toBe('first'));
  });

  it('gives a funnel of a group’s values the order the rows came in', async () => {
    const { user, draft } = await open(
      {
        type: 'bar',
        cartesian: { x: 'status', series: [{ metric: 'orders' }] },
      },
      { groups: [STATUS], metrics: [ORDERS] },
      [
        { status: 'PENDING', orders: 5 },
        { status: 'SHIPPED', orders: 3 },
        { status: 'PAID', orders: 1 },
      ],
    );

    // Picked, not opened: the order is business knowledge the kernel does
    // not have, and a funnel of no stages draws nothing at all.
    await user.click(screen.getByRole('radio', { name: 'funnel' }));
    await waitFor(() =>
      expect(draft().chart.funnel?.stages).toMatchObject({
        from: 'group',
        order: ['PENDING', 'SHIPPED', 'PAID'],
      }),
    );

    await user.click(gear('funnel'));
    const stages = () =>
      [...panel()!.querySelectorAll('[data-slot="stage-card"]')].map(card =>
        card.getAttribute('data-stage'),
      );
    expect(stages()).toEqual(['PENDING', 'SHIPPED', 'PAID']);
    // A stage read off the rows is not renameable, so it is a line of text
    // cut off at the card's width — and the whole of it is on the element,
    // for a pointer to read.
    expect(
      [...panel()!.querySelectorAll('[data-slot="stage-name"]')].map(name =>
        name.getAttribute('title'),
      ),
    ).toEqual(['PENDING', 'SHIPPED', 'PAID']);

    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Move PENDING down' }),
    );
    await waitFor(() =>
      expect(stages()).toEqual(['SHIPPED', 'PENDING', 'PAID']),
    );
    expect(draft().chart.funnel?.stages).toMatchObject({
      order: ['SHIPPED', 'PENDING', 'PAID'],
    });

    // Each stage is its own rows' number until the analyst asks for
    // "reached at least" — the box starts clear and says what it adds up.
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));
    const cumulative = within(panel()!).getByRole('checkbox', {
      name: 'Cumulative (reached at least this stage)',
    });
    expect(cumulative.getAttribute('aria-checked')).toBe('false');
    await user.click(cumulative);
    await waitFor(() =>
      expect(draft().chart.funnel?.stages).toMatchObject({ cumulative: true }),
    );
  });

  /**
   * A stage counts what entered and remained, so its value is a record
   * count or a sum (`chart.funnel.not-additive`): an average is not offered
   * where the funnel's value is chosen.
   */
  it('offers a funnel only the metrics that add up', async () => {
    const { user } = await open(
      {
        type: 'funnel',
        funnel: {
          stages: {
            from: 'group',
            category: 'warehouse',
            value: 'orders',
            order: ['CN', 'US'],
          },
        },
      },
      { metrics: [ORDERS, AVERAGE, TOTAL] },
    );
    await user.click(gear('funnel'));
    await user.click(within(panel()!).getByLabelText('Value'));
    const offered = (await screen.findAllByRole('option')).map(
      option => option.textContent,
    );
    expect(offered).toEqual(['Record count', 'Sum of Amount']);
  });

  it('puts a heatmap on a log scale and writes its numbers in the cells', async () => {
    const { user, draft, queries } = await open(
      {
        type: 'heatmap',
        heatmap: { x: 'status', y: 'warehouse', value: 'orders' },
      },
      { groups: [WAREHOUSE, STATUS], metrics: [ORDERS] },
    );
    await user.click(gear('heatmap'));
    const ran = queries();
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    // A cell's number is written on it, with the halo every value label
    // wears.
    const written = () =>
      document.querySelectorAll('[data-slot="chart-plot"] svg text[stroke]');
    expect(written()).toHaveLength(0);
    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Value labels' }),
    );
    await waitFor(() => expect(written().length).toBeGreaterThan(0));

    fireEvent.click(
      within(panel()!).getByRole('button', { name: 'Logarithmic' }),
    );
    await waitFor(() => expect(draft().chart.heatmap?.scale).toBe('log'));
    expect(queries()).toBe(ran);
  });

  it('keeps the headline metric out of the card’s comparison', async () => {
    const { user, draft } = await open(
      { type: 'metric', metric: { metric: 'orders' } },
      { groups: [], metrics: [ORDERS, TOTAL] },
      [{ orders: 3, total: 40 }],
    );
    await user.click(gear('metric'));

    // Comparing a number with itself says nothing, so the headline is not
    // on the list; "None" is a word rather than a blank.
    await user.click(screen.getByLabelText('Compared with'));
    expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual(['None', 'Sum of Amount']);
    await user.click(screen.getByRole('option', { name: 'Sum of Amount' }));

    await waitFor(() =>
      expect(draft().chart.metric?.compare).toEqual({
        metric: 'total',
        mode: 'delta',
      }),
    );

    await choose(user, 'Compared with', 'None');
    await waitFor(() =>
      expect(draft().chart.metric).not.toHaveProperty('compare'),
    );
  });
});

describe('what the chart options change on screen', () => {
  const legend = () => document.querySelector('[data-slot="chart-legend"]');
  // A value label is drawn with a halo of the ground under it, and a tick is
  // not: the halo is what tells the two texts of the drawing apart.
  const labels = () =>
    document.querySelectorAll('[data-slot="chart-plot"] svg text[stroke]');

  it('takes the legend away, and the values it writes unasked', async () => {
    const { user, queries, draft } = await open({
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }, { metric: 'total' }],
      },
    });
    await user.click(gear('bar'));
    const ran = queries();
    await user.click(within(panel()!).getByRole('tab', { name: 'Display' }));

    // Two series earn a legend without anybody asking for one; "None" is
    // how it is taken away again.
    expect(legend()).not.toBeNull();
    await choose(user, 'Legend', 'None');
    await waitFor(() => expect(legend()).toBeNull());

    // A bar chart writes its values without being asked, as Metabase's does
    // where they fit: one label per bar, each read as its own column reads
    // it, written short where it has only the bar's width.
    const box = () =>
      within(panel()!).getByRole('checkbox', { name: 'Value labels' });
    expect(box().getAttribute('aria-checked')).toBe('true');
    expect(draft().chart).not.toHaveProperty('labels');
    await waitFor(() => expect(labels().length).toBeGreaterThan(0));
    expect(
      document
        .querySelector('[data-slot="chart"]')!
        .getAttribute('data-labels'),
    ).toBe('on');
    expect([...labels()].map(text => text.textContent)).toEqual(
      expect.arrayContaining(['2', '1', '30', '10']),
    );

    // Turned off, they stay off: the choice is saved as a choice.
    fireEvent.click(box());
    await waitFor(() => expect(draft().chart.labels).toBe(false));
    await waitFor(() => expect(labels()).toHaveLength(0));
    expect(queries()).toBe(ran);
  });

  it('runs a query for the table’s totals row and for nothing else', async () => {
    const { user, queries } = await open(
      {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
      },
      { layout: 'table' },
    );
    // The table is a tile of the picker too, so "back to the table" and "as
    // a pie" are the one gesture.
    await user.click(gear('Table'));
    expect(within(panel()!).queryAllByRole('tab')).toEqual([]);
    const ran = queries();

    fireEvent.click(
      within(panel()!).getByRole('checkbox', { name: 'Totals row' }),
    );

    // The one option that is not presentation: the totals row is an
    // ungrouped query of its own, so it runs at once rather than waiting.
    await waitFor(() => expect(queries()).toBeGreaterThan(ran));
    // Its own ungrouped query, so the row under the groups covers them all
    // rather than summing what is on screen.
    await waitFor(() =>
      expect(
        screen.getByRole('table').querySelector('tfoot')?.textContent,
      ).toContain('40'),
    );
  });
});

/**
 * The drop, read off the two ids it reports: `@dnd-kit/dom` picks its target
 * by measuring boxes, and in jsdom every box is 0×0 at the origin — so the
 * gesture itself is a browser story, and what it means is tested here.
 */
describe('what a drop on the series list means', () => {
  const SERIES = [
    { metric: 'orders' },
    { metric: 'total' },
    { metric: 'average' },
  ];
  const drop = (source: string, target: string, canceled?: boolean) =>
    seriesDrop(
      SERIES,
      { source: { id: source }, target: { id: target } },
      canceled,
    );

  it('is the move between the two metrics it names', () => {
    expect(drop('average', 'orders')).toEqual({ from: 2, to: 0 });
  });

  it('is nothing when the drag was given up, or ended where it began', () => {
    expect(drop('average', 'orders', true)).toBeNull();
    expect(drop('total', 'total')).toBeNull();
    expect(seriesDrop(SERIES, { source: { id: 'total' } }, false)).toBeNull();
    expect(seriesDrop(SERIES, { target: { id: 'total' } }, false)).toBeNull();
  });

  /**
   * This list is not the only draggable thing the page holds, and an id from
   * somewhere else names no series this chart draws.
   */
  it('is nothing when an id names no series of this chart', () => {
    expect(drop('warehouse', 'orders')).toBeNull();
    expect(drop('orders', 'warehouse')).toBeNull();
  });
});

/**
 * The library's own sentences are built from the ids it carries — here the
 * metric each series draws — so every one of them is said in the
 * catalogue's words, with the alias turned back into the column title a
 * reader is looking at.
 */
describe('what a series drag says out loud', () => {
  const accessibility = seriesDragAccessibility(
    formattersFor(defaultMessages),
    alias => (alias === 'total' ? 'Sum of Amount' : alias),
  );
  const carrying = { operation: { source: { id: 'total' } } };

  it('names the series in the reader’s own words', () => {
    expect(accessibility.announcements.dragstart(carrying)).toBe(
      'Sum of Amount picked up',
    );
  });

  /** A completed drop is announced by the list, so it says nothing here. */
  it('speaks only when a series drag is given up', () => {
    expect(accessibility.announcements.dragend(carrying)).toBeUndefined();
    expect(
      accessibility.announcements.dragend({ ...carrying, canceled: true }),
    ).toBe('Move cancelled; Sum of Amount stayed where it was');
  });

  it('carries the instructions a reader is given on the handle', () => {
    expect(accessibility.screenReaderInstructions.draggable).toBe(
      defaultMessages['label.chart.series-instructions'],
    );
  });
});
