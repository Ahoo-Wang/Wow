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

import { AggregationFunction } from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { describedText } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The amount metric beside the record count, so the result that runs has one
 * dimension and two metrics: the shape that makes a scatter drawable, a
 * heatmap short of a dimension and a metric card impossible.
 */
const AMOUNT: AnalysisMetric = {
  alias: 'amount_sum',
  type: 'NUMERIC',
  function: `${AggregationFunction.SUM}`,
  expression: { type: 'FIELD', field: 'amount' },
};

function viewOf(overrides: Partial<AnalysisViewConfig> = {}): ViewInstance {
  return {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config: analysisConfig({
      metrics: [analysisConfig().metrics[0], AMOUNT],
      ...overrides,
    }),
  };
}

function setup(
  instance: ViewInstance = viewOf(),
  definition: DataViewDefinition = ordersDefinition(),
  source: ViewSource = testSource(),
) {
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => source,
  });
  return { engine, source };
}

/** The workbench over one saved analysis view, waited for its first result. */
async function open(instance?: ViewInstance, answer?: ViewSource) {
  const { engine, source } = setup(instance, undefined, answer);
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  return { engine, source };
}

const label = (key: keyof typeof defaultMessages) => defaultMessages[key];

const panel = () =>
  document.querySelector<HTMLElement>('[data-slot="view-panel"]');
const sidebar = () =>
  document.querySelector<HTMLElement>('[data-slot="view-sidebar"]');
const toggle = () =>
  document.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!;

/** One tile of the picker, by the chart type it stands for. */
const tile = (type: string) =>
  document.querySelector<HTMLButtonElement>(
    `[data-slot="chart-tile"][data-chart-type="${type}"]`,
  )!;

/** Opens the visualization panel from the result's toolbar. */
function visualize() {
  fireEvent.click(
    screen.getByRole('button', { name: label('label.analysis.visualize') }),
  );
}

/** How many aggregations the source has been asked for so far. */
const queries = (source: ViewSource) =>
  vi.mocked(source.aggregate).mock.calls.length;

