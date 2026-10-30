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
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type DashboardViewConfig,
  type ViewInstance,
} from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import {
  dashboardConfig,
  mine,
  ordersDefinition,
  overviewDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { panel, pending } from './fixtures/dashboard.js';

afterEach(cleanup);

async function open() {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store,
  });
  const user = userEvent.setup();
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
    />,
  );
  await screen.findAllByRole('row');
  return { store, user };
}

const more = () => screen.getByRole('button', { name: 'More view actions' });

describe('the title bar’s more menu (R2-98)', () => {
  it('moves the open view to the other audience, and says so', async () => {
    const { store, user } = await open();

    await user.click(more());
    await user.click(
      await screen.findByRole('menuitem', { name: 'Make shared' }),
    );

    await waitFor(async () =>
      expect((await store.get('orders-1')).scope).toBe('shared'),
    );
    expect(await screen.findByText('Mine is now shared')).toBeTruthy();
    // And back, from the same menu, now named the other way.
    await user.click(more());
    await user.click(
      await screen.findByRole('menuitem', { name: 'Make personal' }),
    );
    await waitFor(async () =>
      expect((await store.get('orders-1')).scope).toBe('personal'),
    );
  });

  it('offers no link where no router puts the view in the address', async () => {
    const { user } = await open();
    await user.click(more());
    await screen.findByRole('menuitem', { name: 'Make shared' });
    expect(screen.queryByRole('menuitem', { name: 'Copy link' })).toBeNull();
  });
});

describe('放进仪表盘… (R2-98)', () => {
  const board = (panels: number): ViewInstance => ({
    id: 'board-1',
    definitionId: 'overview',
    title: 'Daily',
    scope: 'personal',
    revision: 'r1',
    config: dashboardConfig({
      panels: Array.from({ length: panels }, (_, at) =>
        panel({ id: `p${at}`, layout: { x: 0, y: at * 4, w: 6, h: 4 } }),
      ),
    }),
  });

  async function openWith(instances: ViewInstance[], maxDashboardPanels = 40) {
    const store = new MemoryViewStore({
      instances: [mine, pending, ...instances],
    });
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
        testSource(),
      ),
      store,
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxDashboardPanels },
    });
    const user = userEvent.setup();
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await screen.findAllByRole('row');
    await user.click(more());
    await user.click(
      await screen.findByRole('menuitem', { name: 'Add to dashboard…' }),
    );
    return { store, user, dialog: await screen.findByRole('dialog') };
  }

  it('puts the open view on the board picked, and says so', async () => {
    const { store, user, dialog } = await openWith([board(1)]);
    await user.click(
      await within(dialog).findByRole('button', { name: 'Daily' }),
    );

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const saved = (await store.get('board-1')).config as DashboardViewConfig;
    expect(saved.panels[saved.panels.length - 1]).toMatchObject({
      instanceId: 'orders-1',
    });
    expect(await screen.findByText('Added to “Daily”')).toBeTruthy();
  });

  it('says why a board would not take it, and stays open', async () => {
    const { user, dialog } = await openWith([board(1)], 1);
    await user.click(
      await within(dialog).findByRole('button', { name: 'Daily' }),
    );
    expect((await within(dialog).findByRole('alert')).textContent).toMatch(
      /already holds 1 panels/,
    );
    expect(screen.getByRole('dialog')).toBe(dialog);
  });

  it('says so where there is no board to change', async () => {
    const { dialog } = await openWith([]);
    expect(
      await within(dialog).findByText('There is no dashboard you may change.'),
    ).toBeTruthy();
  });
});

describe('views kept in this browser alone (`ViewStore.local`)', () => {
  /** A permission answer that allows everything but making a board. */
  const noBoards = (definitionId: string) => ({
    createPersonal: definitionId !== 'overview',
    createShared: definitionId !== 'overview',
    reorder: true,
    setDefault: true,
    instance: () => ({
      save: true,
      rename: true,
      delete: true,
      changeAudience: true,
    }),
  });

  async function openLocal() {
    const store = new MemoryViewStore({
      instances: [mine],
      local: true,
      permissions: noBoards,
    });
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
        testSource(),
      ),
      store,
    });
    const user = userEvent.setup();
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await screen.findAllByRole('row');
    return { engine, user };
  }

  it('shares nothing, whatever the permissions answer', async () => {
    const { engine } = await openLocal();
    const permissions = engine.permissions('orders');
    expect(permissions.local).toBe(true);
    expect(permissions.createShared).toBe(false);
    expect(permissions.instance('orders-1').changeAudience).toBe(false);
  });

  it('saves a copy without asking who it is for, and says it stays here', async () => {
    const { user } = await openLocal();
    await user.click(more());
    // No move to the other audience, and no board to put it on: the only
    // board is one the reader cannot make.
    await screen.findByRole('menuitem', { name: 'Save as' });
    expect(screen.queryByRole('menuitem', { name: 'Make shared' })).toBeNull();
    expect(
      screen.queryByRole('menuitem', { name: 'Add to dashboard…' }),
    ).toBeNull();
    await user.click(screen.getByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(
        'Saved in this browser: only you see it, and only here.',
      ),
    ).toBeTruthy();
    expect(within(dialog).queryByRole('radiogroup')).toBeNull();
  });
});
