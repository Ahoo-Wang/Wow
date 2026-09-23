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
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defaultPanelSize } from '../src/dashboard/index.js';
import {
  MemoryViewStore,
  ViewEngine,
  type DashboardDefinition,
  type DashboardRuntime,
  type DashboardPanel,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewScope,
} from '../src/index.js';
import {
  DashboardEditExtensionsContext,
  DashboardWorkbench,
  type DashboardEditExtensions,
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';
import { landed, tracked } from './fixtures/writes.js';

afterEach(cleanup);

/** An analysis of the orders by warehouse: a chart, half the board wide. */
const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
};

/** One reader's own view: on a shared board, seen by that reader alone. */
const mine: ViewInstance = {
  ...pending,
  id: 'mine',
  title: 'My orders',
  scope: 'personal',
};

interface Setup {
  scope?: ViewScope;
  panels?: DashboardPanel[];
  config?: Partial<DashboardViewConfig>;
  /** Whether this reader may save dashboards. */
  canSave?: boolean;
  definition?: DashboardDefinition;
  extensions?: DashboardEditExtensions;
  onOpenView?: (instanceId: string, filter: unknown) => void;
  instanceId?: string;
}

function open({
  scope = 'personal',
  panels = [panel({ title: 'Pending' })],
  config = {},
  canSave = true,
  definition = overviewDefinition(),
  extensions,
  onOpenView,
  instanceId = 'overview-1',
}: Setup = {}) {
  const board: ViewInstance = {
    id: 'overview-1',
    definitionId: 'overview',
    title: 'Operations',
    scope,
    revision: '1',
    config: dashboardConfig({ panels, ...config }),
  };
  const store = tracked(
    new MemoryViewStore({
      instances: [pending, byWarehouse, mine, board],
      ...(canSave
        ? {}
        : {
            permissions: () => ({
              createPersonal: false,
              createShared: false,
              reorder: true,
              setDefault: true,
              instance: () => ({ save: false, rename: false, delete: false }),
            }),
          }),
    }),
  );
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), definition],
    store,
    resolveSource: () => testSource(),
  });
  const wrap = (node: ReactNode) =>
    extensions ? (
      <DashboardEditExtensionsContext.Provider value={extensions}>
        {node}
      </DashboardEditExtensionsContext.Provider>
    ) : (
      node
    );
  render(
    wrap(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId={instanceId}
        onOpenView={onOpenView}
      />,
    ),
  );
  // A submenu's items are still sliding in when a pointer reaches them.
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  return { engine, store, user };
}

const slot = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);
const slots = (name: string) => [
  ...document.querySelectorAll<HTMLElement>(`[data-slot="${name}"]`),
];
const titles = () => slots('panel-title').map(title => title.textContent);

/** The dashboard the workbench has open. */
function boardOf(engine: ViewEngine): DashboardRuntime {
  const board = engine
    .openRuntimes()
    .find(runtime => runtime.kind === 'dashboard');
  if (!board) throw new Error('no dashboard is open');
  return board as DashboardRuntime;
}

async function enter(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await screen.findByRole('region', { name: 'Editing' });
}

async function panelMenu(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
) {
  await user.click(
    await screen.findByRole('button', { name: `Actions for “${name}”` }),
  );
  return screen.findByRole('menu');
}

async function add(user: ReturnType<typeof userEvent.setup>, item: string) {
  await user.click(screen.getByRole('button', { name: 'Add' }));
  await user.click(await screen.findByRole('menuitem', { name: item }));
}

