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
 * 「改这里的展示」 alone (D22 D, R4 Q-04): `PresentationDialog` over a board
 * opened by the engine, without the workbench around it — a panel the board
 * owns, whose look is compared with its own config; the options level and
 * the way back, the keyboard following each; a look that comes back to the
 * view's own, which is no override; the totals row; and the panels with no
 * look to change. How the workbench reaches the dialog is
 * `dashboardExtensions.test.tsx`'s.
 */

import { useState } from 'react';
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
  type DashboardViewRuntime,
  MemoryViewStore,
  ViewEngine,
  type DashboardPanel,
  type DashboardViewConfig,
  type PanelPresentation,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useDashboard } from '../src/react/index.js';
import { PresentationDialog } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testSource,
} from './fixtures.js';
import { pending } from './fixtures/dashboard.js';

afterEach(cleanup);

const LAYOUT = { x: 0, y: 0, w: 12, h: 4 };

/** A panel the board owns: an analysis by warehouse, drawn as a bar. */
function owned(presentation?: PanelPresentation): DashboardPanel {
  return {
    id: 'mine',
    kind: 'view',
    title: 'Mine',
    owned: { definitionId: 'orders', config: analysisConfig() },
    bindings: [],
    layout: LAYOUT,
    ...(presentation ? { presentation } : {}),
  } as DashboardPanel;
}

const note: DashboardPanel = {
  id: 'note',
  kind: 'markdown',
  content: 'Read me',
  layout: LAYOUT,
} as DashboardPanel;

const onRecords: DashboardPanel = {
  id: 'records',
  kind: 'view',
  instanceId: 'pending',
  bindings: [],
  layout: LAYOUT,
} as DashboardPanel;

async function openBoard(
  config: DashboardViewConfig,
  source: ViewSource = testSource(),
) {
  const board: ViewInstance = {
    id: 'board',
    definitionId: 'overview',
    title: 'Operations',
    scope: 'shared',
    revision: '1',
    config,
  };
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store: new MemoryViewStore({ instances: [pending, board] }),
    resolveSource: () => source,
  });
  const runtime = (await engine.open('board')) as DashboardViewRuntime;
  // 「改这里的展示」 is on offer only while the board is built, and an edit
  // is refused while it is read (Q-02).
  runtime.setBuilding(true);
  return runtime;
}

/** The dialog over one of the board's panels, open until it asks to close. */
function Harness({
  board,
  panelId,
}: {
  board: DashboardViewRuntime;
  panelId: string;
}) {
  const dashboard = useDashboard(board);
  const [open, setOpen] = useState(true);
  const panel = open
    ? (dashboard.panels.find(entry => entry.id === panelId) ?? null)
    : null;
  return (
    <PresentationDialog
      panel={panel}
      name="Mine"
      editing={board}
      onOpenChange={next => setOpen(next)}
    />
  );
}

function resetOf(dialog: HTMLElement): HTMLButtonElement {
  return within(dialog).getByRole('button', {
    name: 'Look as the view does',
  }) as HTMLButtonElement;
}

function look(board: DashboardViewRuntime, panelId: string) {
  const found = board
    .getSnapshot()
    .draft.panels.find(entry => entry.id === panelId);
  return found?.kind === 'view' ? found.presentation : undefined;
}

