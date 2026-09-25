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
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type ViewSource,
} from '../src/index.js';
import type { DashboardPanelView } from '../src/react/index.js';
import {
  EmbeddedDashboard,
  type EmbeddedDashboardProps,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  ROWS,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** A record panel over 45 orders, 20 to a page, beside an analysis. */
function engineOf(source: ViewSource) {
  const store = new MemoryViewStore({
    instances: [
      {
        id: 'list',
        definitionId: 'orders',
        title: 'Order list',
        scope: 'shared',
        revision: 'r1',
        config: recordConfig(),
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
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
  });
}

function orders(pages = 45) {
  return testSource({
    paged: vi.fn(() => Promise.resolve({ total: pages, list: [...ROWS] })),
  });
}

function open(source: ViewSource, props: Partial<EmbeddedDashboardProps> = {}) {
  render(
    <EmbeddedDashboard
      engine={engineOf(source)}
      instanceId="board"
      interaction="interactive"
      {...props}
    />,
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
  it('puts a row’s command on each row, only on the panels the host names, and re-runs the panel after it', async () => {
    const source = orders(2);
    const asked: string[] = [];
    await open(source, {
      recordPanel: (panel: DashboardPanelView) =>
        panel.id === 'orders'
          ? {
              actions: {
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
            }
          : undefined,
    });
    const nudge = await screen.findByRole('button', { name: 'Nudge o-1' });
    expect(screen.getByRole('button', { name: 'Nudge o-2' })).toBeTruthy();
    const before = vi.mocked(source.paged).mock.calls.length;

    await userEvent.click(nudge);
    expect(asked).toEqual(['o-1']);
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(before),
    );
  });

  it('offers a selection’s command over picked rows, where the board has controls', async () => {
    const run = vi.fn();
    const recordPanel = () => ({
      actions: {
        bulk: ({ keys }: { keys: readonly unknown[] }) => (
          <button type="button" onClick={() => run(keys)}>
            Nudge all
          </button>
        ),
      },
    });
    const panel = await open(orders(2), { recordPanel });
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
    await open(orders(2), {
      interaction: 'static',
      recordPanel: () => ({
        actions: {
          row: ({ row }) => <span>Row {String(row.key)}</span>,
          bulk: () => <button type="button">Nudge all</button>,
        },
      }),
    });
    await screen.findByText('Row o-1');
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('says how far the host’s bulk command has come above the rows', async () => {
    const command = {
      run: vi.fn(),
      stop: vi.fn(),
      dismiss: vi.fn(),
      outcome: null,
      running: {
        title: 'Nudge',
        progress: { total: 3, done: 1, failed: 0 },
        stopping: false,
      },
    };
    const panel = await open(orders(2), {
      recordPanel: () => ({ actions: {}, bulk: command }),
    });
    const card = panel.closest<HTMLElement>('[data-slot="dashboard-panel"]')!;
    await waitFor(() =>
      expect(card.querySelector('[data-slot="bulk-status"]')).not.toBeNull(),
    );
  });
});