describe('reading and building a dashboard (D22 A)', () => {
  it('offers 编辑 to whoever may save it, and nothing moves until it is pressed', async () => {
    const { user } = open();
    await screen.findByText('Pending', { selector: 'h3' });

    // Read: no handle, no corner, no arrange menu — only the way in.
    expect(slot('panel-grip')).toBeNull();
    expect(slot('panel-arrange')).toBeNull();
    expect(slot('panel-resize')).toBeNull();
    expect(slot('dashboard-edit-bar')).toBeNull();

    await enter(user);
    expect(slot('panel-grip')).not.toBeNull();
    expect(slot('panel-arrange')).not.toBeNull();
    // The bar's 完成 is the save while it is up; Save is not beside it.
    expect(slot('save-actions')).toBeNull();
    expect(slot('dashboard-edit')).toBeNull();
    // Nothing had focus to give back but the pressed button, which went:
    // the bar that took its place has it.
    expect(document.activeElement?.textContent).toBe('Editing');
  });

  it('offers a system dashboard 另存为 and no 编辑', async () => {
    open({
      definition: overviewDefinition({
        views: [
          {
            id: 'ops',
            title: 'Shipped with the release',
            config: dashboardConfig({ panels: [panel({ title: 'Pending' })] }),
          },
        ],
      }),
      instanceId: 'system:overview:ops',
    });
    await screen.findByText('Pending', { selector: 'h3' });
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(
      within(slot('save-actions')!).getByRole('button', { name: 'Save as' }),
    ).toBeTruthy();
  });

  it('offers no 编辑 to a reader who may not save the board', async () => {
    open({ canSave: false });
    await screen.findByText('Pending', { selector: 'h3' });
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull();
  });

  it('saves on 完成 and reads the board again', async () => {
    const { store, user } = open();
    await enter(user);
    const menu = await panelMenu(user, 'Pending');
    await user.click(within(menu).getByRole('menuitem', { name: 'Rename' }));
    const input = screen.getByRole('textbox', { name: 'Panel title' });
    expect(document.activeElement).toBe(input);
    await user.clear(input);
    await user.type(input, 'Waiting{Enter}');
    expect(titles()).toEqual(['Waiting']);

    const saved = landed(store);
    await user.click(screen.getByRole('button', { name: 'Done' }));
    await saved;
    const stored = await store.get('overview-1');
    expect(
      (stored.config as DashboardViewConfig).panels.map(entry => entry.title),
    ).toEqual(['Waiting']);
    await waitFor(() => expect(slot('dashboard-edit-bar')).toBeNull());
    expect(slot('panel-grip')).toBeNull();
    // The keyboard is back on the way in.
    expect(document.activeElement).toBe(slot('dashboard-edit'));
  });

  it('asks before 完成 writes over a shared board, naming it', async () => {
    const { store, user } = open({ scope: 'shared' });
    await enter(user);
    await add(user, 'Heading');
    await user.keyboard('{Enter}');

    await user.click(screen.getByRole('button', { name: 'Done' }));
    const question = await screen.findByRole('alertdialog');
    expect(question.textContent).toContain('Operations');
    const saved = landed(store);
    await user.click(
      within(question).getByRole('button', { name: 'Update for everyone' }),
    );
    await saved;
    expect(
      (await store.get('overview-1')).config.kind === 'dashboard' &&
        (
          (await store.get('overview-1')).config as DashboardViewConfig
        ).panels.some(entry => entry.kind === 'heading'),
    ).toBe(true);
  });

  /**
   * One way to do one thing: while the board is built its edit bar holds
   * 完成 and 取消, so the title bar's 「已修改 ↺」 is not beside them. Once
   * the building ends — by 取消 or by 完成 — the title bar says and undoes
   * an unsaved change as it always has.
   */
  it('leaves 「已修改 ↺」 to the edit bar while the board is built', async () => {
    const { engine, user } = open({
      config: { fields: [{ name: 'region', label: 'Region', kind: 'string' }] },
    });
    const edited = () => slot('view-unsaved');
    const revert = () => slot('view-revert');
    /** A change the building did not make: a global condition composed. */
    const compose = () =>
      act(() =>
        boardOf(engine).edit({
          filter: {
            op: 'and',
            children: [{ field: 'region', operator: 'EQ', value: 'north' }],
          },
        }),
      );

    for (const leave of ['Cancel', 'Done'] as const) {
      await enter(user);
      await add(user, 'Heading');
      await user.keyboard('{Enter}');
      expect(boardOf(engine).getSnapshot().dirty).toBe(true);
      // Changed, and nothing in the title bar says so or undoes it.
      expect(edited()).toBeNull();
      expect(revert()).toBeNull();

      await user.click(screen.getByRole('button', { name: leave }));
      if (leave === 'Cancel')
        await user.click(
          within(await screen.findByRole('alertdialog')).getByRole('button', {
            name: 'Revert',
          }),
        );
      await waitFor(() => expect(slot('dashboard-edit-bar')).toBeNull());

      // Read again: an unsaved change wears the mark and its ↺ as before.
      await compose();
      expect(edited()).not.toBeNull();
      await user.click(revert()!);
      await user.click(
        within(await screen.findByRole('alertdialog')).getByRole('button', {
          name: 'Revert',
        }),
      );
      await waitFor(() => expect(edited()).toBeNull());
    }
  });

  it('leaves at once on 完成 with nothing to save', async () => {
    const { store, user } = open();
    await enter(user);
    const save = vi.spyOn(store, 'save');
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(slot('dashboard-edit-bar')).toBeNull();
    expect(save).not.toHaveBeenCalled();
  });

  it('puts back the saved board on 取消, asking first', async () => {
    const { user } = open();
    await enter(user);
    await add(user, 'Heading');
    await user.keyboard('{Enter}');
    expect(titles()).toContain('New section');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    const question = await screen.findByRole('alertdialog');
    await user.click(within(question).getByRole('button', { name: 'Revert' }));
    await waitFor(() => expect(titles()).toEqual(['Pending']));
    expect(slot('dashboard-edit-bar')).toBeNull();
    expect(screen.queryByText('Edited')).toBeNull();
  });
});

