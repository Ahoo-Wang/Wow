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
} from '@ahoo-wang/wow-client';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * Where the keyboard stands after the screen has taken away the element it
 * was on (phase-2 review A1 / A2).
 *
 * A focus that falls to `<body>` is not «no focus»: the next Tab starts the
 * page again from the title bar, so a user taking three dimensions out of
 * the question walks the whole page three times. Every case here asserts
 * `document.activeElement` after one gesture, because that is the whole of
 * the contract — what the gesture did to the config is the suite of the
 * part it belongs to (`analysisTray`, `chartOptionsUi`, `havingRows`,
 * `elementsSlot`).
 */

const label = (key: keyof typeof defaultMessages) => defaultMessages[key];

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/** Two ways to cut it, two things to measure, and a having to keep by. */
function richDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
    ],
    analysis: {
      count: true,
      having: true,
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
          functions: [AggregationFunction.SUM],
        },
      ],
    },
  });
}

/** Orders holding items holding batches: a declared chain to collapse. */
function chainedDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      {
        name: 'items',
        label: 'Items',
        kind: 'array',
        elements: [
          { name: 'sku', label: 'SKU', kind: 'string' },
          { name: 'qty', label: 'Qty', kind: 'number' },
        ],
      },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
      elements: [
        {
          path: 'items',
          aggregations: [
            {
              field: 'sku',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            { field: 'qty', groups: [], functions: [AggregationFunction.SUM] },
          ],
        },
      ],
    },
  });
}

/** One group per warehouse, carrying every alias a case below may name. */
const GROUPED: RecordData[] = [
  { warehouse: 'CN', status: 'PENDING', orders: 2, total: 30 },
  { warehouse: 'US', status: 'SHIPPED', orders: 1, total: 10 },
];

function spyingSource(rows: RecordData[] = GROUPED): ViewSource {
  return testSource({
    aggregate: vi.fn((query: { groupBy?: unknown[] }) =>
      Promise.resolve(
        (query.groupBy?.length ?? 0) > 0
          ? rows.map(row => ({ ...row }))
          : [{ orders: 3, total: 40 }],
      ),
    ) as ViewSource['aggregate'],
  });
}

/** The workbench over one saved analysis view. */
function open({
  config = {},
  definition = richDefinition(),
  rows = GROUPED,
}: {
  config?: Partial<AnalysisViewConfig>;
  definition?: DataViewDefinition;
  rows?: RecordData[];
} = {}) {
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({
      instances: [{ ...analysisView, config: analysisConfig(config) }],
    }),
    resolveSource: () => spyingSource(rows),
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  return engine;
}

const active = () => document.activeElement as HTMLElement | null;

const picker = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-picker"]');
const options = () =>
  document.querySelector<HTMLElement>('[data-slot="chart-options"]');
const cards = (slot: string) => [
  ...document.querySelectorAll<HTMLElement>(`[data-slot="${slot}"]`),
];

/** The heading of whichever level of the panel is open. */
const headingOf = (panel: HTMLElement) =>
  within(panel).getByRole('heading', { level: 2 });

/** Presses the named button, wherever on the page it is. */
const press = (name: string) =>
  fireEvent.click(screen.getByRole('button', { name }));