describe('the visualization panel', () => {
  it('takes the sidebar column, and the view list comes back with it', async () => {
    await open();
    // The column is the list's until the panel is asked for: a slot filled
    // with `false` would have held it open from the first render.
    expect(sidebar()).not.toBeNull();
    expect(panel()).toBeNull();

    visualize();

    // The list is navigation, and navigation is not what a chart is picked
    // with: one column, two occupants, never both (D20 屏 I).
    const opened = panel()!;
    expect(sidebar()).toBeNull();
    expect(
      within(opened).getByRole('radiogroup', {
        name: label('label.chart.picker'),
      }),
    ).toBeDefined();
    // The button says it is the one holding the panel open.
    expect(
      screen
        .getByRole('button', { name: label('label.analysis.visualize') })
        .getAttribute('aria-pressed'),
    ).toBe('true');

    // The panel carries its own way back — there is no list to press.
    fireEvent.click(
      within(opened).getByRole('button', {
        name: label('label.chart.picker-back'),
      }),
    );
    await waitFor(() => expect(sidebar()).not.toBeNull());
    expect(panel()).toBeNull();
  });

  it('marks the chosen type, the recommended one, and why a tile is greyed', async () => {
    await open(viewOf({ layout: 'chart' }));
    visualize();

    // The saved config draws a bar, so that is what is chosen.
    expect(tile('bar').getAttribute('aria-checked')).toBe('true');
    expect(tile('pie').getAttribute('aria-checked')).toBe('false');
    // A view looked at as a table is the table tile's, not the chart's.
    expect(tile('table').getAttribute('aria-checked')).toBe('false');

    // One dimension that is not a date reads best as bars, and the mark says
    // so in words rather than by a ring nobody can name.
    expect(tile('bar').hasAttribute('data-recommended')).toBe(true);
    expect(
      tile('bar').querySelector('[data-slot="chart-recommended"]')?.textContent,
    ).toBe(label('label.chart.recommended'));
    expect(tile('pie').hasAttribute('data-recommended')).toBe(false);

    // A tile the shape cannot fill is greyed and says what it lacks, in the
    // analyst's words, under itself and in its own description.
    const heatmap = tile('heatmap');
    expect(heatmap.getAttribute('aria-disabled')).toBe('true');
    expect(
      heatmap.querySelector('[data-slot="chart-reason"]')?.textContent,
    ).toBe(label('chart.fit.needs-two-dimensions'));
    expect(describedText(heatmap)).toBe(
      label('chart.fit.needs-two-dimensions'),
    );
    // And the mark is read as well, which an `aria-label` over the tile
    // used to swallow: the name is the word on it, the description is
    // everything the tile adds to that word.
    expect(describedText(tile('bar'))).toBe(label('label.chart.recommended'));
    // One number is not what a shape with a dimension is.
    expect(tile('metric').getAttribute('aria-disabled')).toBe('true');
    expect(
      tile('metric').querySelector('[data-slot="chart-reason"]')?.textContent,
    ).toBe(label('chart.fit.needs-no-dimension'));

    // Two metrics are what a scatter plots against each other, so it exists.
    expect(tile('scatter').getAttribute('aria-disabled')).toBeNull();
    expect(
      tile('scatter').querySelector('[data-slot="chart-reason"]'),
    ).toBeNull();

    // The table is a tile too, and it is the last of them.
    const tiles = [
      ...document.querySelectorAll('[data-slot="chart-tile"]'),
    ].map(found => found.getAttribute('data-chart-type'));
    expect(tiles[tiles.length - 1]).toBe('table');
  });

  it('redraws the rows that ran, without asking the source again', async () => {
    const { source } = await open(viewOf({ layout: 'chart' }));
    await screen.findByRole('img', { name: /^bar:/ });
    visualize();
    const before = queries(source);

    fireEvent.click(tile('pie'));

    // The same answer, drawn as a pie: the picture changes and the query
    // does not (`ANALYSIS_PRESENTATION_MEMBERS`).
    await screen.findByRole('img', { name: /^pie:/ });
    expect(queries(source)).toBe(before);
    expect(tile('pie').getAttribute('aria-checked')).toBe('true');
    expect(tile('bar').getAttribute('aria-checked')).toBe('false');
    // And it is no pending edit: there is nothing left to apply, so the
    // title bar's fold wears no dot.
    expect(toggle().querySelector('[data-slot="pending-dot"]')).toBeNull();
  });

  /**
   * The chart is drawn from the draft, so the draft's chart has to reach the
   * drawing as it is — not as `fitChartSlots` would build it from nothing.
   * A saved cartesian that draws one of two metrics is a choice its author
   * made; re-fitting it on every render opened the series list back up to
   * every metric, and the saved chart could never stay the saved chart.
   * Fitting is for a shape that moved under the chart, and this one has not.
   */
  it('draws a saved chart as it was saved, narrowed slots and all', async () => {
    await open(
      viewOf({
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
        },
      }),
    );

    // One measure in the name, because one series is drawn.
    const drawing = await screen.findByRole('img', { name: /^bar:/ });
    expect(drawing.getAttribute('aria-label')).toBe(
      `bar: ${label('label.analysis.row-count')} by Warehouse`,
    );
  });

  it('puts the result back in a table through the table tile', async () => {
    const { source } = await open(viewOf({ layout: 'chart' }));
    await screen.findByRole('img', { name: /^bar:/ });
    visualize();
    const before = queries(source);

    fireEvent.click(tile('table'));

    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(screen.queryByRole('img', { name: /^bar:/ })).toBeNull();
    expect(tile('table').getAttribute('aria-checked')).toBe('true');
    expect(queries(source)).toBe(before);
    expect(toggle().querySelector('[data-slot="pending-dot"]')).toBeNull();
  });

  it('lets a greyed tile be pressed and does nothing about it', async () => {
    await open(viewOf({ layout: 'chart' }));
    await screen.findByRole('img', { name: /^bar:/ });
    visualize();

    fireEvent.click(tile('heatmap'));

    expect(tile('heatmap').getAttribute('aria-checked')).toBe('false');
    expect(tile('bar').getAttribute('aria-checked')).toBe('true');
    expect(await screen.findByRole('img', { name: /^bar:/ })).toBeDefined();
  });

  it('moves the choice and the focus over the tiles it can draw', async () => {
    await open(viewOf({ layout: 'chart' }));
    visualize();

    // Only the chosen tile is in the tab order, as a radiogroup's is.
    expect(tile('bar').tabIndex).toBe(0);
    expect(tile('line').tabIndex).toBe(-1);

    tile('bar').focus();
    fireEvent.keyDown(tile('bar'), { key: 'ArrowRight' });
    expect(tile('line').getAttribute('aria-checked')).toBe('true');
    expect(document.activeElement).toBe(tile('line'));

    // From area the next available tile is combo, and the one after it is
    // pie: the greyed heatmap and metric are stepped over, never chosen.
    fireEvent.keyDown(tile('line'), { key: 'ArrowRight' });
    fireEvent.keyDown(tile('area'), { key: 'ArrowRight' });
    fireEvent.keyDown(tile('combo'), { key: 'ArrowRight' });
    expect(tile('pie').getAttribute('aria-checked')).toBe('true');
    fireEvent.keyDown(tile('pie'), { key: 'ArrowRight' });
    expect(tile('heatmap').getAttribute('aria-checked')).toBe('false');
    expect(tile('scatter').getAttribute('aria-checked')).toBe('true');
    expect(document.activeElement).toBe(tile('scatter'));

    // Backwards the same way, and Space chooses where the keys stopped.
    fireEvent.keyDown(tile('scatter'), { key: 'ArrowLeft' });
    expect(tile('pie').getAttribute('aria-checked')).toBe('true');
    fireEvent.keyDown(tile('pie'), { key: ' ' });
    expect(tile('pie').getAttribute('aria-checked')).toBe('true');
    await screen.findByRole('img', { name: /^pie:/ });
  });

  /**
   * A greyed tile is `aria-disabled`, never the `disabled` attribute: the
   * keys step over it because there is nothing to choose, but it stays in
   * the accessible tree with its reason in its name, and a reader who could
   * not reach it could not hear why it is out of reach — which is the only
   * thing it has to say.
   */
  it('greys a tile without taking it out of the reader’s reach', async () => {
    await open();
    visualize();

    const heatmap = tile('heatmap');
    expect(heatmap.hasAttribute('disabled')).toBe(false);
    expect(heatmap.getAttribute('aria-disabled')).toBe('true');
    // Named by the word written on it, described by why it is out of reach.
    expect(
      screen.getByRole('radio', { name: label('label.chart.type.heatmap') }),
    ).toBe(heatmap);
    expect(describedText(heatmap)).toBe(
      label('chart.fit.needs-two-dimensions'),
    );
    // Still a focusable element, even though the roving tab index leaves
    // the group's one tab stop on the chosen tile.
    heatmap.focus();
    expect(document.activeElement).toBe(heatmap);
  });

  /**
   * The tiles only pick; the way on to the chosen type's options is one
   * labelled button under them (2026-09-23 review). It used to be a 24px
   * gear hanging off the chosen tile's corner, which covered nothing and
   * which nobody saw or understood. Its pixels are measured in a browser
   * (`VisualizePanel`); this pins what it is and where the keyboard goes.
   */
  it('opens the chosen type’s options from one labelled button under the tiles', async () => {
    await open(viewOf({ layout: 'chart' }));
    visualize();
    const named = (type: string) =>
      label('label.chart.options').replace('{name}', type);
    const buttons = () => [
      ...panel()!.querySelectorAll<HTMLButtonElement>(
        '[data-slot="chart-options-open"]',
      ),
    ];

    // One, named by the type it opens, in the words the page is headed with;
    // after the tile group, inside no tile.
    expect(buttons()).toHaveLength(1);
    const button = buttons()[0]!;
    expect(button.textContent).toBe(named(label('label.chart.type.bar')));
    expect(screen.getByRole('button', { name: named('bar') })).toBe(button);
    const group = screen.getByRole('radiogroup');
    expect(group.contains(button)).toBe(false);
    expect(
      group.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // Each tile is one button, with nothing inside it but its words.
    for (const each of group.querySelectorAll('[data-slot="chart-tile"]'))
      expect(each.querySelector('button')).toBeNull();
    // In the Tab order, where the group's one stop is not.
    expect(button.tabIndex).toBe(0);

    // A pick moves the choice and renames the button; it opens nothing.
    fireEvent.click(tile('pie'));
    expect(buttons()).toHaveLength(1);
    expect(buttons()[0]!.textContent).toBe(named('pie'));
    expect(document.querySelector('[data-slot="chart-options"]')).toBeNull();

    // The table's options are its totals row, and the button says so.
    fireEvent.click(tile('table'));
    expect(buttons()[0]!.textContent).toBe(named(label('label.layout.table')));

    // The press opens the options; back comes back to the button.
    fireEvent.click(buttons()[0]!);
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="chart-options"]'),
      ).not.toBeNull(),
    );
    fireEvent.click(
      screen.getByRole('button', { name: label('label.chart.options-back') }),
    );
    await waitFor(() => expect(document.activeElement).toBe(buttons()[0]));
  });
});

