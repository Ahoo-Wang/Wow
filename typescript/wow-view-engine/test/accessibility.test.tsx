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

import { useRef, useState } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import axe from 'axe-core';
import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  AnalysisWorkbench,
  DashboardWorkbench,
  defaultMessages,
  EmbeddedView,
  formatMessage,
  RecordPagination,
  RecordTable,
  RecordWorkbench,
  useViewExpansion,
  ViewSurface,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { recordTableController } from './fixtures/ui.js';

/**
 * Axe over the three default workbenches.
 *
 * Storybook runs the same engine against every story in a real browser, which
 * is the thorough check; this one runs in jsdom beside the unit tests, so a
 * rule a component breaks is reported by `pnpm test` rather than by CI twenty
 * minutes later. jsdom computes no layout, so the rules that need geometry —
 * colour contrast above all — are out of its reach and stay Storybook's.
 */
afterEach(cleanup);

const pendingOrders: ViewInstance = {
  id: 'pending',
  definitionId: 'orders',
  title: 'Pending',
  scope: 'shared',
  revision: '1',
  config: recordConfig(),
};

/** The same rows under two sorted columns, with both summary scopes. */
const sortedOrders: ViewInstance = {
  id: 'sorted',
  definitionId: 'orders',
  title: 'Sorted',
  scope: 'shared',
  revision: '1',
  config: recordConfig({
    sort: [
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ],
    summaries: [{ field: 'amount', fn: 'SUM' }],
  }),
};

/**
 * A numeric `IN`, which is a list rather than a pair of ends: one chip per
 * value with a remove button of its own, an entry field, and the button that
 * commits what is in it.
 */
const amountsIn: ViewInstance = {
  id: 'amounts',
  definitionId: 'orders',
  title: 'Amounts',
  scope: 'shared',
  revision: '1',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [{ field: 'amount', operator: 'IN', value: [100, 200, 300] }],
    },
  }),
};

/**
 * A calendar condition on a `withTime` field, which draws the clock beside
 * the calendar: two more controls in a portalled popover, each of which has
 * a name to get wrong.
 */
const createdBetween: ViewInstance = {
  id: 'created',
  definitionId: 'orders',
  title: 'Created',
  scope: 'shared',
  revision: '1',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          // Wide enough to keep the rows on screen: this case is about the
          // control's names, and an empty result draws no table to wait for.
          value: { type: 'absolute', from: '2020-01-01', to: '2030-12-31' },
        },
      ],
    },
  }),
};

const warehouseTotals: ViewInstance = {
  id: 'totals',
  definitionId: 'orders',
  title: 'Totals',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({
    layout: 'table',
    table: { columns: [], totals: true },
  }),
};

/** The same aggregation drawn rather than tabulated. */
const warehouseChart: ViewInstance = {
  id: 'drawn',
  definitionId: 'orders',
  title: 'Drawn',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({ layout: 'chart' }),
};

const overview: ViewInstance = {
  id: 'overview-1',
  definitionId: 'overview',
  title: 'Overview',
  scope: 'personal',
  revision: '1',
  config: dashboardConfig({
    fields: [{ name: 'region', label: 'Region', kind: 'string' }],
    panels: [
      {
        id: 'rows',
        kind: 'view',
        title: 'Rows',
        instanceId: 'pending',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 0, w: 6, h: 4 },
      },
      {
        id: 'note',
        kind: 'markdown',
        title: 'Note',
        content: '# Weekly review',
        layout: { x: 6, y: 0, w: 6, h: 2 },
      },
    ],
  }),
};

/** The orders capability plus a field that carries a time of day. */
function timedOrdersDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      ...ordersDefinition().fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
  });
}

function engineWith(
  instances: ViewInstance[],
  definition: DataViewDefinition = ordersDefinition(),
): ViewEngine {
  return new ViewEngine({
    definitions: [definition, overviewDefinition()],
    store: new MemoryViewStore({ instances }),
    resolveSource: () => testSource(),
  });
}

/** Rule ids that were violated, with how many nodes each one covers. */
async function violations(node: HTMLElement): Promise<string[]> {
  const result = await axe.run(node, {
    resultTypes: ['violations'],
    // `region` asks that every piece of content sit inside a landmark, which
    // is a question about a whole page. These suites render a fragment into a
    // bare body, and every popup this package opens is portalled to that body
    // as a sibling of the surface — so the rule fires on the harness rather
    // than on anything a host would ship.
    rules: { region: { enabled: false } },
  });
  return result.violations.map(
    found => `${found.id} (${found.nodes.length} node(s))`,
  );
}

