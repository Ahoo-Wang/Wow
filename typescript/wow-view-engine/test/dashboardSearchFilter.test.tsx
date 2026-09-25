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
 * A board's search, built on screen (D36 follow-up): 「添加筛选」 lists the
 * search kind, its settings are the ones a search has, its empty box says
 * 「搜索…」, and wiring it reaches the record panels' search boxes — the one
 * picked by hand, the rest by auto-connect — and never an analysis panel.
 * Typing into it runs the panels it reaches.
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
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { DashboardWorkbench } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Orders with a search box over the order number and the status. */
function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'q',
        label: 'Order no. or status',
        kind: 'search',
        searchFields: ['id', 'status'],
      },
    ],
  };
}

function instance(
  id: string,
  title: string,
  config: ViewInstance['config'],
): ViewInstance {
  return {
    id,
    definitionId: 'orders',
    title,
    scope: 'shared',
    revision: 'r1',
    config,
  };
}

const views: ViewInstance[] = [
  instance('trend', 'Orders by warehouse', analysisConfig()),
  instance('list', 'Order list', recordConfig()),
  instance('late', 'Late orders', recordConfig()),
];

function panel(id: string, instanceId: string, y: number): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings: [],
    title: views.find(view => view.id === instanceId)?.title,
    layout: { x: 0, y, w: 12, h: 4 },
  } as DashboardPanel;
}

function setup() {
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [orders(), overviewDefinition()],
    store: new MemoryViewStore({
      instances: [
        ...views,
        {
          id: 'board',
          definitionId: 'overview',
          title: 'Operations',
          scope: 'personal',
          revision: '1',
          config: dashboardConfig({
            panels: [
              panel('chart', 'trend', 0),
              panel('list', 'list', 4),
              panel('late', 'late', 8),
            ],
          }),
        },
      ],
    }),
    resolveSource: () => source,
  });
  const runtime = () =>
    engine
      .openRuntimes()
      .find(
        (open): open is DashboardViewRuntime =>
          open instanceof DashboardViewRuntime,
      ) as DashboardViewRuntime;
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="board"
    />,
  );
  return { source, runtime };
}

async function addSearch(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await screen.findByRole('region', { name: 'Editing' });
  await user.click(screen.getByRole('button', { name: 'Add filter' }));
  await user.click(await screen.findByRole('menuitem', { name: 'Search' }));
  return screen.findByRole('dialog', { name: 'Settings of “Search”' });
}

describe('a search filter on the board', () => {
  it('is listed among the kinds, set up as a search, and says 「搜索…」 while empty', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime } = setup();
    const settings = await addSearch(user);

    expect(runtime().getSnapshot().draft.fields).toEqual([
      { name: 'filter-1', label: 'Search', kind: 'search' },
    ]);
    // One line to look for: no 「可多选」, no list of its own.
    expect(within(settings).queryByText('Several values')).toBeNull();
    expect(within(settings).queryByText('Values from')).toBeNull();
    expect(
      within(settings).getByRole('checkbox', { name: 'Required' }),
    ).toBeTruthy();
    const start = within(settings).getByRole('textbox', {
      name: 'Search Default',
    });
    expect(start.getAttribute('placeholder')).toBe('Search…');

    const box = within(screen.getByRole('group', { name: 'Search' })).getByRole(
      'textbox',
      { name: 'Search' },
    );
    expect(box.getAttribute('placeholder')).toBe('Search…');
  });

  it('wires the record panels’ search boxes, the rest by auto-connect, and never the analysis panel', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { runtime, source } = setup();
    const settings = await addSearch(user);
    await user.click(
      within(settings).getByRole('button', { name: 'Wire to panels' }),
    );
    await screen.findByRole('region', { name: 'Wiring “Search”' });
    await waitFor(() =>
      expect(
        document.querySelectorAll('[data-slot="panel-wiring"]'),
      ).toHaveLength(3),
    );
    // The analysis panel has nothing to wire a search to.
    expect(
      document.querySelectorAll('[data-slot="panel-wiring-none"]'),
    ).toHaveLength(1);
    expect(
      screen.queryByRole('combobox', {
        name: 'Field of “Orders by warehouse” filtered by “Search”',
      }),
    ).toBeNull();

    await user.click(
      screen.getByRole('combobox', {
        name: 'Field of “Order list” filtered by “Search”',
      }),
    );
    await user.click(
      await screen.findByRole('option', { name: 'Order no. or status' }),
    );
    expect(
      await screen.findByText(
        'Wired 1 more panel with a search box automatically',
      ),
    ).toBeTruthy();
    const reach = (id: string) =>
      runtime()
        .getSnapshot()
        .panels.find(entry => entry.id === id)?.reach['filter-1'];
    await waitFor(() =>
      expect(reach('late')).toEqual({ wired: true, field: 'q', auto: true }),
    );
    expect(reach('list')).toEqual({ wired: true, field: 'q', auto: false });
    expect(reach('chart')).toEqual({ wired: false, why: 'no-field' });

    await user.click(screen.getByRole('button', { name: 'Done wiring' }));

    // Typing runs the panels it reaches, a moment later, as a search.
    vi.mocked(source.paged).mockClear();
    vi.mocked(source.aggregate).mockClear();
    await user.type(
      within(screen.getByRole('group', { name: 'Search' })).getByRole(
        'textbox',
      ),
      'SO-1',
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.lastCall?.[0].filter).toEqual({
        op: 'SEARCH',
        query: 'SO-1',
        mode: 'TERMS',
        fields: ['id', 'status'],
      }),
    );
    // The chart is not narrowed by it: it never asked again.
    expect(source.aggregate).not.toHaveBeenCalled();
  });
});
