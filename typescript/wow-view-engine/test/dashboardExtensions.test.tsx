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
 * What the default workbench plugs into the board's building through
 * `DashboardEditExtensions` (D22 C–E), driven through the real edit mode —
 * 「编辑」, 「＋ 添加 ▾」, a panel's 「⋯」: the tab bar (only the tab on screen
 * runs, the reader's last tab remembered, the host told which tab is
 * shown), a new analysis made in a dialog and put on the board, saved as a
 * view of its own, and a panel's own look chosen with the visualization
 * panel and put back. The edit bar and the panel menu themselves are
 * `dashboardBuilding.test.tsx`'s.
 */

import {
  act,
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
  isOwnedPanel,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { useDashboard } from '../src/react/index.js';
import {
  DashboardTabs,
  DashboardWorkbench,
  EmbeddedDashboard,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testSource,
} from './fixtures.js';
import { pending } from './fixtures/dashboard.js';

afterEach(cleanup);

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
};

function onAnalysis(
  id: string,
  tab?: string,
  overrides: Partial<DashboardPanel> = {},
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId: 'by-warehouse',
    bindings: [],
    layout: { x: 0, y: 0, w: 12, h: 4 },
    ...(tab ? { tab } : {}),
    ...overrides,
  } as DashboardPanel;
}

const tabbed = dashboardConfig({
  tabs: [
    { id: 'overview', title: 'Overview' },
    { id: 'detail', title: 'Detail' },
  ],
  panels: [
    onAnalysis('a', 'overview', { title: 'Orders by warehouse' }),
    onAnalysis('c', 'detail', { title: 'Detail by warehouse' }),
  ],
});

function setup(config: DashboardViewConfig = tabbed) {
  const source = testSource();
  const board: ViewInstance = {
    id: 'board',
    definitionId: 'overview',
    title: 'Operations',
    scope: 'shared',
    revision: '1',
    config,
  };
  const store = new MemoryViewStore({
    instances: [pending, byWarehouse, board],
  });
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => source,
  });
  const asked = () => vi.mocked(source.aggregate).mock.calls.length;
  const runtime = () =>
    engine
      .openRuntimes()
      .find(
        (open): open is DashboardViewRuntime =>
          open instanceof DashboardViewRuntime,
      ) ?? null;
  return { engine, source, store, asked, runtime };
}

type User = ReturnType<typeof userEvent.setup>;

/** 「编辑」: the board's building state, as its author enters it. */
async function startBuilding(user: User) {
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await screen.findByRole('region', { name: 'Editing' });
}

/** One item of a panel's 「⋯」 menu. */
async function panelMenu(user: User, name: string, item: string) {
  await user.click(
    await screen.findByRole('button', { name: `Actions for “${name}”` }),
  );
  const menu = await screen.findByRole('menu');
  await user.click(within(menu).getByRole('menuitem', { name: item }));
}

/** 「＋ 添加 ▾」 → one item. */
async function addMenu(user: User, item: string) {
  await user.click(screen.getByRole('button', { name: 'Add' }));
  await user.click(await screen.findByRole('menuitem', { name: item }));
}