/**
 * The view title is a heading, and the region it names says so.
 *
 * As a bare span it was a line of text like any other: a screen reader
 * navigating by headings jumped straight past the one thing that says which
 * view is open, and the region holding the whole view had no name at all.
 */
describe('the open view names the region it is drawn in', () => {
  it('renders the view title as a heading the main region points at', async () => {
    const { container } = render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([pendingOrders])}
          definitionId="orders"
          instanceId="pending"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(container.querySelector('table')).toBeTruthy());

    const title = container.querySelector('[data-slot="view-title"]')!;
    expect(title.tagName).toBe('H2');
    expect(title.textContent).toBe('Pending');

    const main = container.querySelector('main')!;
    expect(main.getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.id).not.toBe('');
  });

  /**
   * An id that addresses nothing is a broken label rather than a missing
   * one, so the region is left unnamed while there is no title to name it.
   */
  it('leaves the region unnamed when the view could not be opened', async () => {
    const { container } = render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([pendingOrders])}
          definitionId="orders"
          instanceId="no-such-view"
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(container.querySelector('[role="alert"]')).toBeTruthy(),
    );

    expect(
      container.querySelector('main')!.hasAttribute('aria-labelledby'),
    ).toBe(false);
    expect(await violations(container)).toEqual([]);
  });
});

/**
 * The states a workbench only reaches after a click. Axe is run over the
 * whole document rather than the container, because every popup this package
 * opens is portalled to the body — checking the container alone would pass a
 * menu nobody looked at.
 */
describe('the states behind a click pass axe', () => {
  /** The record workbench, opened on a view with rows on screen. */
  async function workbench(
    instance: ViewInstance = pendingOrders,
    definition?: DataViewDefinition,
  ) {
    const user = userEvent.setup();
    render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([instance], definition)}
          definitionId="orders"
          instanceId={instance.id}
        />
      </ViewSurface>,
    );
    await screen.findByRole('table');
    return user;
  }

  /** The editor unfolded, which is where the condition pills are. */
  async function openEditor(
    instance?: ViewInstance,
    definition?: DataViewDefinition,
  ) {
    const user = await workbench(instance, definition);
    // A saved view opens with the editor folded, so the panel is not in the
    // document until the title bar's toggle is pressed.
    await user.click(
      screen.getByRole('button', {
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    );
    return user;
  }

  it('the sidebar collapsed, with the switcher in the title bar', async () => {
    const user = await workbench();
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.collapse-sidebar'],
      }),
    );

    expect(await violations(document.body)).toEqual([]);
  });

  it('the view switcher open', async () => {
    const user = await workbench();
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.collapse-sidebar'],
      }),
    );
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.switch-view'],
      }),
    );
    await screen.findByRole('menu');

    expect(await violations(document.body)).toEqual([]);
  });

  /**
   * The freshness control is a split button, and the half that opens is a
   * menu of radio items over a portal — the shape most likely to lose its
   * name or its grouping.
   */
  it('the refresh interval menu open', async () => {
    const user = await workbench();
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.refresh.auto'],
      }),
    );
    await screen.findByRole('menu');

    expect(await violations(document.body)).toEqual([]);
  });

  it('the editor open, with its modes menu showing', async () => {
    const user = await workbench();
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.editor-modes'],
      }),
    );
    await screen.findByRole('menu');

    expect(await violations(document.body)).toEqual([]);
  });

  /**
   * A numeric `IN` is a list that grows, and every part of it is a control
   * with a name to get wrong: a chip per value carrying its own remove
   * button, the entry field, and the button that commits what is in it.
   */
  it('the editor open on a numeric IN of several values', async () => {
    await openEditor(amountsIn);
    await screen.findByRole('button', {
      name: formatMessage(defaultMessages, 'label.filter.remove-value', {
        value: '200',
      }),
    });

    expect(await violations(document.body)).toEqual([]);
  });

  /**
   * A date condition on a `withTime` field is one control with two halves:
   * the calendar, and the clock under it. Both live in the same portalled
   * popover, and the clock's boxes are named by their own labels rather than
   * by an `aria-label` the visible word would disagree with.
   */
  it('the calendar open on a field that carries a time of day', async () => {
    const user = await openEditor(createdBetween, timedOrdersDefinition());
    await user.click(
      screen.getByRole('button', {
        name: formatMessage(defaultMessages, 'label.filter.value-of', {
          field: 'Created',
        }),
      }),
    );
    await screen.findByLabelText(defaultMessages['label.date.time-from']);
    expect(
      screen.getByLabelText(defaultMessages['label.date.time-to']),
    ).toBeDefined();

    expect(await violations(document.body)).toEqual([]);
  });

  it('the field picker open, which is a grid of checkboxes in a dialog', async () => {
    const user = await openEditor();
    await user.click(
      await screen.findByRole('button', {
        name: defaultMessages['label.filter.add'],
      }),
    );
    await screen.findByText(defaultMessages['label.filter.pick-fields']);

    expect(await violations(document.body)).toEqual([]);
  });

  /**
   * The view filling the screen, and the page put back afterwards.
   *
   * It is deliberately not a modal — no `aria-modal`, no focus trap, nothing
   * marked inert — because nothing is being asked and nothing was moved: the
   * same content, in the same place, still a descendant of the host's own
   * page. Which means the whole document has to keep passing, in both
   * states: an expansion that quietly stranded the controls behind it would
   * be exactly the modality this refuses to claim.
   */
  it('the view filling the screen, and the page given back', async () => {
    const user = await workbench();
    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.expand-view'],
      }),
    );

    expect(await violations(document.body)).toEqual([]);

    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.collapse-view'],
      }),
    );
    expect(await violations(document.body)).toEqual([]);
  });

  /**
   * An embed filling the screen under a control its host owns.
   *
   * That control is a sibling of the surface, so the surface now covers it —
   * which on a touch device, with no Escape key to fall back on, would be a
   * screen with no way off it. The surface grows its own exit for exactly
   * that case, and it is a real control on a real page: it has to carry a
   * name and pass with the rest of the document, in both states.
   */
  it('an embed filling the screen with the way out it grew', async () => {
    function Hosted() {
      const root = useRef<HTMLDivElement>(null);
      const toggle = useRef<HTMLButtonElement>(null);
      const expansion = useViewExpansion(root, toggle);
      const [engine] = useState(() => engineWith([pendingOrders]));
      return (
        <>
          <button ref={toggle} type="button" onClick={expansion.toggle}>
            Fill the screen
          </button>
          <EmbeddedView ref={root} engine={engine} instanceId="pending" />
        </>
      );
    }
    const user = userEvent.setup();
    render(<Hosted />);
    await screen.findByRole('table');
    // Nothing of its own while the page can be worked normally: an embed is
    // the result and no chrome at all.
    expect(await violations(document.body)).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Fill the screen' }));
    expect(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.collapse-view'],
      }),
    ).toBeDefined();
    expect(await violations(document.body)).toEqual([]);
  });
});

