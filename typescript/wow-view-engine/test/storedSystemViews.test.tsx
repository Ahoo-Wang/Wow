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
 * Stored system views (D81): a system view the store keeps (`stored`) is
 * edited by whoever the host grants `editSystem` — absent is false — and a
 * personal or shared view is published as one by copy. Code and configured
 * system views stay read-only, and no system view moves audience.
 */

import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  coversScope,
  type DashboardPanel,
  type Issue,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPermissions,
  type ViewStore,
} from '../src/index.js';
import {
  ALLOW_ALL,
  instanceAbilities,
  mayCreate,
  PermissionGuard,
} from '../src/runtime/permissions.js';
import { abilitiesOf } from '../src/react/manager/abilities.js';
import {
  useSaveCommands,
  useViewList,
  useViewManager,
} from '../src/react/index.js';
import { formatIssue, zhCN } from '../src/ui/index.js';
import { kindIssue } from '../src/ui/kit/kinds.js';
import { DeleteDialog } from '../src/ui/manage/DeleteDialog.js';
import { ViewManager } from '../src/ui/manage/ViewManager.js';
import { ViewSurface } from '../src/ui/kit/ViewSurface.js';
import {
  SaveAsDialog,
  type SaveAsCommands,
} from '../src/ui/workbench/SaveAsDialog.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { instances, row, rows } from './fixtures/manager.js';
import { landed, tracked, withoutAudience } from './fixtures/writes.js';

afterEach(cleanup);

const CONFIGURED: ViewInstance = {
  id: 'ops-1',
  definitionId: 'orders',
  title: 'Configured',
  scope: 'system',
  revision: 'ops-1',
  config: recordConfig(),
};

const STORED: ViewInstance = {
  id: 'sys-1',
  definitionId: 'orders',
  title: 'Everyone',
  scope: 'system',
  revision: '1',
  config: recordConfig(),
  stored: true,
};

function summary(
  id: string,
  scope: ViewInstanceSummary['scope'],
  stored?: true,
): ViewInstanceSummary {
  return {
    id,
    definitionId: 'orders',
    title: id,
    scope,
    kind: 'record',
    revision: '1',
    ...(stored ? { stored } : {}),
  };
}