describe('adding to a board (D22 A, B)', () => {
  it('adds a saved view from the picker, sized by what it shows', async () => {
    const { engine, user } = open({ scope: 'shared' });
    await enter(user);
    await add(user, 'Saved view…');
    const picker = await screen.findByRole('dialog');
    const rows = await within(picker).findAllByRole('button', {
      name: /Pending orders|By warehouse|My orders/,
    });
    expect(rows).toHaveLength(3);

    // Grouped as the switcher groups them, with what is true of each here.
    const groups = within(picker)
      .getAllByRole('heading', { level: 3 })
      .map(heading => heading.textContent);
    expect(groups).toEqual(['System views', 'Shared views', 'My views']);
    const row = (name: string) =>
      within(picker).getByRole('button', { name: new RegExp(name) });
    expect(row('Pending orders').textContent).toContain('On the board');
    expect(row('My orders').textContent).toContain('Only you can see it');
    expect(row('By warehouse').textContent).not.toContain('On the board');

    // Searched by name, and narrowed by kind.
    await user.type(
      within(picker).getByRole('searchbox', { name: 'Search by name' }),
      'ware',
    );
    expect(
      within(picker).getAllByRole('button', { name: /orders|warehouse/i }),
    ).toHaveLength(1);
    await user.clear(within(picker).getByRole('searchbox'));
    await user.click(within(picker).getByRole('button', { name: 'Records' }));
    expect(
      within(picker).queryByRole('button', { name: /By warehouse/ }),
    ).toBeNull();
    await user.click(within(picker).getByRole('button', { name: 'All' }));

    await user.click(row('By warehouse'));
    await waitFor(() => expect(titles()).toContain('By warehouse'));
    // Loaded before it was placed, so it starts at the size of what it
    // shows (D22 A) rather than at a chart's for everything.
    const board = engine
      .openRuntimes()
      .find(runtime => runtime.kind === 'dashboard')!;
    const config = board.getSnapshot().draft as DashboardViewConfig;
    const placed = config.panels.find(
      entry =>
        entry.kind === 'view' &&
        'instanceId' in entry &&
        entry.instanceId === 'by-warehouse',
    )!;
    expect({ w: placed.layout.w, h: placed.layout.h }).toEqual(
      defaultPanelSize({ kind: 'view' }, byWarehouse.config),
    );
    expect(screen.getByText('Added “By warehouse”')).toBeTruthy();
    // Back where it was asked from.
    await waitFor(() =>
      expect(document.activeElement).toBe(slot('dashboard-add')),
    );
  });

  it('adds a heading and names it in place', async () => {
    const { user } = open();
    await enter(user);
    await add(user, 'Heading');
    const input = await screen.findByRole('textbox', { name: 'Heading text' });
    await user.clear(input);
    await user.type(input, 'Warehouses{Enter}');
    expect(titles()).toContain('Warehouses');
    // A section's name, set on no body.
    const heading = slots('dashboard-panel').find(
      card => card.dataset.kind === 'heading',
    )!;
    expect(heading.querySelector('[role="group"]')).toBeNull();
  });

  it('writes a note, a picture and links through their forms', async () => {
    const { user } = open();
    await enter(user);

    await add(user, 'Text…');
    let form = await screen.findByRole('dialog');
    const text = within(form).getByRole('textbox', { name: 'Text' });
    await user.clear(text);
    await user.type(text, 'Read me first');
    await user.click(within(form).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByText('Read me first')).toBeTruthy();

    await add(user, 'Image…');
    form = await screen.findByRole('dialog');
    // Nothing typed is a mistake only once it is tried.
    expect(form.querySelector('[data-invalid]')).toBeNull();
    await user.click(within(form).getByRole('button', { name: 'Add' }));
    expect(within(form).getByText('Fill this in.')).toBeTruthy();
    await user.type(
      within(form).getByRole('textbox', { name: 'Image address' }),
      'javascript:alert(1)',
    );
    expect(
      within(form).getByText(
        'Only http, https, mailto and relative links can be shown.',
      ),
    ).toBeTruthy();
    await user.clear(
      within(form).getByRole('textbox', { name: 'Image address' }),
    );
    await user.type(
      within(form).getByRole('textbox', { name: 'Image address' }),
      '/plan.png',
    );
    await user.type(
      within(form).getByRole('textbox', { name: 'Description' }),
      'Floor plan',
    );
    await user.click(within(form).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('img', { name: 'Floor plan' })).toBeTruthy();

    await add(user, 'Links…');
    form = await screen.findByRole('dialog');
    await user.type(
      within(form).getByRole('textbox', { name: 'Text' }),
      'Runbook',
    );
    await user.type(
      within(form).getByRole('textbox', { name: 'Address' }),
      'https://example.com/runbook',
    );
    await user.click(within(form).getByRole('button', { name: 'Add a link' }));
    expect(form.querySelectorAll('[data-slot="content-link"]')).toHaveLength(2);
    await user.click(
      within(form).getByRole('button', { name: 'Remove link 2' }),
    );
    await user.click(within(form).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('link', { name: /^Runbook/ })).toBeTruthy();

    // And edited again from the panel's menu.
    const menu = await panelMenu(user, 'Links');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Edit content…' }),
    );
    form = await screen.findByRole('dialog');
    const label = within(form).getByRole('textbox', { name: 'Text' });
    await user.clear(label);
    await user.type(label, 'On-call runbook');
    await user.click(within(form).getByRole('button', { name: 'Update' }));
    await waitFor(() =>
      expect(
        screen.getByRole('link', { name: /^On-call runbook/ }),
      ).toBeTruthy(),
    );
  });

  it('offers an empty board its first steps, and a reader who cannot build it nothing', async () => {
    const { user } = open({ panels: [] });
    const empty = await waitFor(() => {
      const found = slot('dashboard-empty');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(
      within(empty)
        .getAllByRole('button')
        .map(button => button.textContent),
      // The workbench provides the new-analysis dialog, so its first step is
      // there too (`DashboardEditExtensions.onAddOwnedAnalysis`).
    ).toEqual(['Add a view…', 'New analysis…', 'Add a heading']);
    await user.click(
      within(empty).getByRole('button', { name: 'Add a heading' }),
    );
    // The first step starts the building with it.
    expect(slot('dashboard-edit-bar')).not.toBeNull();
    expect(screen.getByRole('textbox', { name: 'Heading text' })).toBeTruthy();
    cleanup();

    open({ panels: [], canSave: false });
    const read = await waitFor(() => {
      const found = slot('dashboard-empty');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(within(read).queryByRole('button')).toBeNull();
  });
});

describe("a panel's menu (D22 D)", () => {
  it('offers 看 to a reader, and 改 only while the board is built', async () => {
    const { user } = open();
    await screen.findByText('Pending', { selector: 'h3' });
    let menu = await panelMenu(user, 'Pending');
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual(['Refresh this panel']);
    await user.keyboard('{Escape}');

    await enter(user);
    menu = await panelMenu(user, 'Pending');
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      'Refresh this panel',
      'Rename',
      'Replace view…',
      'Duplicate',
      'Remove from dashboard',
    ]);
  });

  it('opens the view in the workbench only where the host has a route, under the board’s condition', async () => {
    const onOpenView = vi.fn();
    const { user } = open({
      onOpenView,
      config: {
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        filter: {
          op: 'and',
          children: [{ field: 'region', operator: 'EQ', value: 'north' }],
        },
      },
      panels: [
        panel({
          title: 'Pending',
          bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        }),
      ],
    });
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    const menu = await panelMenu(user, 'Pending');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Open in the workbench' }),
    );
    expect(onOpenView).toHaveBeenCalledWith('pending', {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'north' }],
    });
  });

  it('removes a panel after asking, says so, and keeps the keyboard on the bar', async () => {
    const { user } = open({
      panels: [
        panel({ title: 'Pending' }),
        panel({
          id: 'chart',
          title: 'Chart',
          instanceId: 'by-warehouse',
          layout: { x: 6, y: 0, w: 6, h: 4 },
        }),
      ],
    });
    await enter(user);
    const menu = await panelMenu(user, 'Chart');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Remove from dashboard' }),
    );
    const question = await screen.findByRole('alertdialog');
    expect(question.textContent).toContain('The view it shows is not deleted.');
    await user.click(
      within(question).getByRole('button', { name: 'Remove from dashboard' }),
    );
    await waitFor(() => expect(titles()).toEqual(['Pending']));
    expect(screen.getByText('Removed “Chart”')).toBeTruthy();
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('Editing'),
    );
  });

  it('keeps the panel when the question is answered no', async () => {
    const { user } = open();
    await enter(user);
    const menu = await panelMenu(user, 'Pending');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Remove from dashboard' }),
    );
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Keep it',
      }),
    );
    expect(titles()).toEqual(['Pending']);
  });

  it('duplicates a panel, and replaces the view one shows', async () => {
    const { user } = open();
    await enter(user);
    let menu = await panelMenu(user, 'Pending');
    await user.click(within(menu).getByRole('menuitem', { name: 'Duplicate' }));
    await waitFor(() => expect(titles()).toEqual(['Pending', 'Pending']));

    await user.click(
      screen.getAllByRole('button', { name: 'Actions for “Pending”' })[0],
    );
    menu = await screen.findByRole('menu');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Replace view…' }),
    );
    const picker = await screen.findByRole('dialog', {
      name: 'Replace the view in “Pending”',
    });
    await user.click(
      await within(picker).findByRole('button', { name: /By warehouse/ }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('moves a panel to another tab where the board has tabs', async () => {
    const { engine, user } = open({
      config: {
        tabs: [
          { id: 't1', title: 'Today' },
          { id: 't2', title: '' },
        ],
      },
      panels: [panel({ title: 'Pending', tab: 't1' })],
    });
    await enter(user);
    const menu = await panelMenu(user, 'Pending');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Move to tab' }),
    );
    // A tab without a name is called by its place.
    await screen.findByRole('menuitem', { name: 'Tab 2' });
    await user.keyboard('{ArrowRight}');
    await waitFor(() =>
      expect(document.activeElement?.textContent).toBe('Tab 2'),
    );
    await user.keyboard('{Enter}');
    await waitFor(() =>
      expect(
        (boardOf(engine).getSnapshot().draft as DashboardViewConfig).panels[0]
          .tab,
      ).toBe('t2'),
    );
  });

  it('shows the extensions’ entries only where they are provided', async () => {
    const onAddOwnedAnalysis = vi.fn();
    const onEditPresentation = vi.fn();
    const onSaveOwnedAsView = vi.fn();
    const { user } = open({
      extensions: {
        onAddOwnedAnalysis,
        onEditPresentation,
        onSaveOwnedAsView,
        tabBar: <nav aria-label="Tabs">tab bar</nav>,
      },
      panels: [
        panel({ title: 'Pending' }),
        {
          id: 'own',
          kind: 'view',
          title: 'Own',
          owned: { definitionId: 'orders', config: analysisConfig() },
          bindings: [],
          layout: { x: 6, y: 0, w: 6, h: 4 },
        } as DashboardPanel,
      ],
    });
    expect(
      await screen.findByRole('navigation', { name: 'Tabs' }),
    ).toBeTruthy();
    await enter(user);
    await add(user, 'New analysis…');
    expect(onAddOwnedAnalysis).toHaveBeenCalledWith({ fromRow: 0 });

    // A record panel has no look to change here, nor an analysis to save.
    let menu = await panelMenu(user, 'Pending');
    expect(
      within(menu).queryByRole('menuitem', { name: 'Save as a view…' }),
    ).toBeNull();
    expect(
      within(menu).queryByRole('menuitem', {
        name: 'Change how it looks here…',
      }),
    ).toBeNull();
    await user.keyboard('{Escape}');

    menu = await panelMenu(user, 'Own');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Change how it looks here…' }),
    );
    expect(onEditPresentation).toHaveBeenCalledWith('own');

    menu = await panelMenu(user, 'Own');
    await user.click(
      within(menu).getByRole('menuitem', { name: 'Save as a view…' }),
    );
    expect(onSaveOwnedAsView).toHaveBeenCalledWith('own');
  });
});