describe('the visualization panel hands the keyboard on', () => {
  /**
   * A1. The panel takes the sidebar's column and replaces its whole
   * contents at every level change, so the press that made the change is
   * gone by the time the next level is drawn. Each level therefore takes
   * the keyboard to its own heading, and the way out gives it back to the
   * button the journey started at.
   */
  it('moves to each level’s heading, and back to the button that opened it', async () => {
    open({ config: { layout: 'chart' } });
    const visualize = await screen.findByRole('button', {
      name: label('label.analysis.visualize'),
    });

    fireEvent.click(visualize);

    await waitFor(() => expect(picker()).not.toBeNull());
    // The heading, not the first tile: the level is what changed, and a
    // heading is what says which one the keyboard landed on. It is off the
    // Tab route otherwise.
    const pickerHeading = headingOf(picker()!);
    expect(pickerHeading.textContent).toBe(label('label.chart.picker'));
    expect(pickerHeading.getAttribute('tabindex')).toBe('-1');
    await waitFor(() => expect(active()).toBe(pickerHeading));

    press(label('label.chart.options').replace('{name}', 'bar'));

    await waitFor(() => expect(options()).not.toBeNull());
    await waitFor(() => expect(active()).toBe(headingOf(options()!)));

    press(label('label.chart.options-back'));

    await waitFor(() => expect(picker()).not.toBeNull());
    // Back from the options is back to the button that went there, the one
    // the user left by — not the top of the level they are returning to.
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', {
          name: label('label.chart.options').replace('{name}', 'bar'),
        }),
      ),
    );

    press(label('label.chart.picker-back'));

    // Closed, and the keyboard is where the user left it: the toolbar
    // button that opened the panel, which is still on screen.
    await waitFor(() => expect(picker()).toBeNull());
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', { name: label('label.analysis.visualize') }),
      ),
    );
  });

  /**
   * The same press through the toolbar's own toggle: `aria-pressed` says
   * the panel is open, and pressing it again closes it — which must not be
   * read as «the level changed» and send the focus to a heading that is no
   * longer there.
   */
  it('leaves the keyboard on the toggle when the toolbar closes the panel', async () => {
    open({ config: { layout: 'chart' } });
    const visualize = await screen.findByRole('button', {
      name: label('label.analysis.visualize'),
    });

    fireEvent.click(visualize);
    await waitFor(() => expect(picker()).not.toBeNull());
    fireEvent.click(
      screen.getByRole('button', { name: label('label.analysis.visualize') }),
    );

    await waitFor(() => expect(picker()).toBeNull());
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', { name: label('label.analysis.visualize') }),
      ),
    );
  });

  /**
   * The toolbar no longer goes with the rows (2026-09-23 audit): a result
   * that comes back empty while the panel is open keeps the button that
   * opened it, and closing the panel hands the keyboard back to it.
   */
  it('hands the keyboard back to the button when the result comes back empty', async () => {
    const rows = GROUPED.map(row => ({ ...row }));
    const engine = open({ config: { layout: 'chart' }, rows });
    fireEvent.click(
      await screen.findByRole('button', {
        name: label('label.analysis.visualize'),
      }),
    );
    await waitFor(() => expect(picker()).not.toBeNull());

    rows.length = 0;
    act(() => engine.openRuntimes()[0]!.refresh());
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="analysis-empty"]'),
      ).not.toBeNull(),
    );

    press(label('label.chart.picker-back'));

    await waitFor(() => expect(picker()).toBeNull());
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', { name: label('label.analysis.visualize') }),
      ),
    );
  });

  /** Nothing is focused before the first press: the page opens as it opens. */
  it('takes no focus while the panel has never been opened', async () => {
    open({ config: { layout: 'chart' } });
    await screen.findByRole('button', {
      name: label('label.analysis.visualize'),
    });

    expect(active()).toBe(document.body);
  });
});

