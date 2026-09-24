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
 * The two things a panel's 「⋯」 does that reach past the board: 导出数据…,
 * the workbench's export window over a record panel's rows (D14, D22 运维),
 * and 复制为共享视图并替换…, a shared board's panel on someone's personal
 * view copied for its readers (D22 B). The rest of the menu is
 * `test/dashboardBuilding.test.tsx`.
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
  ViewStoreError,
  type DashboardPanel,
  type DashboardRuntime,
  type DashboardViewConfig,
  type ViewInstance,
  type ViewScope,
} from '../src/index.js';
import { DashboardWorkbench } from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';
import { tracked } from './fixtures/writes.js';

afterEach(cleanup);

/** One reader's own view: on a shared board, seen by that reader alone. */
const mine: ViewInstance = {
  ...pending,
  id: 'mine',
  title: 'My orders',
  scope: 'personal',
};

const byWarehouse: ViewInstance = {
  id: 'by-warehouse',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'shared',
  revision: 'r1',
  config: analysisConfig(),
};

interface Setup {
  scope?: ViewScope;
  panels?: DashboardPanel[];
  config?: Partial<DashboardViewConfig>;
  /** Whether this reader may make shared views. */
  createShared?: boolean;
  features?: { export?: boolean };
  store?: (store: MemoryViewStore) => void;
}

