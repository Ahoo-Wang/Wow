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

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import axe from 'axe-core';
import {
  MemoryViewStore,
  ViewEngine,
  type ViewInstance,
} from '../src/index.js';
import {
  AnalysisWorkbench,
  DashboardWorkbench,
  defaultMessages,
  RecordPagination,
  RecordWorkbench,
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

function engineWith(instances: ViewInstance[]): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
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
  async function workbench() {
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
    await screen.findByRole('table');
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

  it('the field picker open, which is a popover of checkboxes', async () => {
    const user = await workbench();
    // The editor opens folded on a saved view, so the panel is not in the
    // document until the title bar's toggle is pressed.
    await user.click(
      screen.getByRole('button', {
        name: new RegExp(`^${defaultMessages['label.filter.panel']}`),
      }),
    );
    await user.click(
      await screen.findByRole('button', {
        name: defaultMessages['label.filter.add'],
      }),
    );
    await screen.findByText(defaultMessages['label.filter.pick-fields']);

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
});
