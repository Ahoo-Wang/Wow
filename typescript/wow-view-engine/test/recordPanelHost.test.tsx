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

/**
 * A dashboard's record panel (D39): how many rows its query matched and the
 * way to the rest — the pages where the board has controls, the count alone
 * where it has none — and the host's own commands on it: a row's, a
 * selection's with the bulk command's line, each able to re-run the panel.
 * The commands are the host's; the board writes nothing (D36).
 */

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  actions,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type RecordViewConfig,
  type ViewSource,
} from '../src/index.js';
import {
  bind,
  EmbeddedDashboard,
  ViewHost,
  type EmbeddedDashboardProps,
  type ViewBindingOptions,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  ROWS,
  testSource,
  resourcesOf,
} from './fixtures.js';

afterEach(cleanup);

/** A record panel over 45 orders, 20 to a page, beside an analysis. */
function engineOf(source: ViewSource, list: RecordViewConfig = recordConfig()) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'list',
        definitionId: 'orders',
        title: 'Order list',
        scope: 'shared',
        revision: 'r1',
        config: list,
      },
      {
        id: 'chart',
        definitionId: 'orders',
        title: 'By warehouse',
        scope: 'shared',
        revision: 'r1',
        config: analysisConfig({ layout: 'table' }),
      },
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'shared',
        revision: 'r1',
        config: dashboardConfig({
          panels: [
            {
              id: 'orders',
              kind: 'view',
              title: 'Orders',
              instanceId: 'list',
              bindings: [],
              layout: { x: 0, y: 0, w: 12, h: 6 },
            },
            {
              id: 'by-warehouse',
              kind: 'view',
              title: 'By warehouse',
              instanceId: 'chart',
              bindings: [],
              layout: { x: 12, y: 0, w: 12, h: 6 },
            },
          ] as DashboardPanel[],
        }),
      },
    ],
  });
  return new ViewEngine({
    resources: resourcesOf(
      [ordersDefinition(), overviewDefinition()],
      () => source,
    ),
    store,
  });
}

function orders(pages = 45) {
  return testSource({
    paged: vi.fn(() => Promise.resolve({ total: pages, list: [...ROWS] })),
  });
}

/**
 * The board embedded under a provider that binds `orders` — the host's
 * commands on its records reach every record panel over it (`bind`).
 */
function open(
  source: ViewSource,
  props: Partial<EmbeddedDashboardProps> = {},
  list?: RecordViewConfig,
  orders: ViewBindingOptions = {},
) {
  render(
    <ViewHost
      engine={engineOf(source, list)}
      bindings={[bind('orders', orders)]}
    >
      <EmbeddedDashboard
        instanceId="board"
        interaction="interactive"
        {...props}
      />
    </ViewHost>,
  );
  return screen.findByRole('group', { name: 'Orders' });
}

describe('a record panel says how many rows there are (D39)', () => {
  it('counts every match and pages through them, the page size the view’s', async () => {
    const source = orders();
    const panel = await open(source);
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;

    await waitFor(() =>
      expect(within(card).getByText('45 records in all')).toBeTruthy(),
    );
    // Its own landmark, named after the panel: a board of several record
    // panels has as many, told apart.
    expect(
      screen.getByRole('navigation', { name: 'Pages of “Orders”' }),
    ).toBeTruthy();
    // The page size is the view's: no select for it on a panel.
    expect(within(card).queryByRole('combobox')).toBeNull();
    await userEvent.click(
      within(card).getByRole('button', { name: 'Next page' }),
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.lastCall?.[0]).toMatchObject({
        pagination: { index: 2 },
      }),
    );
  });

  it('says the count alone on a board with no controls', async () => {
    const panel = await open(orders(), { interaction: 'static' });
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;

    await waitFor(() =>
      expect(within(card).getByText('45 records in all')).toBeTruthy(),
    );
    expect(
      within(card).queryByRole('button', { name: 'Next page' }),
    ).toBeNull();
  });
});

