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
 * Embeds never write (D36): whatever a reader does on an embedded view or
 * board — filters, search, sort, pages, a column's width, a press, a tab,
 * filling the screen, the follow-up menu — lives in that viewing alone. Not
 * a view, not a board, not a preference (the tab a board was last read on
 * among them) reaches the store, in either tier.
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
  type ViewInstance,
} from '../src/index.js';
import { EmbeddedDashboard, EmbeddedView } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Every write the `ViewStore` port has, each one spied on. */
const WRITES = [
  'create',
  'save',
  'rename',
  'delete',
  'setPreferences',
] as const;

const views: ViewInstance[] = [
  {
    id: 'by-warehouse',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({ layout: 'table' }),
  },
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

function panel(
  id: string,
  instanceId: string,
  title: string,
  tab: string,
  extra: Partial<DashboardPanel> = {},
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    instanceId,
    tab,
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'q', panelField: 'q' },
    ],
    layout: { x: 0, y: 0, w: 12, h: 4 },
    ...extra,
  } as DashboardPanel;
}

/**
 * A board with two tabs, a text filter, a search and a chart that
 * cross-filters — every reader gesture a board has — over a store whose
 * every write is spied on, and a reader who may save everything.
 */
function setUp() {
  const orders = ordersDefinition();
  const store = new MemoryViewStore({
    instances: [
      ...views,
      {
        id: 'board',
        definitionId: 'overview',
        title: 'Operations',
        scope: 'shared',
        revision: 'r1',
        config: dashboardConfig({
          tabs: [
            { id: 'one', title: 'Summary' },
            { id: 'two', title: 'Detail' },
          ],
          fields: [
            { name: 'region', label: 'Region', kind: 'string' },
            { name: 'q', label: 'Find', kind: 'search' },
          ],
          panels: [
            panel('chart', 'by-warehouse', 'By warehouse', 'one', {
              click: { kind: 'filter', filter: 'region' },
              bindings: [{ globalField: 'region', panelField: 'warehouse' }],
            }),
            panel('list', 'list', 'Order list', 'one', {
              layout: { x: 12, y: 0, w: 12, h: 4 },
            }),
            panel('more', 'list', 'More orders', 'two'),
          ],
        }),
      },
    ],
  });
  const writes = WRITES.map(name => vi.spyOn(store, name));
  const engine = new ViewEngine({
    definitions: [
      {
        ...orders,
        fields: [
          ...orders.fields,
          { name: 'q', label: 'Search orders', kind: 'search' },
        ],
      },
      overviewDefinition(),
    ],
    store,
    resolveSource: () =>
      testSource({
        paged: vi.fn(() =>
          Promise.resolve({
            total: 60,
            list: [
              { id: 'o-1', warehouse: 'CN', status: 'PENDING', amount: 10 },
              { id: 'o-2', warehouse: 'EU', status: 'SHIPPED', amount: 20 },
            ],
          }),
        ),
        aggregate: vi.fn(() =>
          Promise.resolve([
            { warehouse: 'CN', orders: 2 },
            { warehouse: 'EU', orders: 1 },
          ]),
        ),
      }),
  });
  const written = () =>
    writes.flatMap((spy, index) => spy.mock.calls.map(() => WRITES[index]));
  return { engine, written };
}

/** Lets whatever a gesture set off — a query, a write — settle. */
async function settled() {
  await new Promise(resolve => setTimeout(resolve, 50));
}

describe('an embed writes nothing', () => {
  it('through a whole interactive session on a board', async () => {
    const { engine, written } = setUp();
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    render(
      <EmbeddedDashboard
        engine={engine}
        instanceId="board"
        interaction="interactive"
        withTitle
        withExport
        expandable
        onNavigate={onNavigate}
      />,
    );

    // A filter set by hand, and one by a press on the chart.
    const find = await screen.findByRole('group', { name: 'Find' });
    await user.type(within(find).getByRole('textbox'), 'o-1');
    const chart = await screen.findByRole('group', { name: 'By warehouse' });
    await user.click(await within(chart).findByRole('row', { name: /CN/ }));
    await settled();
    // The list panel sorted, paged and its column widened.
    const list = screen.getByRole('group', { name: 'Order list' });
    await user.click(
      await within(list).findByRole('button', { name: /Sort by Amount/ }),
    );
    within(list).getAllByRole('separator')[0].focus();
    await user.keyboard('{ArrowRight}');
    await settled();
    // The panel's menu: refresh, and a way off the board.
    await user.click(
      screen.getByRole('button', { name: 'Actions for “Order list”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Refresh this panel' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Actions for “Order list”' }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Open in the workbench' }),
    );
    expect(onNavigate).toHaveBeenCalled();
    // Another tab — a workbench would remember it — and back; the screen
    // filled and put back; the filters cleared.
    await user.click(screen.getByRole('tab', { name: 'Detail' }));
    await user.click(screen.getByRole('tab', { name: 'Summary' }));
    await user.click(screen.getByRole('button', { name: 'Fill the screen' }));
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await settled();

    expect(written()).toEqual([]);
  });

  it('through a whole interactive session on a record view and an analysis', async () => {
    const { engine, written } = setUp();
    const user = userEvent.setup();
    const { unmount } = render(
      <EmbeddedView
        engine={engine}
        instanceId="list"
        interaction="interactive"
        withSearch
        withExport
        expandable
        onNavigate={vi.fn()}
      />,
    );

    await user.type(
      await screen.findByRole('searchbox', { name: 'Search orders' }),
      'o-2{Enter}',
    );
    await user.click(
      await screen.findByRole('button', { name: /Sort by Amount/ }),
    );
    screen.getAllByRole('separator')[0].focus();
    await user.keyboard('{ArrowRight}');
    await user.click(screen.getByRole('button', { name: /Next page/ }));
    await user.click(screen.getAllByRole('checkbox')[1]);
    await user.click(screen.getByRole('button', { name: 'Fill the screen' }));
    await user.keyboard('{Escape}');
    await settled();
    unmount();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="by-warehouse"
        interaction="interactive"
        onNavigate={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole('button', { name: 'Chart' }));
    await user.click(screen.getByRole('button', { name: 'Table' }));
    await settled();

    await waitFor(() => expect(written()).toEqual([]));
  });

  it('in the static tier, whatever the host switched on', async () => {
    const { engine, written } = setUp();
    render(
      <>
        <EmbeddedDashboard
          engine={engine}
          instanceId="board"
          withExport
          expandable
        />
        <EmbeddedView
          engine={engine}
          instanceId="list"
          withSearch
          withExport
          expandable
        />
      </>,
    );

    await screen.findByRole('group', { name: 'Order list' });
    await userEvent.type(
      await screen.findByRole('searchbox', { name: 'Search orders' }),
      'o-2{Enter}',
    );
    await userEvent.click(screen.getByRole('tab', { name: 'Detail' }));
    await settled();

    expect(written()).toEqual([]);
  });
});