describe('the tab bar', () => {
  it('shows the tabs, runs only the one on screen, and tells the host and the preferences', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, store, asked } = setup();
    const onTabChange = vi.fn();

    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
        onTabChange={onTabChange}
      />,
    );
    const tabs = await screen.findByRole('tablist', { name: 'Tabs' });
    expect(
      within(tabs)
        .getAllByRole('tab')
        .map(tab => tab.textContent),
    ).toEqual(['Overview', 'Detail']);
    await waitFor(() => expect(onTabChange).toHaveBeenCalledWith('overview'));
    expect(screen.getByText('Orders by warehouse')).toBeTruthy();
    expect(screen.queryByText('Detail by warehouse')).toBeNull();
    await waitFor(() => expect(asked()).toBe(1));

    await user.click(within(tabs).getByRole('tab', { name: 'Detail' }));

    await waitFor(() =>
      expect(screen.getByText('Detail by warehouse')).toBeTruthy(),
    );
    expect(screen.queryByText('Orders by warehouse')).toBeNull();
    // What the bar switches is the tab on screen's panel, named after it.
    expect(screen.getByRole('tabpanel', { name: 'Detail' })).toBeTruthy();
    await waitFor(() => expect(asked()).toBe(2));
    expect(onTabChange).toHaveBeenLastCalledWith('detail');
    await waitFor(async () =>
      expect((await store.getPreferences('overview')).lastTabs).toEqual({
        board: 'detail',
      }),
    );
  });

  it('opens on the tab the host names', async () => {
    const { engine, asked } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
        initialTab="detail"
      />,
    );
    await waitFor(() =>
      expect(screen.getByText('Detail by warehouse')).toBeTruthy(),
    );
    expect(
      screen.getByRole('tab', { name: 'Detail' }).getAttribute('aria-selected'),
    ).toBe('true');
    await waitFor(() => expect(asked()).toBe(1));
  });

  it('hands the host’s tab to the first board an uncontrolled workbench opens', async () => {
    const { engine } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        initialTab="detail"
      />,
    );
    await waitFor(() =>
      expect(screen.getByText('Detail by warehouse')).toBeTruthy(),
    );
  });

  it('draws no bar for one tab or none', async () => {
    const { engine } = setup(
      dashboardConfig({ panels: [onAnalysis('a', undefined, { title: 'A' })] }),
    );
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await waitFor(() => expect(screen.getByText('A')).toBeTruthy());
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('adds, renames, moves and deletes tabs while the board is built', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup(
      dashboardConfig({ panels: [onAnalysis('a', undefined, { title: 'A' })] }),
    );
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await waitFor(() => expect(screen.getByText('A')).toBeTruthy());
    await startBuilding(user);

    // One tab or none: the only thing on the bar is the way to add one.
    await user.click(screen.getByRole('button', { name: 'Add a tab' }));
    const field = await screen.findByRole('textbox', {
      name: 'Name of tab “Tab 2”',
    });
    // The new tab is shown and its name is ready to be typed over; the
    // panels already on the board went to the first one.
    await waitFor(() => expect(document.activeElement).toBe(field));
    expect(runtime()?.getSnapshot().tab).toBe(
      runtime()?.getSnapshot().draft.tabs[1].id,
    );
    expect(screen.getByText('This tab has no panels yet')).toBeTruthy();
    await user.clear(field);
    await user.type(field, 'Retries{Enter}');
    expect(
      runtime()
        ?.getSnapshot()
        .draft.tabs.map(tab => tab.title),
    ).toEqual(['Overview', 'Retries']);
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Retries' }),
      ),
    );

    // Moved by its menu…
    await user.click(screen.getByRole('button', { name: 'Tab “Retries”' }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Move left' }),
    );
    expect(
      runtime()
        ?.getSnapshot()
        .draft.tabs.map(tab => tab.title),
    ).toEqual(['Retries', 'Overview']);
    // …and by the arrows on its handle.
    const handle = screen.getByRole('button', { name: 'Reorder “Retries”' });
    handle.focus();
    await user.keyboard('{ArrowRight}');
    expect(
      runtime()
        ?.getSnapshot()
        .draft.tabs.map(tab => tab.title),
    ).toEqual(['Overview', 'Retries']);
    expect(
      document.querySelector('[data-slot="dashboard-announcement"]')
        ?.textContent,
    ).toBe('“Retries” is now tab 2 of 2');

    // A tab that carries panels is asked about before it goes.
    await user.click(screen.getByRole('button', { name: 'Tab “Overview”' }));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Delete tab' }),
    );
    const question = await screen.findByRole('alertdialog');
    expect(question.textContent).toContain('Its one panel is deleted with it');
    await user.click(
      within(question).getByRole('button', { name: 'Delete tab and panels' }),
    );
    expect(
      runtime()
        ?.getSnapshot()
        .draft.tabs.map(tab => tab.title),
    ).toEqual(['Retries']);
    expect(runtime()?.getSnapshot().draft.panels).toEqual([]);
    // Nothing is written until the board is saved.
    expect(runtime()?.getSnapshot().dirty).toBe(true);
  });

  it('renames a tab by double-clicking it, and Escape keeps the old name', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    // Being built, the bar is a list of the tabs to arrange; the tab on
    // screen is the one pressed in.
    expect(await screen.findByRole('list', { name: 'Tabs' })).toBeTruthy();
    expect(
      screen
        .getByRole('button', { name: 'Overview' })
        .getAttribute('aria-current'),
    ).toBe('true');
    await user.dblClick(screen.getByRole('button', { name: 'Detail' }));
    const field = await screen.findByRole('textbox', {
      name: 'Name of tab “Detail”',
    });
    await user.type(field, 'x{Escape}');
    expect(runtime()?.getSnapshot().draft.tabs[1].title).toBe('Detail');
    // A tab without panels goes without a question.
    await user.click(screen.getByRole('button', { name: 'Tab “Detail”' }));
    act(() => runtime()?.movePanelToTab('c', 'overview'));
    await user.click(
      await screen.findByRole('menuitem', { name: 'Delete tab' }),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(runtime()?.getSnapshot().draft.tabs).toHaveLength(1);
  });

  it('switches tabs in an embed too, and remembers nothing there', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, store } = setup();
    render(<EmbeddedDashboard engine={engine} instanceId="board" />);
    await user.click(await screen.findByRole('tab', { name: 'Detail' }));
    await waitFor(() =>
      expect(screen.getByText('Detail by warehouse')).toBeTruthy(),
    );
    expect(screen.queryByRole('button', { name: 'Add a tab' })).toBeNull();
    expect((await store.getPreferences('overview')).lastTabs).toBeUndefined();
  });
});