describe('the host’s commands on a record panel (D39)', () => {
  it('puts a row’s command on each row of a record panel over the definition the host bound, and re-runs the board after it', async () => {
    const source = orders(2);
    const asked: string[] = [];
    await open(source, {}, undefined, {
      slots: {
        row: ({ row, refresh }) => (
          <button
            type="button"
            onClick={() => {
              asked.push(String(row.key));
              refresh();
            }}
          >
            Nudge {String(row.key)}
          </button>
        ),
      },
    });
    const nudge = await screen.findByRole('button', { name: 'Nudge o-1' });
    expect(screen.getByRole('button', { name: 'Nudge o-2' })).toBeTruthy();
    await screen.findByRole('group', { name: 'By warehouse' });
    await waitFor(() => expect(source.aggregate).toHaveBeenCalled());
    const before = vi.mocked(source.paged).mock.calls.length;
    const sibling = vi.mocked(source.aggregate).mock.calls.length;

    await userEvent.click(nudge);
    expect(asked).toEqual(['o-1']);
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(before),
    );
    // The command wrote to the host's service, and any number on the board
    // may have moved with it: the panel beside it asks again too.
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBeGreaterThan(
        sibling,
      ),
    );
  });

  it('re-runs the board after a selection’s command, too', async () => {
    const source = orders(2);
    const panel = await open(source, {}, undefined, {
      slots: {
        bulk: ({ refresh }) => (
          <button type="button" onClick={refresh}>
            Nudge all
          </button>
        ),
      },
    });
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const boxes = await within(card).findAllByRole('checkbox');
    await waitFor(() => expect(source.aggregate).toHaveBeenCalled());
    const sibling = vi.mocked(source.aggregate).mock.calls.length;

    await userEvent.click(boxes[boxes.length - 1]);
    await userEvent.click(
      within(card).getByRole('button', { name: 'Nudge all' }),
    );
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBeGreaterThan(
        sibling,
      ),
    );
  });

  it('offers a selection’s command over picked rows, where the board has controls', async () => {
    const run = vi.fn();
    const panel = await open(orders(2), {}, undefined, {
      slots: {
        bulk: ({ keys }) => (
          <button type="button" onClick={() => run(keys)}>
            Nudge all
          </button>
        ),
      },
    });
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const boxes = await within(card).findAllByRole('checkbox');
    // No bar until a row is picked.
    expect(
      within(card).queryByRole('button', { name: 'Nudge all' }),
    ).toBeNull();

    await userEvent.click(boxes[boxes.length - 1]);
    await userEvent.click(
      within(card).getByRole('button', { name: 'Nudge all' }),
    );
    expect(run).toHaveBeenCalledWith(['o-2']);
    expect(card.querySelector('[data-slot="panel-selection"]')).not.toBeNull();
  });

  it('picks no rows on a board with no controls, and keeps the row’s command', async () => {
    await open(orders(2), { interaction: 'static' }, undefined, {
      slots: {
        row: ({ row }) => <span>Row {String(row.key)}</span>,
        bulk: () => <button type="button">Nudge all</button>,
      },
    });
    await screen.findByText('Row o-1');
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});

describe('an empty record panel says what it is empty of', () => {
  const none = () =>
    testSource({
      paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
    });

  it('says no record matches, under the view’s own conditions', async () => {
    const panel = await open(
      none(),
      {},
      recordConfig({
        filter: {
          op: 'and',
          children: [{ field: 'status', operator: 'EQ', value: 'PENDING' }],
        },
      }),
    );
    expect(
      await within(panel).findByText(
        'No record matches the current conditions.',
      ),
    ).toBeTruthy();
    expect(within(panel).queryByText('There are no records yet.')).toBeNull();
  });

  it('says there are none at all where nothing narrows it', async () => {
    const panel = await open(none());
    expect(
      await within(panel).findByText('There are no records yet.'),
    ).toBeTruthy();
  });
});

describe('declared actions on a record panel (host-integration.md 5)', () => {
  it('places a bound definition’s actions on the panel’s rows and selection, says the run under the rows and reads the board again', async () => {
    const source = orders(2);
    const run = vi.fn(() => Promise.resolve());
    const panel = await open(source, {}, undefined, {
      actions: actions([{ id: 'nudge', label: 'Nudge', primary: true, run }]),
    });
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    const buttons = await within(card).findAllByRole('button', {
      name: 'Nudge',
    });
    await waitFor(() => expect(source.aggregate).toHaveBeenCalled());
    const sibling = vi.mocked(source.aggregate).mock.calls.length;

    await userEvent.click(buttons[0]);
    await waitFor(() =>
      expect(
        card.querySelector('[data-slot="bulk-status"]')?.textContent,
      ).toContain('Nudge · o-1 done'),
    );
    // The line sits after the rows, where the reader's eye ends up.
    const line = card.querySelector('[data-slot="bulk-status"]')!;
    const rows = within(card).getAllByRole('button', { name: 'Nudge' });
    expect(
      line.compareDocumentPosition(rows[rows.length - 1]) &
        Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy();
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBeGreaterThan(
        sibling,
      ),
    );

    // Picked rows: the bar offers the same action, counting them.
    const boxes = await within(card).findAllByRole('checkbox');
    await userEvent.click(boxes[boxes.length - 1]);
    await userEvent.click(
      within(card).getByRole('button', { name: 'Nudge 1' }),
    );
    expect(
      await screen.findByRole('dialog', { name: /^Run “Nudge” on o-\d\?$/ }),
    ).toBeTruthy();
  });

  it('offers no selection on a board with no controls, and keeps a record’s action', async () => {
    await open(orders(2), { interaction: 'static' }, undefined, {
      actions: actions([
        {
          id: 'nudge',
          label: 'Nudge',
          primary: true,
          run: () => Promise.resolve(),
        },
      ]),
    });
    expect(
      (await screen.findAllByRole('button', { name: 'Nudge' })).length,
    ).toBe(2);
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});