describe('a layout is a redraw, not a run', () => {
  async function editor(instance?: ViewInstance) {
    const { engine } = setup(instance);
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, analysis: useAnalysisEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return result;
  }

  it('edits the draft and leaves the applied config alone until Apply', async () => {
    const result = await editor();
    const runtime = result.current.opened.runtime!;
    await waitFor(() => expect(runtime.getSnapshot().result).not.toBeNull());

    result.current.analysis.setLayout('chart');

    await waitFor(() => expect(result.current.analysis.layout).toBe('chart'));
    const state = runtime.getSnapshot();
    expect((state.draft as AnalysisViewConfig).layout).toBe('chart');
    // The rows on screen answer the same question they answered before, so
    // the applied config keeps the layout it ran with until an Apply.
    expect((state.applied as AnalysisViewConfig).layout).toBe('table');
    // Presentation, so no dot: the two configs differ, and there is nothing
    // to run about the difference.
    expect(result.current.analysis.pending).toBe(false);

    result.current.analysis.submit();
    await waitFor(() =>
      expect((runtime.getSnapshot().applied as AnalysisViewConfig).layout).toBe(
        'chart',
      ),
    );
  });

  it('counts a chart type as no pending edit either', async () => {
    const result = await editor(viewOf({ layout: 'chart' }));
    const runtime = result.current.opened.runtime!;
    await waitFor(() => expect(runtime.getSnapshot().result).not.toBeNull());

    result.current.analysis.setChartType('pie');

    await waitFor(() => expect(result.current.analysis.chart.type).toBe('pie'));
    const state = runtime.getSnapshot();
    const draft = state.draft as AnalysisViewConfig;
    const applied = state.applied as AnalysisViewConfig;
    expect(applied.chart.type).toBe('bar');
    // The chart is the whole of the difference: every other member of the
    // question is what it was.
    expect({ ...draft, chart: applied.chart }).toEqual(applied);
    expect(result.current.analysis.pending).toBe(false);
  });
});