describe('the panel menu’s look entries', () => {
  it('offers 恢复为视图的样子 only over a look of its own', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await user.click(
      await screen.findByRole('button', {
        name: 'Actions for “Orders by warehouse”',
      }),
    );
    let menu = await screen.findByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'Change how it looks here…' }),
    ).toBeTruthy();
    expect(
      within(menu).queryByRole('menuitem', { name: 'Look as the view does' }),
    ).toBeNull();
    await user.keyboard('{Escape}');

    act(() =>
      runtime()?.setPresentation('a', {
        layout: 'chart',
        chart: {
          type: 'pie',
          pie: { category: 'warehouse', value: 'orders' },
        },
      }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Actions for “Orders by warehouse”' }),
    );
    menu = await screen.findByRole('menu');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Look as the view does' }),
    );
    expect(runtime()?.getSnapshot().draft.panels[0]).not.toHaveProperty(
      'presentation',
    );
  });

  it('moves a panel to another tab, where the tab it left says it is empty', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await panelMenu(user, 'Orders by warehouse', 'Move to tab');
    await screen.findByRole('menuitem', { name: 'Detail' });
    await user.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('Detail'),
    );
    await user.keyboard('{Enter}');
    await waitFor(() =>
      expect(
        runtime()
          ?.getSnapshot()
          .draft.panels.find(entry => entry.id === 'a')?.tab,
      ).toBe('detail'),
    );
    expect(await screen.findByText('This tab has no panels yet')).toBeTruthy();
  });
});

describe('a new analysis made in the dashboard', () => {
  it('is the analysis view in a dialog, named by its reading, and lands on the tab on screen', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
        initialTab="detail"
      />,
    );
    await startBuilding(user);
    await addMenu(user, 'New analysis…');

    const dialog = await screen.findByRole('dialog');
    // One data can be analysed, so it is chosen already.
    expect(
      within(dialog).getByRole('heading', { name: 'New analysis · Orders' }),
    ).toBeTruthy();
    // The tray and the result of the analysis view itself.
    await waitFor(() =>
      expect(
        dialog.querySelector('[data-slot="new-analysis-tray"]'),
      ).not.toBeNull(),
    );
    const title = within(dialog).getByRole('textbox', { name: 'Title' });
    await waitFor(() =>
      expect((title as HTMLInputElement).value).toBe(
        'By Warehouse · Record count',
      ),
    );
    // The visualization panel opens beside the result, and its way back
    // says what it does here, where there is no view list.
    await user.click(within(dialog).getByRole('button', { name: 'Visualize' }));
    await user.click(
      within(dialog).getByRole('button', { name: 'Close the visualization' }),
    );
    await user.click(
      within(dialog).getByRole('button', { name: 'Put on the dashboard' }),
    );

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const added = runtime()
      ?.getSnapshot()
      .draft.panels.find(entry => isOwnedPanel(entry));
    expect(added).toMatchObject({
      title: 'By Warehouse · Record count',
      tab: 'detail',
    });
    expect(screen.getByText('By Warehouse · Record count')).toBeTruthy();
    expect(
      document.querySelector('[data-slot="dashboard-announcement"]')
        ?.textContent,
    ).toBe('“By Warehouse · Record count” was put on the dashboard');
    // The view it held for the dialog is let go.
    expect(
      engine.openRuntimes().filter(open => open.kind === 'analysis'),
    ).toEqual([]);
  });

  it('keeps the title its author typed, and cancels without a trace', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    const before = engine.openRuntimes().length;
    await addMenu(user, 'New analysis…');
    const dialog = await screen.findByRole('dialog');
    const title = within(dialog).getByRole('textbox', { name: 'Title' });
    await waitFor(() => expect(engine.openRuntimes().length).toBe(before + 1));
    await user.type(title, 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(engine.openRuntimes().length).toBe(before);
    expect(runtime()?.getSnapshot().draft.panels).toHaveLength(2);
  });

  it('is saved as a view of its own, the panel pointing at it', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, store, runtime } = setup(
      dashboardConfig({
        panels: [
          {
            id: 'mine',
            kind: 'view',
            title: 'Mine',
            owned: { definitionId: 'orders', config: analysisConfig() },
            bindings: [],
            layout: { x: 0, y: 0, w: 12, h: 4 },
          } as DashboardPanel,
        ],
      }),
    );
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await panelMenu(user, 'Mine', 'Save as a view…');

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { name: 'Save as a view' }),
    ).toBeTruthy();
    expect(dialog.textContent).toContain('It becomes a view of Orders');
    // The board's audience first.
    expect(
      within(dialog)
        .getByRole('radio', { name: 'Everyone' })
        .getAttribute('aria-checked'),
    ).toBe('true');
    const title = within(dialog).getByRole('textbox', { name: 'Title' });
    expect((title as HTMLInputElement).value).toBe('Mine');
    await user.click(within(dialog).getByRole('button', { name: 'Save view' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const saved = (await store.list('orders')).find(
      summary => summary.title === 'Mine',
    );
    expect(saved?.scope).toBe('shared');
    expect(runtime()?.getSnapshot().draft.panels[0]).toMatchObject({
      instanceId: saved?.id,
    });
  });
  /**
   * D26 Q34 names the board 仪表盘 wherever the chrome reports on it; what
   * this dialog makes is a view, so its refusal still says view.
   */
  it('says a refused save of it as a view’s: a view is what it makes', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine } = setup(
      dashboardConfig({
        panels: [
          {
            id: 'mine',
            kind: 'view',
            title: 'Mine',
            owned: { definitionId: 'orders', config: analysisConfig() },
            bindings: [],
            layout: { x: 0, y: 0, w: 12, h: 4 },
          } as DashboardPanel,
        ],
      }),
    );
    vi.spyOn(engine, 'saveOwnedView').mockRejectedValue(new Error('down'));
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await panelMenu(user, 'Mine', 'Save as a view…');
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Save view' }));

    expect(
      await within(dialog).findByText('This view could not be saved.'),
    ).toBeTruthy();
  });
});