function permitting(overrides: Partial<ViewPermissions> = {}) {
  return (): ViewPermissions => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

const ADMIN = permitting({ editSystem: true });

function setup(permissions = permitting()) {
  const store = tracked(
    new MemoryViewStore({
      instances: [...instances(), CONFIGURED, STORED],
      permissions,
    }),
  );
  const engine = new ViewEngine({
    resources: resourcesOf([ordersDefinition()], () => testSource()),
    store,
  });
  return { engine, store };
}

describe('instanceAbilities over a system view', () => {
  const READ_ONLY = {
    save: false,
    rename: false,
    delete: false,
    changeAudience: false,
    readOnly: true,
  };

  it('writes a stored one where editSystem is granted, never moving it', () => {
    expect(
      instanceAbilities(summary('sys-1', 'system', true), ADMIN()),
    ).toEqual({
      save: true,
      rename: true,
      delete: true,
      changeAudience: false,
      readOnly: false,
    });
  });

  it('keeps the store’s answer for the instance, as for a shared view', () => {
    const narrowed = permitting({
      editSystem: true,
      instance: () => ({ save: true, rename: false, delete: false }),
    })();
    expect(
      instanceAbilities(summary('sys-1', 'system', true), narrowed),
    ).toEqual({
      save: true,
      rename: false,
      delete: false,
      changeAudience: false,
      readOnly: false,
    });
  });

  it('stays read-only when editSystem is granted but the store refuses every write', () => {
    const refused = permitting({
      editSystem: true,
      instance: () => ({ save: false, rename: false, delete: false }),
    })();
    expect(
      instanceAbilities(summary('sys-1', 'system', true), refused),
    ).toEqual(READ_ONLY);
  });

  it('reads editSystem left out as false, and ALLOW_ALL does not imply it', () => {
    const stored = summary('sys-1', 'system', true);
    expect(instanceAbilities(stored, permitting()())).toEqual(READ_ONLY);
    expect(
      instanceAbilities(stored, permitting({ editSystem: false })()),
    ).toEqual(READ_ONLY);
    expect(ALLOW_ALL.editSystem).toBeUndefined();
    expect(instanceAbilities(stored, ALLOW_ALL)).toEqual(READ_ONLY);
  });

  it('never writes a configured one, whatever is granted', () => {
    expect(instanceAbilities(summary('ops-1', 'system'), ADMIN())).toEqual(
      READ_ONLY,
    );
  });

  it('never writes a code view, even one a store calls stored', () => {
    expect(
      instanceAbilities(summary('system:orders:all', 'system', true), ADMIN()),
    ).toEqual(READ_ONLY);
  });

  it('creates in the system scope only where editSystem is granted', () => {
    expect(mayCreate(permitting()(), 'system')).toBe(false);
    expect(mayCreate(ALLOW_ALL, 'system')).toBe(false);
    expect(mayCreate(ADMIN(), 'system')).toBe(true);
    expect(mayCreate(ADMIN(), 'shared')).toBe(true);
  });

  it('keeps editSystem when the store has no changeAudience', () => {
    const store: ViewStore = {
      ...withoutAudience(new MemoryViewStore()),
      permissions: ADMIN,
    };
    expect(new PermissionGuard(store).of('orders').editSystem).toBe(true);
  });
});

describe('the engine’s commands on a system view', () => {
  it('saves, renames and deletes a stored one for an admin', async () => {
    const { engine, store } = setup(ADMIN);
    await engine.list('orders');
    const runtime = await engine.open('sys-1');
    runtime.edit({ pageSize: 50 });
    await expect(engine.save(runtime)).resolves.toMatchObject({
      id: 'sys-1',
      stored: true,
    });
    await expect(engine.rename('sys-1', 'For all')).resolves.toMatchObject({
      title: 'For all',
      scope: 'system',
    });
    await engine.delete('sys-1');
    await expect(store.get('sys-1')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });

  it('refuses every write to a stored one without editSystem, before sending', async () => {
    const { engine, store } = setup();
    await engine.list('orders');
    const rename = vi.spyOn(store, 'rename');
    await expect(engine.rename('sys-1', 'Mine now')).rejects.toMatchObject({
      issue: { code: 'view.system.read-only' },
    });
    await expect(engine.delete('sys-1')).rejects.toMatchObject({
      issue: { code: 'view.system.read-only' },
    });
    expect(rename).not.toHaveBeenCalled();
  });

  it('never moves a system view to another audience', async () => {
    const { engine } = setup(ADMIN);
    await engine.list('orders');
    await expect(
      engine.changeAudience('sys-1', 'personal'),
    ).rejects.toMatchObject({
      issue: { code: 'view.changeAudience.forbidden' },
    });
  });

  it('refuses a configured one even to an admin', async () => {
    const { engine } = setup(ADMIN);
    await engine.list('orders');
    await expect(engine.rename('ops-1', 'Mine now')).rejects.toMatchObject({
      issue: { code: 'view.system.read-only' },
    });
  });

  it('publishes a copy of a saved view, leaving the view as it was', async () => {
    const { engine, store } = setup(ADMIN);
    await engine.list('orders');
    const before = await store.get('orders-1');

    const published = await engine.publishAsSystem('orders-1');

    expect(published).toMatchObject({
      scope: 'system',
      stored: true,
      title: before.title,
      config: before.config,
    });
    expect(published.id).not.toBe('orders-1');
    expect(await store.get('orders-1')).toEqual(before);
  });

  it('refuses to publish, or to save as a system view, without editSystem', async () => {
    const { engine, store } = setup();
    await engine.list('orders');
    const create = vi.spyOn(store, 'create');
    await expect(engine.publishAsSystem('orders-1')).rejects.toMatchObject({
      issue: { code: 'view.create.forbidden' },
    });
    const runtime = await engine.open('orders-1');
    await expect(
      engine.saveAs(runtime, { title: 'For all', scope: 'system' }),
    ).rejects.toMatchObject({ issue: { code: 'view.create.forbidden' } });
    expect(create).not.toHaveBeenCalled();
  });

  it('refuses to publish a system view, whatever its source', async () => {
    const { engine } = setup(ADMIN);
    await engine.list('orders');
    for (const id of ['sys-1', 'ops-1', 'system:orders:all'])
      await expect(engine.publishAsSystem(id)).rejects.toMatchObject({
        issue: { code: 'view.system.read-only' },
      });
  });

  it('saves a copy as a system view for an admin', async () => {
    const { engine } = setup(ADMIN);
    const runtime = await engine.open('orders-1');
    await expect(
      engine.saveAs(runtime, { title: 'For all', scope: 'system' }),
    ).resolves.toMatchObject({ scope: 'system', stored: true });
  });
});

describe('MemoryViewStore’s system views', () => {
  const ctx = (requestId: string) => ({ requestId });

  it('refuses every write to a configured one', async () => {
    const store = new MemoryViewStore({ instances: [CONFIGURED] });
    await expect(
      store.rename('ops-1', 'Mine', 'ops-1', ctx('a')),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(
      store.delete('ops-1', 'ops-1', ctx('b')),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });

  it('writes a stored one, and never moves it', async () => {
    const store = new MemoryViewStore({ instances: [STORED] });
    const saved = await store.save(
      'sys-1',
      recordConfig({ pageSize: 50 }),
      '1',
      ctx('a'),
    );
    expect(saved).toMatchObject({ revision: '2', stored: true });
    await expect(
      store.changeAudience('sys-1', 'personal', '2', ctx('b')),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await store.delete('sys-1', '2', ctx('c'));
    expect(await store.list('orders')).toEqual([]);
  });
});

describe('the manager’s abilities', () => {
  const rowsOnScreen = [
    summary('p1', 'personal'),
    summary('s1', 'shared'),
    summary('ops-1', 'system'),
    summary('sys-1', 'system', true),
  ];

  it('offers publishing on personal and shared rows, and editing a stored one, to an admin', () => {
    const can = abilitiesOf(rowsOnScreen, ADMIN());
    expect(can.publishSystem).toBe(true);
    expect(can.instance('p1').publish).toBe(true);
    expect(can.instance('s1').publish).toBe(true);
    expect(can.instance('ops-1')).toEqual({
      rename: false,
      delete: false,
      changeAudience: false,
      publish: false,
    });
    expect(can.instance('sys-1')).toEqual({
      rename: true,
      delete: true,
      changeAudience: false,
      publish: false,
    });
  });

  it('offers none of it to anyone else', () => {
    const can = abilitiesOf(rowsOnScreen, permitting()());
    expect(can.publishSystem).toBe(false);
    expect(can.instance('p1').publish).toBe(false);
    expect(can.instance('sys-1')).toEqual({
      rename: false,
      delete: false,
      changeAudience: false,
      publish: false,
    });
  });
});

describe('the save commands on a stored system view', () => {
  it('saves, renames and deletes it, and copies into the system audience, for an admin', async () => {
    const { engine } = setup(ADMIN);
    await engine.list('orders');
    const runtime = await engine.open('sys-1');
    const { result } = renderHook(() => useSaveCommands(engine, runtime));
    expect(result.current.can).toMatchObject({
      save: true,
      rename: true,
      delete: true,
      createSystem: true,
    });
  });

  it('keeps it read-only for anyone else, with no system copy', async () => {
    const { engine } = setup();
    await engine.list('orders');
    const runtime = await engine.open('sys-1');
    const { result } = renderHook(() => useSaveCommands(engine, runtime));
    expect(result.current.can).toMatchObject({
      save: false,
      rename: false,
      delete: false,
      saveAs: true,
      createSystem: false,
    });
  });
});

function Manager({
  engine,
  messages,
}: {
  engine: ViewEngine;
  messages?: Record<string, string>;
}) {
  const list = useViewList(engine, 'orders');
  const manager = useViewManager(engine, 'orders', list);
  return (
    <ViewSurface messages={messages}>
      <ViewManager
        manager={manager}
        list={list}
        open
        onOpenChange={() => undefined}
      />
    </ViewSurface>
  );
}

function publishButton(title: string): HTMLElement | null {
  return row(title).querySelector('[data-publish]');
}

describe('the view manager', () => {
  it('publishes a copy for an admin, the row staying and the copy joining the system views', async () => {
    const { engine, store } = setup(ADMIN);
    render(<Manager engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(6));

    expect(publishButton('Configured')).toBeNull();
    expect(publishButton('Everyone')).toBeNull();
    const publish = within(row('Mine')).getByRole('button', {
      name: 'Publish as a system view',
    });
    fireEvent.click(publish);
    await landed(store);

    await waitFor(() => expect(rows()).toHaveLength(7));
    expect((await store.get('orders-1')).scope).toBe('personal');
    const listed = await store.list('orders');
    expect(
      listed.filter(item => item.title === 'Mine').map(item => item.scope),
    ).toEqual(['system', 'personal']);
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="view-manager-announcement"]')
          ?.textContent,
      ).toBe('Mine is published as a system view'),
    );
  });

  it('gives a stored system view rename and delete, and a lock that says so', async () => {
    const { engine } = setup(ADMIN);
    render(<Manager engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(6));

    const stored = within(row('Everyone'));
    expect(stored.getByRole('button', { name: 'Rename' })).toBeDefined();
    expect(stored.getByRole('button', { name: 'Delete' })).toBeDefined();
    expect(
      row('Everyone')
        .querySelector('[data-slot="view-system-tag"]')
        ?.hasAttribute('data-editable'),
    ).toBe(true);
    // The configured one keeps its plain lock and no actions.
    expect(
      within(row('Configured')).queryByRole('button', { name: 'Rename' }),
    ).toBeNull();
    expect(
      row('Configured')
        .querySelector('[data-slot="view-system-tag"]')
        ?.hasAttribute('data-editable'),
    ).toBe(false);
  });

  it('offers a non-admin no edit and no publish', async () => {
    const { engine } = setup();
    render(<Manager engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(6));

    expect(document.querySelector('[data-publish]')).toBeNull();
    expect(
      within(row('Everyone')).queryByRole('button', { name: 'Rename' }),
    ).toBeNull();
    expect(
      within(row('Everyone')).queryByRole('button', { name: 'Delete' }),
    ).toBeNull();
  });

  it('says 发布为系统视图 in Chinese', async () => {
    const { engine } = setup(ADMIN);
    render(<Manager engine={engine} messages={zhCN} />);
    await waitFor(() => expect(rows()).toHaveLength(6));
    expect(
      within(row('Mine')).getByRole('button', { name: '发布为系统视图' }),
    ).toBeDefined();
  });

  it('keeps a refused publish on its row, said as a permission to publish', async () => {
    const memory = new MemoryViewStore({
      instances: [...instances(), STORED],
      permissions: ADMIN,
    });
    const store: ViewStore = memory;
    vi.spyOn(memory, 'create').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'The gateway said no'),
    );
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
    });
    render(<Manager engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(5));
    await act(async () => {
      fireEvent.click(
        within(row('Mine')).getByRole('button', {
          name: 'Publish as a system view',
        }),
      );
    });
    // Said under the row that asked, and nothing was added to the list.
    await waitFor(() =>
      expect(row('Mine').textContent).toContain(
        'You may not publish system views; ask an administrator.',
      ),
    );
    expect(rows()).toHaveLength(5);
  });
});