describe('a funnel is offered where it draws', () => {
  /** Two warehouses: two groups, so two stages a funnel can start from. */
  const twoWarehouses = () =>
    testSource({
      aggregate: vi.fn(() =>
        Promise.resolve([
          { warehouse: 'CN', orders: 5, amount_sum: 30 },
          { warehouse: 'JP', orders: 2, amount_sum: 10 },
        ]),
      ),
    });

  /**
   * One group is one stage, and a funnel of one stage has nothing to convert
   * from. It used to be offered anyway, and picking it drew nothing but
   * 「漏斗至少要有两个阶段」 (the 2026-09-23 audit): the tile is greyed and
   * says why, as every other tile the shape cannot fill does.
   */
  it('greys the funnel over one group, and says why', async () => {
    await open(viewOf({ layout: 'chart' }));
    await screen.findByRole('img', { name: /^bar:/ });
    visualize();

    const funnel = tile('funnel');
    expect(funnel.getAttribute('aria-disabled')).toBe('true');
    expect(
      funnel.querySelector('[data-slot="chart-reason"]')?.textContent,
    ).toBe(label('chart.fit.needs-two-stages'));
    expect(describedText(funnel)).toBe(label('chart.fit.needs-two-stages'));

    fireEvent.click(funnel);
    expect(funnel.getAttribute('aria-checked')).toBe('false');
    expect(await screen.findByRole('img', { name: /^bar:/ })).toBeDefined();
  });

  it('draws the funnel at once where the rows give it stages', async () => {
    await open(viewOf({ layout: 'chart' }), twoWarehouses());
    await screen.findByRole('img', { name: /^bar:/ });
    visualize();
    expect(tile('funnel').getAttribute('aria-disabled')).toBeNull();

    fireEvent.click(tile('funnel'));

    // The stages are the groups in the order they came, filled on the pick:
    // the reading table beside the drawing lists them as it draws them.
    await screen.findByRole('img', { name: /^funnel:/ });
    const reading = document.querySelector<HTMLElement>(
      '[data-slot="chart-reading"] tbody',
    )!;
    expect(
      [...reading.querySelectorAll('tr')].map(
        row => row.querySelector('th, td')?.textContent,
      ),
    ).toEqual(['CN', 'JP']);
    // Nothing is left to fix, so the status line says nothing.
    expect(
      screen.queryByRole('button', {
        name: label('label.analysis.open-chart-options'),
      }),
    ).toBeNull();
  });

  /**
   * A chart the config cannot draw is the visualization panel's to fix, and
   * the tray holds nothing about it: the strip's way out used to be
   * 「打开分析」, which opened the tray. A saved funnel of one stage is such a
   * chart — the panel no longer offers a way to make one, but a store can
   * still hold it.
   */
  it('sends a chart that will not draw to the visualization panel, not to the tray', async () => {
    const { engine } = setup(
      viewOf({
        layout: 'chart',
        chart: {
          type: 'funnel',
          funnel: {
            stages: {
              from: 'group',
              category: 'warehouse',
              value: 'orders',
              order: ['CN'],
            },
          },
        },
      }),
    );
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );

    const way = await screen.findByRole('button', {
      name: label('label.analysis.open-chart-options'),
    });
    expect(
      screen.queryByRole('button', {
        name: label('label.analysis.open-editor'),
      }),
    ).toBeNull();

    fireEvent.click(way);

    await waitFor(() =>
      expect(
        within(panel()!).getByRole('radiogroup', {
          name: label('label.chart.picker'),
        }),
      ).toBeDefined(),
    );
  });

  it('keeps the tray as the way out of a finding that is not the chart’s', async () => {
    const { engine } = setup(viewOf({ limit: 0 }));
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );

    expect(
      await screen.findByRole('button', {
        name: label('label.analysis.open-editor'),
      }),
    ).toBeDefined();
  });
});