describe('a panel’s own look', () => {
  it('is picked with the visualization panel, marked on the panel, redrawn without a query, and put back', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime, asked } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await waitFor(() => expect(asked()).toBe(1));
    await panelMenu(user, 'Orders by warehouse', 'Change how it looks here…');

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { level: 2, name: /looks here/ }),
    ).toBeTruthy();
    await user.click(await within(dialog).findByRole('radio', { name: 'pie' }));

    expect(runtime()?.getSnapshot().draft.panels[0]).toMatchObject({
      presentation: { layout: 'chart', chart: { type: 'pie' } },
    });
    const mark = document.querySelector<HTMLElement>(
      '[data-slot="panel-presentation"]',
    );
    expect(mark?.textContent).toBe('Shown here as pie');
    // Why, on focus and a tap as well as a hover (U-11): no `title`.
    expect(mark?.tagName).toBe('BUTTON');
    expect(mark?.getAttribute('title')).toBeNull();
    expect(
      document.getElementById(mark!.getAttribute('aria-describedby')!)
        ?.textContent,
    ).toBe(
      'This panel looks different from the view it shows, on purpose: it was changed for this dashboard only.',
    );
    // Presentation never asks the source (D20).
    expect(asked()).toBe(1);

    await user.click(
      within(dialog).getByRole('button', { name: 'Look as the view does' }),
    );
    expect(runtime()?.getSnapshot().draft.panels[0]).not.toHaveProperty(
      'presentation',
    );
    expect(
      document.querySelector('[data-slot="panel-presentation"]'),
    ).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('is put back as it was when the dialog is cancelled', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    const { engine, runtime } = setup();
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="board"
      />,
    );
    await startBuilding(user);
    await waitFor(() =>
      expect(runtime()?.getSnapshot().panels[0].runtime).not.toBeNull(),
    );
    await panelMenu(user, 'Orders by warehouse', 'Change how it looks here…');
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('radio', { name: 'pie' }));
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(runtime()?.getSnapshot().draft.panels[0]).not.toHaveProperty(
      'presentation',
    );
  });
});

describe('DashboardTabs alone', () => {
  it('draws nothing for a board without tabs, being read', async () => {
    const { engine } = setup(dashboardConfig());
    const board = (await engine.open('board')) as DashboardViewRuntime;
    function Harness() {
      const dashboard = useDashboard(board);
      return (
        <div data-testid="bar">
          <DashboardTabs dashboard={dashboard} />
        </div>
      );
    }
    render(<Harness />);
    expect(screen.getByTestId('bar').childElementCount).toBe(0);
  });
});
