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
import { afterEach, describe, expect, it } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import type { DataViewKind } from '../src/ui/index.js';
import {
  analysisConfig,
  mine,
  ordersDefinition,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** The analysis view every case opens: one warehouse group, rows as a table. */
const chart: ViewInstance = {
  id: 'orders-chart',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({ layout: 'table' }),
};

/**
 * A definition with a second dimension to split by: the orders fixture
 * declares only `warehouse` as groupable, and the one group already in force
 * is never on offer.
 */
function twoDimensions(): DataViewDefinition {
  return ordersDefinition({
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
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

function open(
  options: {
    kinds?: readonly DataViewKind[];
    definition?: DataViewDefinition;
    source?: ViewSource;
  } = {},
) {
  const source = options.source ?? testSource();
  const engine = new ViewEngine({
    definitions: [options.definition ?? ordersDefinition()],
    // A record view beside the analysis one, so the list holds both kinds.
    store: new MemoryViewStore({ instances: [mine, chart] }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-chart"
      kinds={options.kinds ?? ['record', 'analysis']}
    />,
  );
  return { engine, source };
}

/** The analysis result's one data row: the group the user presses. */
async function groupRow(): Promise<HTMLElement> {
  const table = await screen.findByRole('table');
  return await waitFor(() => {
    const row = within(table).getAllByRole('row')[1];
    if (!row) throw new Error('the result has no group row yet');
    return row;
  });
}

const menu = () =>
  document.querySelector<HTMLElement>('[data-slot="drill-menu"]');

const item = (name: string) => screen.findByRole('menuitem', { name });

/** The one open analysis runtime, for reading what is actually in force. */
function analysisRuntime(engine: ViewEngine) {
  const found = engine
    .openRuntimes()
    .find(runtime => runtime.kind === 'analysis');
  if (!found) throw new Error('no analysis view is open');
  return found;
}

const appliedIn = (runtime: ReturnType<typeof analysisRuntime>) =>
  runtime.getSnapshot().applied as AnalysisViewConfig;

describe('the follow-up menu on one group', () => {
  /**
   * The pointer presses a mark; the keyboard presses a row. A chart's marks
   * are inside an element that says `role="img"` and holds nothing focusable
   * (`charts/asImage.ts`), and the reading table beside it is `sr-only` and
   * deliberately out of reach — so the table layout is where the same menu is
   * opened without a pointer (F10), and the row says it opens one.
   */
  it('opens from a group row on a press, on Enter and on Space', async () => {
    open();
    const row = await groupRow();

    expect(row.tabIndex).toBe(0);
    expect(row.getAttribute('aria-haspopup')).toBe('menu');
    expect(row.hasAttribute('data-pickable')).toBe(true);
    expect(menu()).toBeNull();

    fireEvent.click(row);
    await waitFor(() => expect(menu()).not.toBeNull());
    // The menu is about one group, and says which: the row's conditions, in
    // the applied bar's own words.
    expect(
      menu()!.querySelector('[data-slot="drill-group"]')!.textContent,
    ).toBe('Warehouse is CN');
    fireEvent.keyDown(document.body, { key: 'Escape' });
    await waitFor(() => expect(menu()).toBeNull());

    for (const key of ['Enter', ' ']) {
      fireEvent.keyDown(await groupRow(), { key });
      await waitFor(() => expect(menu()).not.toBeNull());
      fireEvent.keyDown(document.body, { key: 'Escape' });
      await waitFor(() => expect(menu()).toBeNull());
    }
  });

  it('closes on Escape, putting the user back on the row they pressed', async () => {
    const { engine, source } = open();
    const row = await groupRow();
    fireEvent.keyDown(row, { key: 'Enter' });
    await waitFor(() => expect(menu()).not.toBeNull());
    const ran = source.aggregate as unknown as { mock: { calls: unknown[] } };
    const before = ran.mock.calls.length;

    fireEvent.keyDown(document.body, { key: 'Escape' });

    await waitFor(() => expect(menu()).toBeNull());
    expect(ran.mock.calls.length).toBe(before);
    expect(analysisRuntime(engine).getSnapshot().dirty).toBe(false);
    expect(screen.queryByRole('table')).not.toBeNull();
    // The menu has no control of its own to hand focus back to, so it hands
    // it back to what was pressed: a keyboard user closing the menu is where
    // they were, not at the top of the document.
    await waitFor(() => expect(document.activeElement).toBe(row));
  });

  it('opens the records behind the group, held under its origin', async () => {
    open();
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.records']));

    // A record view, with the line that says where it came from and the way
    // back to it.
    const bar = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="origin-bar"]',
      );
      if (!found) throw new Error('no origin bar');
      return found;
    });
    expect(within(bar).getByText('From By warehouse')).toBeDefined();
    expect(
      [...bar.querySelectorAll('[data-slot="origin-condition"]')].map(
        badge => badge.textContent,
      ),
    ).toEqual(['Warehouse is CN']);
    expect(
      within(bar).getByRole('button', { name: 'Back to By warehouse' }),
    ).toBeDefined();
    await waitFor(() => expect(menu()).toBeNull());
  });

  it('offers no records where no record view can be made', async () => {
    open({ kinds: ['analysis'] });
    fireEvent.click(await groupRow());
    await waitFor(() => expect(menu()).not.toBeNull());

    expect(
      screen.queryByRole('menuitem', {
        name: defaultMessages['label.drill.records'],
      }),
    ).toBeNull();
    // The two that edit this view are still there: they are nobody's to
    // permit, and they need no second view.
    expect(
      screen.getByRole('menuitem', {
        name: defaultMessages['label.drill.focus'],
      }),
    ).toBeDefined();
  });

  it('narrows the analysis view to the group, and stays one', async () => {
    const { engine, source } = open();
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.focus']));

    // The condition is in force — asked for again, and said on the bar the
    // rows on screen were fetched under.
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
    const runtime = analysisRuntime(engine);
    expect(appliedIn(runtime).filter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
    // Still an analysis view, still grouped as it was: only the range moved.
    expect(runtime.kind).toBe('analysis');
    expect(appliedIn(runtime).groups.map(group => group.field)).toEqual([
      'warehouse',
    ]);
    expect(document.querySelector('[data-slot="origin-bar"]')).toBeNull();

    const bar = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="applied-bar"]',
      );
      if (!found) throw new Error('no applied bar');
      return found;
    });
    await waitFor(() =>
      expect(within(bar).getByText('Warehouse is CN')).toBeDefined(),
    );
  });

  it('splits the group by a dimension it is not grouped by already', async () => {
    const { engine, source } = open({ definition: twoDimensions() });
    fireEvent.click(await groupRow());
    fireEvent.click(await item(defaultMessages['label.drill.split']));

    // The dimensions on offer are the groupable fields this analysis does
    // not already group by — never the one the pressed group is of.
    await item('Status');
    const split = document.querySelector<HTMLElement>(
      '[data-slot="dropdown-menu-sub-content"]',
    )!;
    expect(
      within(split)
        .getAllByRole('menuitem')
        .map(entry => entry.textContent),
    ).toEqual(['Status']);

    fireEvent.click(within(split).getByRole('menuitem', { name: 'Status' }));

    // The same question, of the group the user pressed, by the other
    // dimension: one group, and the range narrowed to the row.
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(2));
    const applied = appliedIn(analysisRuntime(engine));
    expect(applied.groups.map(group => group.field)).toEqual(['status']);
    expect(applied.filter).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    });
    await waitFor(() => expect(menu()).toBeNull());
  });
});