describe('the default workbenches pass axe', () => {
  it('record', async () => {
    const { container } = render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([pendingOrders])}
          definitionId="orders"
          instanceId="pending"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(container.querySelector('table')).toBeTruthy());

    expect(await violations(container)).toEqual([]);
  });

  /**
   * The table as it is actually read: ordered by two columns, with both
   * summary scopes under it. Sorting adds `aria-sort` to a cell and a button
   * inside a header, and the summaries add a footer whose first cell is a
   * label rather than a number — three ways to put a table wrong that the
   * plain record case above would never reach.
   */
  it('record, sorted on two columns and summarised', async () => {
    // Two sortable columns, so the headers carry positions as well as
    // directions; the shared definition sorts on `amount` alone.
    const definition = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...definition,
          fields: definition.fields.map(field =>
            field.name === 'id' ? { ...field, sortable: true } : field,
          ),
        },
      ],
      store: new MemoryViewStore({ instances: [sortedOrders] }),
      resolveSource: () => testSource(),
    });
    const { container } = render(
      <ViewSurface>
        <RecordWorkbench
          engine={engine}
          definitionId="orders"
          instanceId="sorted"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(container.querySelector('tfoot')).toBeTruthy());

    // One `aria-sort`, on the column the table is ordered by; the column
    // that breaks its ties carries its place in the button's name instead.
    expect(
      [...container.querySelectorAll('thead [aria-sort]')].map(cell =>
        cell.getAttribute('aria-sort'),
      ),
    ).toEqual(['descending']);
    expect(
      container.querySelectorAll('[data-slot="sort-position"]'),
    ).toHaveLength(2);
    expect(container.querySelectorAll('tfoot tr[data-scope]')).toHaveLength(2);
    expect(await violations(container)).toEqual([]);
  });

  /**
   * The column settings are the densest thing this package draws: a list of
   * rows each carrying a drag handle, a checkbox, a select and a toggle, and
   * the drag library adds attributes of its own to every handle. Axe is run
   * over the document rather than the container, because the popover and the
   * library's live region are both portalled out of it.
   */
  it('record, with the column settings open', async () => {
    const user = userEvent.setup();
    render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([pendingOrders])}
          definitionId="orders"
          instanceId="pending"
          actions={{ row: () => <button type="button">{'Open'}</button> }}
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(document.querySelector('table')).toBeTruthy());

    await user.click(screen.getByRole('button', { name: /Columns/ }));
    expect(
      await screen.findByRole('button', { name: 'Reorder Amount' }),
    ).toBeTruthy();

    expect(await violations(document.body)).toEqual([]);

    // And again with the search on, which is a second screen: the refusal
    // line the input points at, and — with nothing matching — an empty
    // state where the list was.
    await user.type(
      document.querySelector<HTMLElement>('[data-slot="column-search"]')!,
      'zzz',
    );
    expect(document.querySelector('[data-slot="column-none"]')).toBeTruthy();

    expect(await violations(document.body)).toEqual([]);
  });

  it('record, with the sort control open', async () => {
    const user = userEvent.setup();
    render(
      <ViewSurface>
        <RecordWorkbench
          engine={engineWith([pendingOrders])}
          definitionId="orders"
          instanceId="pending"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(document.querySelector('table')).toBeTruthy());

    // The trigger reads the sort back, so it is addressed by what it is
    // rather than by a name that changes with the config.
    await user.click(
      document.querySelector<HTMLElement>('[data-control="sort"]')!,
    );
    expect(
      await screen.findByRole('button', { name: /Sort by a field/ }),
    ).toBeTruthy();

    expect(await violations(document.body)).toEqual([]);
  });

  it('analysis', async () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisWorkbench
          engine={engineWith([warehouseTotals])}
          definitionId="orders"
          instanceId="totals"
        />
      </ViewSurface>,
    );
    await waitFor(() => expect(container.querySelector('table')).toBeTruthy());

    expect(await violations(container)).toEqual([]);
  });

  /**
   * The chart layout, where the answer is drawn rather than written. The
   * drawing is one `role="img"` with a name and the numbers are a table
   * beside it, and both of those are ways to put a figure wrong that the
   * table layout above never reaches.
   */
  it('analysis, drawn as a chart', async () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisWorkbench
          engine={engineWith([warehouseChart])}
          definitionId="orders"
          instanceId="drawn"
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(container.querySelector('[role="img"]')).toBeTruthy(),
    );

    expect(
      container.querySelector('[data-slot="chart-reading"] table'),
    ).toBeTruthy();
    expect(await violations(container)).toEqual([]);
  });

  it('dashboard, with the layout open for editing', async () => {
    const { container } = render(
      <ViewSurface>
        <DashboardWorkbench
          engine={engineWith([pendingOrders, overview])}
          definitionId="overview"
          instanceId="overview-1"
          editable
        />
      </ViewSurface>,
    );
    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="dashboard-panel"]'),
      ).toBeTruthy(),
    );

    // The drag grip is the reason this test exists: an `aria-label` on a bare
    // span is a prohibited attribute, and only a browser or axe reports it.
    expect(await violations(container)).toEqual([]);
  });

  /**
   * The pagination bar on its own, on a page in the middle so that every
   * control it can draw is drawn and live.
   *
   * The workbench case above only ever reaches the bar's first page, where
   * both the empty-result rule and a disabled Previous hide half of it. This
   * one is where the size control's name is checked — it is labelled by the
   * words beside it, and an `aria-labelledby` that addresses nothing is a
   * broken name rather than a missing one, which axe is the thing that sees.
   */
  it('the pagination bar, mid-way through a paged result', async () => {
    const { container } = render(
      <ViewSurface>
        <RecordPagination
          table={recordTableController({
            paging: { mode: 'paged', index: 2, total: 42 },
          })}
        />
      </ViewSurface>,
    );

    expect(await violations(container)).toEqual([]);
  });

  /**
   * Every cell reading at once. The link is why this is here: the one
   * interactive thing a cell can hold has to carry a name, and its name is
   * the text it shows — there is no label beside a cell to borrow one from.
   */
  it('a row holding each of the cell readings', async () => {
    const { container } = render(
      <ViewSurface>
        <RecordTable
          table={recordTableController({
            columns: [
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'status',
                sortable: false,
                options: [
                  { value: 'PENDING', label: 'Pending', tone: 'warning' },
                ],
              },
              {
                field: 'tags',
                label: 'Tags',
                kind: 'array',
                cell: 'tags',
                sortable: false,
              },
              {
                field: 'track',
                label: 'Tracking',
                kind: 'string',
                cell: 'link',
                sortable: false,
              },
              {
                field: 'note',
                label: 'Note',
                kind: 'string',
                cell: 'text',
                sortable: false,
              },
            ],
            rows: [
              {
                key: 'o-1',
                data: {
                  status: 'PENDING',
                  tags: ['rush', 'gift'],
                  track: 'https://example.com/t/1',
                  note: 'Left with the neighbour.\nSecond attempt tomorrow.',
                },
              },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(await violations(container)).toEqual([]);
  });
});