describe('the dialogs', () => {
  function SaveAs({ can }: { can: SaveAsCommands['can'] }) {
    const [open, setOpen] = useState(true);
    return (
      <ViewSurface>
        <SaveAsDialog
          open={open}
          onOpenChange={setOpen}
          title="Mine"
          commands={{
            can,
            state: { pending: false, error: null },
            saveAs: () => Promise.resolve(null),
          }}
        />
      </ViewSurface>
    );
  }

  it('offers the system audience in 另存为 only where it is granted', () => {
    const { unmount } = render(
      <SaveAs
        can={{ createPersonal: true, createShared: true, createSystem: true }}
      />,
    );
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    expect(screen.getByText('Everyone, as a system view')).toBeDefined();
    unmount();

    render(<SaveAs can={{ createPersonal: true, createShared: true }} />);
    expect(screen.getAllByRole('radio')).toHaveLength(2);
    expect(screen.queryByText('Everyone, as a system view')).toBeNull();
  });

  it('says a deleted system view disappears for everyone', () => {
    render(
      <ViewSurface>
        <DeleteDialog
          open
          onOpenChange={() => undefined}
          item={summary('sys-1', 'system', true)}
          dirty={false}
          onConfirm={() => undefined}
        />
      </ViewSurface>,
    );
    expect(
      screen.getByText(
        /Once it is deleted, no one sees this system view any more\./,
      ),
    ).toBeDefined();
    expect(screen.queryByText(/Everyone who uses it loses it/)).toBeNull();
  });

  it('says a system dashboard in its own words, in both catalogues', () => {
    const board = {
      ...summary('sys-2', 'system', true),
      kind: 'dashboard' as const,
    };
    render(
      <ViewSurface messages={zhCN}>
        <DeleteDialog
          open
          onOpenChange={() => undefined}
          item={board}
          dirty={false}
          onConfirm={() => undefined}
        />
      </ViewSurface>,
    );
    expect(
      screen.getByText(/删除后，所有人都不再看到这个系统仪表盘。/),
    ).toBeDefined();
  });

  it('draws publishing with its own icon, not the lock a system view wears', async () => {
    const { engine } = setup(ADMIN);
    render(<Manager engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(6));
    // Compared by the drawing itself: the lock says "a system view", and a
    // lock on a personal row would read as "locked".
    const drawing = (svg: Element | null | undefined) => svg?.innerHTML ?? '';
    const lock = row('Everyone').querySelector(
      '[data-slot="view-system-tag"] svg',
    );
    expect(drawing(lock)).not.toBe('');
    expect(drawing(publishButton('Mine')?.querySelector('svg'))).not.toBe(
      drawing(lock),
    );
  });
});