function open({
  scope = 'shared',
  panels = [panel({ title: 'Pending' })],
  config = {},
  createShared = true,
  features,
  store: adjust,
}: Setup = {}) {
  const store = tracked(
    new MemoryViewStore({
      instances: [
        pending,
        mine,
        byWarehouse,
        {
          id: 'overview-1',
          definitionId: 'overview',
          title: 'Operations',
          scope,
          revision: '1',
          config: dashboardConfig({ panels, ...config }),
        },
      ],
      permissions: () => ({
        createPersonal: true,
        createShared,
        reorder: true,
        setDefault: true,
        instance: () => ({ save: true, rename: true, delete: true }),
      }),
    }),
  );
  adjust?.(store);
  const engine = new ViewEngine({
    definitions: [ordersDefinition(), overviewDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="overview-1"
      {...(features ? { features } : {})}
    />,
  );
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  return { engine, store, user };
}

/** The dashboard the workbench has open. */
function boardOf(engine: ViewEngine): DashboardRuntime {
  const board = engine
    .openRuntimes()
    .find(runtime => runtime.kind === 'dashboard');
  if (!board) throw new Error('no dashboard is open');
  return board as DashboardRuntime;
}

const menuButton = (name: string) =>
  screen.findByRole('button', { name: `Actions for “${name}”` });

async function items(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
): Promise<string[]> {
  await user.click(await menuButton(name));
  const menu = await screen.findByRole('menu');
  return within(menu)
    .getAllByRole('menuitem')
    .map(item => item.textContent ?? '');
}

async function enter(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  await screen.findByRole('region', { name: 'Editing' });
}

/** The browser's half of a download, which jsdom has none of. */
function stubObjectUrls() {
  const createObjectURL = vi.fn((blob: Blob) => {
    void blob;
    return 'blob:panel';
  });
  Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
  return createObjectURL;
}

describe('导出数据… on a record panel (D22 运维)', () => {
  it('opens the export window over the panel’s rows, under the board’s filters, from the keyboard', async () => {
    const createObjectURL = stubObjectUrls();
    const { user } = open({
      config: {
        fields: [{ name: 'region', label: 'Region', kind: 'string' }],
        fixed: {
          op: 'and',
          children: [{ field: 'region', operator: 'NE', value: 'north' }],
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

    // By keyboard alone: the 「⋯」 is a Tab stop, the item is in its menu.
    const trigger = await menuButton('Pending');
    trigger.focus();
    await user.keyboard('{Enter}');
    const menu = await screen.findByRole('menu');
    expect(
      within(menu).getByRole('menuitem', { name: 'Export data…' }),
    ).toBeTruthy();
    within(menu).getByRole('menuitem', { name: 'Export data…' }).focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Export' });
    // Every row: a panel's rows carry no checkboxes, so there is no 「选中」.
    expect(within(dialog).queryByRole('radio')).toBeNull();
    expect(dialog.textContent).toContain('2 records');
    // The board's filter as it reaches the panel, on the view's own field.
    expect(dialog.textContent).toMatch(/Conditions: .*north/);
    const named = /File: (Pending-\d{4}-\d{2}-\d{2}\.csv)/.exec(
      dialog.textContent ?? '',
    );
    expect(named).not.toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'Export' }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledTimes(1));
    const blob = createObjectURL.mock.calls[0][0];
    // The columns the panel draws, each value as its cell reads (`text()`
    // decodes the byte-order mark away).
    expect(await blob.text()).toBe('Order,Amount\r\no-1,10\r\no-2,20\r\n');

    await waitFor(() =>
      expect(dialog.querySelector('[data-slot="export-close"]')).not.toBeNull(),
    );
    await user.keyboard('{Enter}');
    // Back where it was asked for: the item went with the menu.
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('is on no analysis panel, and on no panel where the host turned exports off', async () => {
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
    await waitFor(() =>
      expect(screen.getAllByRole('table').length).toBeGreaterThan(0),
    );
    expect(await items(user, 'Chart')).toEqual(['Refresh this panel']);
    await user.keyboard('{Escape}');
    expect(await items(user, 'Pending')).toEqual([
      'Refresh this panel',
      'Export data…',
    ]);
    cleanup();

    const off = open({ features: { export: false } });
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(await items(off.user, 'Pending')).toEqual(['Refresh this panel']);
  });
});

describe('复制为共享视图并替换… (D22 B)', () => {
  const onMine = () =>
    panel({
      title: undefined,
      instanceId: 'mine',
      presentation: { layout: 'card' },
    } as Partial<DashboardPanel>);

  it('copies the personal view a shared board stands on, after asking, and points the panel at the copy', async () => {
    const { engine, store, user } = open({ panels: [onMine()] });
    await screen.findByText('My orders', { selector: 'h3' });
    expect(
      document.querySelector('[data-slot="panel-warning"]'),
    ).not.toBeNull();
    await enter(user);
    const trigger = await menuButton('My orders');
    expect(await items(user, 'My orders')).toContain(
      'Copy as a shared view and replace…',
    );
    await user.click(
      screen.getByRole('menuitem', {
        name: 'Copy as a shared view and replace…',
      }),
    );

    // The question before anything is written: what happens, to what.
    const dialog = await screen.findByRole('dialog', {
      name: 'Copy as a shared view and replace',
    });
    expect(dialog.textContent).toContain('“My orders” is a personal view');
    expect(dialog.textContent).toContain('The personal view stays as it is.');
    // No audience to pick: shared is the point.
    expect(within(dialog).queryByRole('radio')).toBeNull();
    const title = within(dialog).getByRole('textbox', { name: 'Title' });
    expect((title as HTMLInputElement).value).toBe('My orders');
    await user.click(
      within(dialog).getByRole('button', { name: 'Copy and replace' }),
    );

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const copies = (await store.list('orders')).filter(
      view => view.title === 'My orders' && view.scope === 'shared',
    );
    expect(copies).toHaveLength(1);
    const copy = await store.get(copies[0].id);
    expect(copy.config).toEqual(mine.config);
    // The personal view is left as it was.
    expect((await store.get('mine')).scope).toBe('personal');
    // The panel shows the copy, keeping its look; the board is not written
    // until 保存.
    const draft = boardOf(engine).getSnapshot().draft.panels[0];
    expect(draft).toMatchObject({
      instanceId: copies[0].id,
      presentation: { layout: 'card' },
    });
    expect(
      screen.getByText(
        '“My orders” was copied as a shared view; this panel shows it now',
      ),
    ).toBeTruthy();
    await waitFor(() =>
      expect(document.querySelector('[data-slot="panel-warning"]')).toBeNull(),
    );
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('says why a copy was refused and keeps the question open', async () => {
    const { user } = open({
      panels: [onMine()],
      store: store => {
        store.create = () =>
          Promise.reject(new ViewStoreError('FORBIDDEN', 'no'));
      },
    });
    await enter(user);
    await items(user, 'My orders');
    await user.click(
      screen.getByRole('menuitem', {
        name: 'Copy as a shared view and replace…',
      }),
    );
    const dialog = await screen.findByRole('dialog');
    await user.click(
      within(dialog).getByRole('button', { name: 'Copy and replace' }),
    );
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('dialog')).toBe(dialog);
  });

  it('is offered only on a shared board, over a personal view, to whoever may make a shared one', async () => {
    // Not while reading.
    let setup = open({ panels: [onMine()] });
    await screen.findByText('My orders', { selector: 'h3' });
    expect(await items(setup.user, 'My orders')).not.toContain(
      'Copy as a shared view and replace…',
    );
    cleanup();

    // Not without the right to make a shared view.
    setup = open({ panels: [onMine()], createShared: false });
    await enter(setup.user);
    expect(await items(setup.user, 'My orders')).not.toContain(
      'Copy as a shared view and replace…',
    );
    cleanup();

    // Not on a personal board, where a personal view is read by all it has.
    setup = open({ panels: [onMine()], scope: 'personal' });
    await enter(setup.user);
    expect(await items(setup.user, 'My orders')).not.toContain(
      'Copy as a shared view and replace…',
    );
    cleanup();

    // Not over a view already shared.
    setup = open();
    await enter(setup.user);
    expect(await items(setup.user, 'Pending')).not.toContain(
      'Copy as a shared view and replace…',
    );
  });
});