describe('PresentationDialog', () => {
  it('writes only what differs from the panel’s own view, and a look back to it is no override', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const board = await openBoard(dashboardConfig({ panels: [owned()] }));
    render(<Harness board={board} panelId="mine" />);

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { name: 'How “Mine” looks here' }),
    ).toBeTruthy();
    // Nothing to put back yet.
    expect(resetOf(dialog).disabled).toBe(true);

    // The view's own bar, drawn: only the layout differs from the view.
    await user.click(await within(dialog).findByRole('radio', { name: 'bar' }));
    expect(look(board, 'mine')).toEqual({ layout: 'chart' });
    expect(resetOf(dialog).disabled).toBe(false);

    // Back to the table the view is drawn as: the look says nothing the
    // view does not, so it is no look at all.
    await user.click(within(dialog).getByRole('radio', { name: 'Table' }));
    expect(look(board, 'mine')).toBeUndefined();
    expect(resetOf(dialog).disabled).toBe(true);

    await user.click(within(dialog).getByRole('radio', { name: 'pie' }));
    const chosen = look(board, 'mine');
    expect(chosen).toMatchObject({ layout: 'chart', chart: { type: 'pie' } });
    // The owned config's own table is not repeated in the look.
    expect(chosen).not.toHaveProperty('table');
  });

  it('opens the options and comes back, the keyboard following, and writes what they change', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const board = await openBoard(
      dashboardConfig({ panels: [owned({ layout: 'chart' })] }),
    );
    render(<Harness board={board} panelId="mine" />);
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByRole('radio', { name: 'bar' });
    // The panel already wears a look, so it can be put back.
    await waitFor(() => expect(resetOf(dialog).disabled).toBe(false));

    await user.click(within(dialog).getByRole('radio', { name: 'Table' }));
    await user.click(
      within(dialog).getByRole('button', { name: 'Table options' }),
    );
    const back = await within(dialog).findByRole('button', {
      name: 'Back to the chart types',
    });
    await waitFor(() =>
      expect(document.activeElement?.tagName).toMatch(/^H\d$/),
    );
    await user.click(
      within(dialog).getByRole('checkbox', { name: /Totals row/ }),
    );
    expect(look(board, 'mine')).toEqual({
      table: { columns: [], totals: true },
    });

    await user.click(back);
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(dialog).getByRole('button', { name: 'Table options' }),
      ),
    );

    await user.click(within(dialog).getByRole('radio', { name: 'bar' }));
    await user.click(
      within(dialog).getByRole('button', { name: 'bar options' }),
    );
    await user.click(
      await within(dialog).findByRole('tab', { name: 'Display' }),
    );
    await user.click(
      within(dialog).getByRole('checkbox', { name: 'Horizontal' }),
    );
    // The chart the options wrote, the totals row picked before kept.
    expect(look(board, 'mine')).toMatchObject({
      layout: 'chart',
      chart: { type: 'bar', cartesian: { orientation: 'horizontal' } },
      table: { totals: true },
    });
  });

  it('puts back the look the panel had when it opened, on Cancel', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const board = await openBoard(
      dashboardConfig({ panels: [owned({ layout: 'chart' })] }),
    );
    render(<Harness board={board} panelId="mine" />);
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('radio', { name: 'pie' }));
    expect(look(board, 'mine')).toMatchObject({ chart: { type: 'pie' } });

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(look(board, 'mine')).toEqual({ layout: 'chart' });
  });

  it('opens the options over a first answer still on its way; a look asks nothing, the totals row asks its whole', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const source = testSource({
      aggregate: vi.fn(() => new Promise<RecordData[]>(() => {})),
    });
    const board = await openBoard(
      dashboardConfig({ panels: [owned()] }),
      source,
    );
    render(<Harness board={board} panelId="mine" />);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(source.aggregate).toHaveBeenCalledTimes(1));

    await user.click(
      await within(dialog).findByRole('radio', { name: 'Table' }),
    );
    await user.click(
      within(dialog).getByRole('button', { name: 'Table options' }),
    );
    // A look is drawn from the rows the panel has: nothing more is asked.
    expect(source.aggregate).toHaveBeenCalledTimes(1);
    await user.click(
      await within(dialog).findByRole('checkbox', { name: /Totals row/ }),
    );
    expect(look(board, 'mine')).toEqual({
      table: { columns: [], totals: true },
    });
    // Only the totals row is a question of its own, and travels with the
    // panel's: the whole, asked with no dimension.
    await waitFor(() =>
      expect(
        vi
          .mocked(source.aggregate)
          .mock.calls.some(([query]) => query.groupBy === undefined),
      ).toBe(true),
    );
  });

  it.each([
    ['a note', note],
    ['a record view', onRecords],
  ])('has no look to change on %s, and Done closes it', async (_, panel) => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const board = await openBoard(dashboardConfig({ panels: [panel] }));
    render(<Harness board={board} panelId={panel.id} />);
    const dialog = await screen.findByRole('dialog');
    expect(
      dialog.querySelector('[data-slot="panel-presentation-nothing"]'),
    ).not.toBeNull();
    expect(within(dialog).queryByRole('radio')).toBeNull();
    expect(resetOf(dialog).disabled).toBe(true);

    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(board.getSnapshot().draft.panels).toEqual([panel]);
  });
});