describe('a panel that is out, while the board is built', () => {
  it('offers the way out as buttons rather than a person to ask', async () => {
    const { user } = open({
      panels: [panel({ title: 'Gone', instanceId: 'vanished' })],
    });
    const out = await waitFor(() => {
      const found = slot('panel-unavailable');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(out.textContent).toContain('Ask');
    expect(within(out).queryByRole('button')).toBeNull();

    await enter(user);
    const buttons = within(slot('panel-unavailable')!)
      .getAllByRole('button')
      .map(button => button.textContent);
    expect(buttons).toEqual(['Replace view…', 'Remove from dashboard']);
    expect(slot('panel-unavailable')!.textContent).not.toContain('Ask');
  });
});

describe('a chart finding in a panel names columns', () => {
  /**
   * A bar chart over three dimensions is drawn as its table, and the panel
   * says so as the workbench does — with the chart's name and the reason,
   * never an alias (`analysisIssueNamer`). It used to say nothing at all: only
   * a child's warnings reached its panel, and this one is a note.
   */
  it('says why a chart shows as its table, in the words of the picker', async () => {
    const { TERMS, DATE_HISTOGRAM } = AggregationGroupType;
    const base = ordersDefinition();
    const definition = ordersDefinition({
      fields: [
        ...base.fields,
        { name: 'createdAt', label: 'Created', kind: 'datetime' },
      ],
      analysis: {
        count: true,
        fields: [
          { field: 'warehouse', groups: [TERMS], functions: [] },
          { field: 'status', groups: [TERMS], functions: [] },
          {
            field: 'createdAt',
            groups: [DATE_HISTOGRAM],
            functions: [],
            dateUnits: [AggregationDateUnit.DAY],
          },
        ],
      },
    });
    const chart: ViewInstance = {
      ...byWarehouse,
      id: 'three',
      config: analysisConfig({
        groups: [
          { alias: 'g_wh', field: 'warehouse', type: 'TERMS' },
          { alias: 'g_st', field: 'status', type: 'TERMS' },
          {
            alias: 'g_day',
            field: 'createdAt',
            type: 'DATE_HISTOGRAM',
            unit: 'DAY',
          },
        ],
        metrics: [{ alias: 'm_n', type: 'COUNT' }],
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: {
            x: 'g_wh',
            splitBy: 'g_st',
            series: [{ metric: 'm_n' }],
          },
        },
      }),
    };
    const engine = new ViewEngine({
      definitions: [definition, overviewDefinition()],
      store: new MemoryViewStore({
        instances: [
          chart,
          {
            id: 'overview-1',
            definitionId: 'overview',
            title: 'Operations',
            scope: 'personal',
            revision: '1',
            config: dashboardConfig({
              panels: [panel({ title: 'Three ways', instanceId: 'three' })],
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    render(
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId="overview-1"
      />,
    );
    const marker = await waitFor(() => {
      const found = slot('panel-note');
      expect(found).not.toBeNull();
      return found!;
    });
    const said = marker.getAttribute('aria-label') ?? '';
    expect(said).toContain(
      'The bar chart cannot draw this result (At most two dimensions)',
    );
    for (const alias of ['g_wh', 'g_st', 'g_day', 'm_n'])
      expect(said).not.toContain(alias);
  });
});