describe('a board headed for the system views', () => {
  const panel = (id: string, title: string | undefined, instanceId: string) =>
    ({
      id,
      kind: 'view',
      ...(title === undefined ? {} : { title }),
      instanceId,
      bindings: [],
      layout: { x: 0, y: 0, w: 12, h: 4 },
    }) as DashboardPanel;

  function boards(panels: DashboardPanel[]) {
    const board: ViewInstance = {
      id: 'board-1',
      definitionId: 'overview',
      title: 'Ops',
      scope: 'shared',
      revision: '1',
      config: dashboardConfig({ panels }),
    };
    const store = new MemoryViewStore({
      instances: [...instances(), STORED, board],
      permissions: ADMIN,
    });
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition(), overviewDefinition()], () =>
        testSource(),
      ),
      store,
    });
    return { engine, store };
  }

  it('covers a system view alone: a shared view is one tenant’s', () => {
    expect(coversScope('system', 'system')).toBe(true);
    expect(coversScope('system', 'shared')).toBe(false);
    expect(coversScope('system', 'personal')).toBe(false);
    expect(coversScope('shared', 'system')).toBe(true);
  });

  it('is refused when a panel shows a view that is not a system view, naming the panels', async () => {
    const { engine, store } = boards([
      panel('p1', 'Mine panel', 'orders-1'),
      panel('p2', undefined, 'orders-3'),
      panel('p3', 'Everyone panel', 'sys-1'),
    ]);
    const create = vi.spyOn(store, 'create');
    const refusal = await engine
      .publishAsSystem('board-1')
      .catch((error: unknown) => error);
    expect(refusal).toMatchObject({
      issue: {
        code: 'dashboard.system.non-system-panels',
        params: { count: 2 },
      },
    });
    // The untitled panel is named by the view it shows.
    expect(
      formatIssue(zhCN, (refusal as { issue: Issue }).issue, 'zh-CN'),
    ).toBe(
      '这些面板引用了非系统视图，先把它们发布为系统视图：Mine panel和Ours',
    );
    expect(create).not.toHaveBeenCalled();

    const runtime = await engine.open('board-1');
    await expect(
      engine.saveAs(runtime, { title: 'For all', scope: 'system' }),
    ).rejects.toMatchObject({
      issue: { code: 'dashboard.system.non-system-panels' },
    });
    // Shared stays allowed, as before.
    await expect(
      engine.saveAs(runtime, { title: 'For the team', scope: 'shared' }),
    ).resolves.toMatchObject({ scope: 'shared' });
  });

  it('passes with system views and views of its own', async () => {
    const { engine } = boards([
      panel('p1', 'Everyone panel', 'sys-1'),
      panel('p2', 'Code panel', 'system:orders:all'),
      {
        id: 'own',
        kind: 'view',
        title: 'Own',
        owned: { definitionId: 'orders', config: analysisConfig() },
        bindings: [],
        layout: { x: 0, y: 4, w: 12, h: 4 },
      } as DashboardPanel,
    ]);
    await expect(engine.publishAsSystem('board-1')).resolves.toMatchObject({
      scope: 'system',
      stored: true,
    });
  });

  it('says the gateway’s refusal of a system board in a board’s words', () => {
    const forbidden = {
      code: 'view.publish.forbidden',
      path: [],
      severity: 'error' as const,
    };
    expect(formatIssue(zhCN, kindIssue(forbidden, 'dashboard'))).toBe(
      '你没有发布系统仪表盘的权限，请联系管理员。',
    );
    expect(formatIssue(zhCN, forbidden)).toBe(
      '你没有发布系统视图的权限，请联系管理员。',
    );
  });
});