describe('a removal leaves the keyboard in the list', () => {
  /**
   * A2. The item that takes the removed one's place is «where the row
   * was», so that is where the keyboard stays — and the next removal is
   * one press away rather than a walk down the page.
   */
  it('moves to the card that takes the removed one’s place', async () => {
    open({
      config: {
        groups: [
          { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
          { alias: 'status', field: 'status', type: 'TERMS' },
        ],
      },
    });
    await openTray();
    expect(cards('dimension-card')).toHaveLength(2);

    press(label('label.analysis.remove-group').replace('{name}', 'Warehouse'));

    await waitFor(() => expect(cards('dimension-card')).toHaveLength(1));
    const remaining = cards('dimension-card')[0]!;
    expect(remaining.contains(active())).toBe(true);
  });

  /** The last one out leaves the slot's own way back in. */
  it('moves to the slot’s add button when the last card goes', async () => {
    open();
    await openTray();

    press(label('label.analysis.remove-group').replace('{name}', 'Warehouse'));

    await waitFor(() => expect(cards('dimension-card')).toHaveLength(0));
    expect(active()).toBe(
      screen.getByRole('button', { name: label('label.analysis.add-group') }),
    );
  });

  /** The metrics slot answers the same way; its last card cannot go at all. */
  it('keeps the keyboard in the metrics slot', async () => {
    open({
      config: {
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'total',
            type: 'NUMERIC',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      },
    });
    await openTray();
    expect(cards('metric-card')).toHaveLength(2);

    press(
      label('label.analysis.remove-metric').replace(
        '{name}',
        label('label.analysis.row-count'),
      ),
    );

    await waitFor(() => expect(cards('metric-card')).toHaveLength(1));
    expect(cards('metric-card')[0]!.contains(active())).toBe(true);
  });

  /** 「只保留」 is rows of one comparison each, and answers the same rule. */
  it('moves to the row under the one kept out of the having', async () => {
    open({
      config: {
        having: {
          type: 'AND',
          operands: [
            { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 1 },
            { type: 'CONDITION', metric: 'total', operator: 'GT', value: 2 },
          ],
        },
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'total',
            type: 'NUMERIC',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      },
    });
    await openTray();
    await waitFor(() => expect(cards('having-row')).toHaveLength(2));

    fireEvent.click(
      within(cards('having-row')[0]!).getByRole('button', {
        name: label('label.analysis.remove-having'),
      }),
    );

    await waitFor(() => expect(cards('having-row')).toHaveLength(1));
    expect(cards('having-row')[0]!.contains(active())).toBe(true);

    fireEvent.click(
      within(cards('having-row')[0]!).getByRole('button', {
        name: label('label.analysis.remove-having'),
      }),
    );

    await waitFor(() => expect(cards('having-row')).toHaveLength(0));
    expect(active()).toBe(document.querySelector('[data-slot="add-having"]'));
  });

  /** A level collapsed leaves the keyboard on the way to expand again. */
  it('moves to 「expand into」 when the last level collapses', async () => {
    open({ definition: chainedDefinition() });
    await openTray();
    const expand = label('label.analysis.expand-into').replace(
      '{name}',
      'Items',
    );
    press(expand);
    await waitFor(() => expect(cards('element-card')).toHaveLength(1));

    press(label('label.analysis.collapse').replace('{name}', 'Items'));

    await waitFor(() => expect(cards('element-card')).toHaveLength(0));
    expect(active()).toBe(screen.getByRole('button', { name: expand }));
  });
});

describe('the options panel keeps the keyboard on the list it moved', () => {
  /** Opens the options of the chart type the saved config draws. */
  async function openOptions(
    config: Partial<AnalysisViewConfig>,
    type: string,
    rows: RecordData[] = GROUPED,
  ) {
    const engine = open({ config: { layout: 'chart', ...config }, rows });
    fireEvent.click(
      await screen.findByRole('button', {
        name: label('label.analysis.visualize'),
      }),
    );
    await waitFor(() => expect(picker()).not.toBeNull());
    press(label('label.chart.options').replace('{name}', type));
    await waitFor(() => expect(options()).not.toBeNull());
    return engine;
  }

  /**
   * A2, the move half. A stage carried to the end disables the very button
   * that carried it, so the keyboard has to be put somewhere by hand — and
   * the somewhere is the other direction on the same stage, which is the
   * only thing left to press. Keeping the button enabled as a no-op was
   * the alternative, and a button that does nothing reads worse than a
   * focus that steps sideways.
   */
  it('keeps the keyboard on the moving stage, and hands it over at the end', async () => {
    await openOptions(
      {
        groups: [],
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'total',
            type: 'NUMERIC',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        chart: {
          type: 'funnel',
          funnel: {
            stages: {
              from: 'metrics',
              items: [{ metric: 'orders' }, { metric: 'total' }],
            },
          },
        },
      },
      'funnel',
      [{ orders: 3, total: 40 }],
    );
    const stages = () =>
      cards('stage-card').map(card => card.getAttribute('data-stage'));
    expect(stages()).toEqual(['orders', 'total']);

    const count = label('label.analysis.row-count');
    press(label('label.chart.move-down').replace('{name}', count));

    // It landed last, where「move down」is disabled — so「move up」on that
    // same stage holds the keyboard and the next press moves it back.
    await waitFor(() => expect(stages()).toEqual(['total', 'orders']));
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', {
          name: label('label.chart.move-up').replace('{name}', count),
        }),
      ),
    );

    press(label('label.chart.move-up').replace('{name}', count));

    // Back in the middle of the list, the button that made the move keeps
    // the keyboard: a stage can be walked all the way up without leaving.
    await waitFor(() => expect(stages()).toEqual(['orders', 'total']));
    await waitFor(() =>
      expect(active()).toBe(
        screen.getByRole('button', {
          name: label('label.chart.move-down').replace('{name}', count),
        }),
      ),
    );
  });

  /**
   * A funnel of a group's values can drop a stage out of its order; what
   * the rows hold and the order does not name goes to the end of the list
   * (`stageValues`), so the card at that index is another stage — and that
   * is the one the keyboard stays on.
   */
  it('moves to the stage that takes the removed one’s place', async () => {
    await openOptions(
      {
        groups: [{ alias: 'status', field: 'status', type: 'TERMS' }],
        metrics: [{ alias: 'orders', type: 'COUNT' }],
        chart: {
          type: 'funnel',
          funnel: {
            stages: {
              from: 'group',
              category: 'status',
              value: 'orders',
              order: ['PENDING', 'SHIPPED', 'PAID'],
            },
          },
        },
      },
      'funnel',
      [
        { status: 'PENDING', orders: 5 },
        { status: 'SHIPPED', orders: 3 },
        { status: 'PAID', orders: 1 },
      ],
    );
    const stages = () =>
      cards('stage-card').map(card => card.getAttribute('data-stage'));
    expect(stages()).toEqual(['PENDING', 'SHIPPED', 'PAID']);

    fireEvent.click(
      within(cards('stage-card')[0]!).getByRole('button', {
        name: label('label.chart.remove-stage').replace('{name}', 'PENDING'),
      }),
    );

    await waitFor(() =>
      expect(stages()).toEqual(['SHIPPED', 'PAID', 'PENDING']),
    );
    expect(cards('stage-card')[0]!.contains(active())).toBe(true);
  });

  /** A series taken out leaves the keyboard on the one under it. */
  it('moves to the series that takes the removed one’s place', async () => {
    await openOptions(
      {
        metrics: [
          { alias: 'orders', type: 'COUNT' },
          {
            alias: 'total',
            type: 'NUMERIC',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        chart: {
          type: 'bar',
          cartesian: {
            x: 'warehouse',
            series: [{ metric: 'orders' }, { metric: 'total' }],
          },
        },
      },
      'bar',
    );
    await waitFor(() => expect(cards('series-card')).toHaveLength(2));

    fireEvent.click(
      within(cards('series-card')[0]!).getByRole('button', {
        name: label('label.chart.remove-series').replace(
          '{name}',
          label('label.analysis.row-count'),
        ),
      }),
    );

    await waitFor(() => expect(cards('series-card')).toHaveLength(1));
    expect(cards('series-card')[0]!.contains(active())).toBe(true);
  });

  /** The last reference line out leaves the keyboard on 「add」. */
  it('moves to 「add reference line」 when the last line goes', async () => {
    await openOptions(
      {
        chart: {
          type: 'bar',
          cartesian: {
            x: 'warehouse',
            series: [{ metric: 'orders' }],
            referenceLines: [{ axis: 'left', value: 2 }],
          },
        },
      },
      'bar',
    );
    fireEvent.click(
      within(options()!).getByRole('tab', {
        name: label('label.chart.tab.display'),
      }),
    );
    await waitFor(() => expect(cards('reference-line-card')).toHaveLength(1));

    press(label('label.chart.remove-reference-line'));

    await waitFor(() => expect(cards('reference-line-card')).toHaveLength(0));
    expect(active()).toBe(
      document.querySelector('[data-slot="add-reference-line"]'),
    );
  });
});
