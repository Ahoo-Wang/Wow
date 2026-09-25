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
 * A stretch of a panel's time axis on a board, on screen (D33 batch C,
 * Q52): the follow-up menu over it adds 「设为〈筛选〉」 for each date filter
 * wired to the field it spans, whatever the panel's click says; the stretch
 * set, the panel keeps every day and marks the ones inside it, and the bar
 * says which panel the value came from. The keyboard picks the stretch in
 * the table layout with Shift; the drag is the browser story's.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
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
  defaultRuntimeEnvironment,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewNavigation,
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

const DAY_MS = 86_400_000;
const FIRST = Date.UTC(2026, 8, 1);
const day = (n: number) => FIRST + n * DAY_MS;

function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    },
  };
}

const views: ViewInstance[] = [
  {
    id: 'by-day',
    definitionId: 'orders',
    title: 'By day',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({
      layout: 'table',
      groups: [
        {
          alias: 'day',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
    }),
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

function view(
  id: string,
  instanceId: string,
  extra: Partial<DashboardPanel> = {},
  x = 0,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'created', panelField: 'createdAt' },
    ],
    layout: { x, y: 0, w: 12, h: 5 },
    ...extra,
  } as DashboardPanel;
}

function board(trend: Partial<DashboardPanel> = {}): DashboardViewConfig {
  return dashboardConfig({
    fields: [
      { name: 'region', label: 'Region', kind: 'string' },
      { name: 'created', label: 'Created', kind: 'datetime' },
    ],
    panels: [view('trend', 'by-day', trend), view('list', 'list', {}, 12)],
  });
}

function setup(
  config: DashboardViewConfig,
  onNavigate?: (to: ViewNavigation) => void,
) {
  const source = testSource({
    aggregate: vi.fn(() =>
      Promise.resolve([0, 1, 2, 3].map(n => ({ day: day(n), orders: n + 1 }))),
    ),
  });
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
          config,
        },
      ],
    }),
    resolveSource: () => source,
    environment: defaultRuntimeEnvironment({ timeZone: 'UTC' }),
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
      onNavigate={onNavigate}
    />,
  );
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  return { runtime, user };
}

/** The trend panel's day rows once its answer is on screen. */
async function days(): Promise<HTMLElement[]> {
  const body = await screen.findByRole('group', { name: 'By day' });
  return waitFor(() => {
    const rows = within(body)
      .getAllByRole('row')
      .filter(row => row.hasAttribute('data-pickable'));
    expect(rows).toHaveLength(4);
    return rows;
  });
}

const menu = () =>
  waitFor(() => {
    const found = document.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    expect(found?.getAttribute('aria-label')).toBe('This period');
    return found!;
  });

describe('a stretch of a panel’s time axis on a board (D33 Q52)', () => {
  it('adds 「设为〈筛选〉」 to the menu; set, the panel marks the days inside it', async () => {
    const onNavigate = vi.fn();
    const { runtime, user } = setup(board(), onNavigate);
    const rows = await days();
    await user.click(rows[1]!);
    await user.keyboard('{Escape}');
    await user.keyboard('{Shift>}');
    await user.click(rows[3]!);
    await user.keyboard('{/Shift}');
    const open = await menu();
    // The analysis view's follow-ups, each away, then the board's filter.
    expect(
      within(open)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      'See these records(opens in the workbench)',
      'Split this period by…',
      'Only this period(opens in the workbench)',
      'Set “Created” to this period',
    ]);
    await user.click(
      within(open).getByRole('menuitem', {
        name: 'Set “Created” to this period',
      }),
    );
    expect(onNavigate).not.toHaveBeenCalled();
    expect(runtime().getSnapshot().filters).toEqual({
      values: {
        created: {
          type: 'absolute',
          from: new Date(day(1)).toISOString(),
          to: new Date(day(4) - 1).toISOString(),
          timeZone: 'UTC',
        },
      },
      from: { created: 'trend' },
    });
    // The panel keeps every day and marks the three inside the stretch.
    await waitFor(async () =>
      expect(
        (await days()).map(row => row.hasAttribute('data-pressed')),
      ).toEqual([false, true, true, true]),
    );
    // What the press did, said in the board's voice.
    await waitFor(() =>
      expect(document.body.textContent).toContain('“Created” now filters by'),
    );
  });

  it('opens a menu of the filter alone where the host gave no route', async () => {
    const { runtime, user } = setup(
      board({ click: { kind: 'filter', filter: 'created' } }),
    );
    const rows = await days();
    // A press sets the filter to its one day, as the click says…
    await user.click(rows[0]!);
    expect(runtime().getSnapshot().filters.from).toEqual({ created: 'trend' });
    // …and with Shift the table is not the keyboard's brush: a press that
    // sets a filter opens no menu, so there is no span to pick.
    await user.keyboard('{Shift>}');
    await user.click(rows[2]!);
    await user.keyboard('{/Shift}');
    expect(document.querySelector('[data-slot="drill-menu"]')).toBeNull();
  });

  it('offers nothing to press without a route or a click', async () => {
    setup(board());
    const rows = await screen.findByRole('group', { name: 'By day' });
    await waitFor(() =>
      expect(within(rows).getAllByRole('row').length).toBeGreaterThan(1),
    );
    expect(rows.querySelector('[data-pickable]')).toBeNull();
  });
});
