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
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type ViewInstance,
  type ViewPermissions,
} from '../src/index.js';
import {
  useViewList,
  useViewManager,
  type ViewListState,
  type ViewManagerController,
} from '../src/react/index.js';
import { ViewList } from '../src/ui/ViewList.js';
import { ViewManager } from '../src/ui/ViewManager.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import {
  deferred,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** Two personal views, alongside the system view the definition declares. */
function instances(): ViewInstance[] {
  return [
    {
      id: 'orders-1',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'orders-2',
      definitionId: 'orders',
      title: 'Yours',
      scope: 'personal',
      revision: '1',
      config: recordConfig(),
    },
    {
      id: 'orders-3',
      definitionId: 'orders',
      title: 'Ours',
      scope: 'shared',
      revision: '1',
      config: recordConfig(),
    },
  ];
}

function permitting(
  overrides: Partial<ViewPermissions> = {},
): () => ViewPermissions {
  return () => ({
    createPersonal: true,
    createShared: true,
    reorder: true,
    setDefault: true,
    instance: () => ({ save: true, rename: true, delete: true }),
    ...overrides,
  });
}

function setup(permissions = permitting()) {
  const store = new MemoryViewStore({ instances: instances(), permissions });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => testSource(),
  });
  return { engine, store };
}

/** The sidebar with its manager, which is how a user reaches the dialog. */
function Sidebar({
  engine,
  withManager = true,
}: {
  engine: ViewEngine;
  withManager?: boolean;
}) {
  const list = useViewList(engine, 'orders');
  const manager = useViewManager(engine, 'orders', list);
  return (
    <ViewSurface>
      <ViewList
        list={list}
        currentId={null}
        onOpen={() => undefined}
        manager={withManager ? manager : undefined}
      />
    </ViewSurface>
  );
}

/** The dialog on its own, so the open view's state can be handed to it. */
function Standalone({
  engine,
  openDirtyId,
  hold,
}: {
  engine: ViewEngine;
  openDirtyId?: string | null;
  /** Lets a test reach the controller the dialog is driving. */
  hold?(parts: { list: ViewListState; manager: ViewManagerController }): void;
}) {
  const list = useViewList(engine, 'orders');
  const manager = useViewManager(engine, 'orders', list);
  hold?.({ list, manager });
  return (
    <ViewSurface>
      <ViewManager
        manager={manager}
        list={list}
        open
        onOpenChange={() => undefined}
        openDirtyId={openDirtyId}
      />
    </ViewSurface>
  );
}

/** Row titles in the order the dialog draws them. */
function rows(): string[] {
  return Array.from(
    document.querySelectorAll('[data-slot="view-manager-row"]'),
  ).map(row => row.textContent ?? '');
}

/**
 * One row by the title it shows. A row being renamed shows it in an input
 * rather than as text, and it is the same row throughout.
 */
function row(title: string): HTMLElement {
  const found = Array.from(
    document.querySelectorAll('[data-slot="view-manager-row"]'),
  ).find(
    candidate =>
      candidate.textContent?.includes(title) ||
      Array.from(candidate.querySelectorAll('input')).some(field =>
        field.value.includes(title),
      ),
  );
  if (!found) throw new Error(`no row for ${title}`);
  return found as HTMLElement;
}

/** The manager opened from the sidebar, settled. */
async function manage(engine: ViewEngine) {
  render(<Sidebar engine={engine} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Manage views' }));
  await screen.findByRole('dialog');
  await waitFor(() => expect(rows()).toHaveLength(4));
}

/** The manager on its own, settled. */
async function standalone(engine: ViewEngine, openDirtyId?: string | null) {
  render(<Standalone engine={engine} openDirtyId={openDirtyId} />);
  await waitFor(() => expect(rows()).toHaveLength(4));
}

describe('the manage button on the view list', () => {
  it('is there only when a manager was given', async () => {
    const { engine } = setup();
    render(<Sidebar engine={engine} withManager={false} />);
    await screen.findByRole('button', { name: /Mine/ });

    expect(screen.queryByRole('button', { name: 'Manage views' })).toBeNull();
  });

  it('opens the manager, grouped as the list is', async () => {
    const { engine } = setup();
    await manage(engine);

    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('Personal');
    expect(dialog.textContent).toContain('Shared');
    // A system view is a shared view, tagged with where it came from.
    expect(row('All orders').textContent).toContain('system');
  });
});

describe('ViewManager rows', () => {
  it('renames a view in place', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText('Title'), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(
      within(row('Renamed')).getByRole('button', { name: 'Save the title' }),
    );

    await waitFor(async () =>
      expect((await store.get('orders-1')).title).toBe('Renamed'),
    );
  });

  it('keeps the title when the rename is called off', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText('Title'), {
      target: { value: 'Never mind' },
    });
    fireEvent.click(
      within(row('Never mind')).getByRole('button', {
        name: 'Keep the title',
      }),
    );

    expect(row('Mine')).toBeDefined();
    expect((await store.get('orders-1')).title).toBe('Mine');
  });

  it('deletes after a confirmation', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const confirm = await screen.findByText('Delete this view?');
    const dialog = confirm.closest('[role="dialog"]') as HTMLElement;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.id)).not.toContain(
        'orders-2',
      ),
    );
  });

  /**
   * The consequences are composed, not written out four times: the base
   * sentence always, and the two that depend on this view only when they do.
   */
  it('says only the consequences that apply', async () => {
    const { engine } = setup();
    await standalone(engine, 'orders-1');

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Delete' }),
    );
    const mine = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    // The open view, with edits: personal, so no word about other people.
    expect(mine.textContent).toContain('Only the view is removed');
    expect(mine.textContent).toContain('Unsaved changes go with it.');
    expect(mine.textContent).not.toContain('Everyone who uses it');
  });

  it("warns that a shared view is somebody else's too", async () => {
    const { engine } = setup();
    await standalone(engine, 'orders-1');

    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: 'Delete' }),
    );
    const shared = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    expect(shared.textContent).toContain('Everyone who uses it loses it.');
    // Not the open view, so nothing unsaved goes with it.
    expect(shared.textContent).not.toContain('Unsaved changes');
  });

  it('moves a view one step and keeps the ends put', async () => {
    const { engine } = setup();
    await manage(engine);

    // The system view is first among the shared ones, so it is the one with
    // nowhere to go up.
    expect(
      within(row('All orders'))
        .getByRole('button', { name: 'Move up' })
        .hasAttribute('disabled'),
    ).toBe(true);

    expect(rows()[0]).toContain('Mine');
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Move down' }),
    );

    await waitFor(() => expect(rows()[0]).toContain('Yours'));
  });

  /**
   * The dialog draws two groups, personal above shared, and the arrows move
   * within the one the row is drawn in. Across the boundary there is nothing
   * to see: the order would be stored again, the revision spent, and the
   * rows would sit exactly where they were.
   */
  it('moves within the audience group the row is drawn in', async () => {
    const { engine, store } = setup();
    await manage(engine);

    // Personal first, shared after, whatever the stored order is.
    expect(rows().map(text => text.replace(/\s+/g, ' ').trim())).toEqual([
      expect.stringContaining('Mine'),
      expect.stringContaining('Yours'),
      expect.stringContaining('All orders'),
      expect.stringContaining('Ours'),
    ]);

    const ends = [
      ['Mine', 'Move up'],
      ['Yours', 'Move down'],
      ['All orders', 'Move up'],
      ['Ours', 'Move down'],
    ] as const;
    for (const [title, arrow] of ends)
      expect(
        within(row(title)).getByRole('button', { name: arrow }),
      ).toHaveProperty('disabled', true);

    // The shared view above it is the system one, three rows away in the
    // stored order and the row above it on screen.
    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: 'Move up' }),
    );

    await waitFor(async () =>
      expect((await store.getPreferences('orders')).order[0]).toBe('orders-3'),
    );
    await waitFor(() => expect(rows()[2]).toContain('Ours'));
    // The personal group never moved.
    expect(rows()[0]).toContain('Mine');
    expect(rows()[1]).toContain('Yours');
  });

  it('chooses and unchooses the view that opens first', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Yours')).getByRole('button', {
        name: 'Open this one first',
      }),
    );
    await waitFor(async () =>
      expect((await store.getPreferences('orders')).defaultInstanceId).toBe(
        'orders-2',
      ),
    );
    await waitFor(() => expect(row('Yours').textContent).toContain('Default'));

    fireEvent.click(
      within(row('Yours')).getByRole('button', {
        name: 'Stop opening this one first',
      }),
    );
    await waitFor(async () =>
      expect(
        (await store.getPreferences('orders')).defaultInstanceId,
      ).toBeNull(),
    );
  });

  it('offers no write a system view could not take', async () => {
    const { engine } = setup();
    await manage(engine);

    const system = within(row('All orders'));
    expect(system.queryByRole('button', { name: 'Rename' })).toBeNull();
    expect(system.queryByRole('button', { name: 'Delete' })).toBeNull();
    // Ordering and the default are the user's own preference, so they stay.
    expect(system.getByRole('button', { name: 'Move down' })).toBeDefined();
  });

  it('leaves out the buttons a permission does not cover', async () => {
    const { engine } = setup(
      permitting({
        reorder: false,
        setDefault: false,
        instance: () => ({ save: true, rename: false, delete: true }),
      }),
    );
    await manage(engine);

    const mine = within(row('Mine'));
    expect(mine.queryByRole('button', { name: 'Move up' })).toBeNull();
    expect(
      mine.queryByRole('button', { name: 'Open this one first' }),
    ).toBeNull();
    expect(mine.queryByRole('button', { name: 'Rename' })).toBeNull();
    expect(mine.getByRole('button', { name: 'Delete' })).toBeDefined();
  });
});

describe('ViewManager outcomes', () => {
  it('offers a way out of a conflict under the row that caused it', async () => {
    const { engine, store } = setup();
    await manage(engine);
    // Somebody else renamed it after this list was read.
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText('Title'), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(
      within(row('Renamed')).getByRole('button', { name: 'Save the title' }),
    );

    const conflicted = await screen.findByText(
      'Someone else saved this view first',
    );
    const line = conflicted.closest('[data-slot="view-manager-row"]');
    fireEvent.click(
      within(line as HTMLElement).getByRole('button', { name: 'Keep mine' }),
    );

    await waitFor(async () =>
      expect((await store.get('orders-1')).title).toBe('Renamed'),
    );
  });

  it('reloads the list rather than replaying a preference conflict', async () => {
    const { engine, store } = setup();
    await manage(engine);
    // The stored preferences moved on, so the revision this order carries is
    // a revision behind.
    await store.setPreferences(
      'orders',
      { order: ['orders-2'], defaultInstanceId: null, revision: '0' },
      { requestId: 'other' },
    );

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Move down' }),
    );

    await screen.findByText('Someone else saved this view first');
    fireEvent.click(screen.getByRole('button', { name: 'Reload list' }));
    // §7.3: the intent is kept and put to the user again rather than replayed
    // behind their back, so the line stays until they act on it.
    await waitFor(() => expect(rows().length).toBeGreaterThan(0));
  });

  /**
   * The second half of §7.3. Once the list has been reloaded the engine has
   * settled the conflict, so the generic "Keep mine" addresses a write that
   * no longer exists and answers nothing — the button was dead. What is
   * offered instead is the user's own intent, put again at the revision the
   * reload brought in.
   */
  it('offers the kept intent again after a preference reload', async () => {
    const { engine, store } = setup();
    await manage(engine);
    await store.setPreferences(
      'orders',
      { order: ['orders-2'], defaultInstanceId: null, revision: '0' },
      { requestId: 'other' },
    );

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Move down' }),
    );
    await screen.findByText('Someone else saved this view first');
    fireEvent.click(screen.getByRole('button', { name: 'Reload list' }));

    const again = await screen.findByRole('button', { name: 'Apply again' });
    expect(screen.queryByRole('button', { name: 'Keep mine' })).toBeNull();
    fireEvent.click(again);

    // It lands this time, and the line goes with it.
    await waitFor(async () =>
      expect((await store.getPreferences('orders')).order).toEqual([
        'system:orders:all',
        'orders-2',
        'orders-1',
        'orders-3',
      ]),
    );
    await waitFor(() =>
      expect(
        screen.queryByText('Someone else saved this view first'),
      ).toBeNull(),
    );
  });

  /**
   * §7.4: a delete that conflicts has to be confirmed again. The first
   * confirmation was about the view as the list had it; what the conflict
   * reports is a view that has changed since — it may have become shared,
   * and deleting it now costs other people theirs.
   */
  it('asks again before overwriting a delete that conflicted', async () => {
    const { engine, store } = setup();
    await manage(engine);
    // Somebody else changed it after this list was read, and made it shared.
    await store.save('orders-2', recordConfig({ pageSize: 30 }), '1', {
      requestId: 'other',
    });

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const first = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    fireEvent.click(within(first).getByRole('button', { name: 'Delete' }));

    await screen.findByText('Someone else saved this view first');
    fireEvent.click(screen.getByRole('button', { name: 'Keep mine' }));

    // Not deleted yet: the destructive answer is the dialog's, not the line's.
    const second = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    expect((await store.list('orders')).map(item => item.id)).toContain(
      'orders-2',
    );

    fireEvent.click(within(second).getByRole('button', { name: 'Delete' }));
    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.id)).not.toContain(
        'orders-2',
      ),
    );
  });

  it('retries a result that never came back', async () => {
    const { engine, store } = setup();
    await manage(engine);
    vi.spyOn(store, 'delete').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    fireEvent.click(
      within(row('Yours')).getByRole('button', { name: 'Delete' }),
    );
    const confirm = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));

    await screen.findByText('The result never came back');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.id)).not.toContain(
        'orders-2',
      ),
    );
  });

  it('lets an unknown result be left alone', async () => {
    const { engine, store } = setup();
    await manage(engine);
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Save the title' }),
    );

    await screen.findByText('The result never came back');
    fireEvent.click(screen.getByRole('button', { name: 'Abandon' }));

    await waitFor(() =>
      expect(screen.queryByText('The result never came back')).toBeNull(),
    );
  });

  /**
   * A refusal has nothing to retry, but the engine is still holding the
   * write it refused: the line would sit over the row for the rest of the
   * session, and the next command on that key would take its slot — handle
   * and all — leaving that write unreachable from the screen.
   */
  it('lets a refusal be dismissed once it has been read', async () => {
    const { engine, store } = setup();
    await manage(engine);
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Save the title' }),
    );
    await screen.findByText(/You may not write to this view/);
    expect(engine.pendingWrites().size).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    await waitFor(() =>
      expect(screen.queryByText(/You may not write to this view/)).toBeNull(),
    );
    expect(engine.pendingWrites().size).toBe(0);
  });

  /**
   * Commands are serialized, so any write in flight is one a recovery would
   * queue behind — and pressed twice, a recovery addresses the same handle
   * twice. The engine refuses the second with `view.write.in-flight`, which
   * the row would report as a failure of the click rather than of nothing.
   */
  it('takes one recovery at a time', async () => {
    const { engine, store } = setup();
    await manage(engine);
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Save the title' }),
    );
    await screen.findByText('The result never came back');

    const held = deferred<ViewInstance>();
    vi.spyOn(store, 'rename').mockReturnValueOnce(held.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Retry' }).hasAttribute('disabled'),
      ).toBe(true),
    );
    expect(
      screen.getByRole('button', { name: 'Abandon' }).hasAttribute('disabled'),
    ).toBe(true);

    await act(async () => {
      held.resolve({ ...instances()[0], title: 'Mine', revision: '2' });
    });
  });

  it('says why a write never left, under the row that asked', async () => {
    // A refusal never reached the store, so it has no handle and offers no
    // buttons — only the reason, where the row that asked can be seen.
    const { engine } = setup();
    let captured!: ViewListState;
    render(
      <Standalone
        engine={engine}
        hold={parts => {
          captured = parts.list;
        }}
      />,
    );
    await waitFor(() => expect(rows()).toHaveLength(4));

    cleanup();
    const refused: ViewManagerController = {
      rename: () => Promise.resolve(false),
      delete: () => Promise.resolve(false),
      setDefault: () => Promise.resolve(false),
      move: () => Promise.resolve(false),
      canMove: () => false,
      outcomes: new Map([
        [
          'orders-1',
          {
            requestId: '',
            kind: 'rejected',
            issue: { code: 'view.title.empty', path: [], severity: 'error' },
            payload: {
              action: 'rename',
              id: 'orders-1',
              revision: '',
              title: '',
            },
          },
        ],
      ]),
      retry: () => Promise.resolve(false),
      abandon: () => undefined,
      resolveConflict: () => Promise.resolve(false),
      resubmit: () => Promise.resolve(false),
      canResubmit: () => false,
      pending: null,
      can: {
        reorder: true,
        setDefault: true,
        instance: () => ({ rename: true, delete: true }),
        anything: true,
      },
    };
    render(
      <ViewSurface>
        <ViewManager
          manager={refused}
          list={captured}
          open
          onOpenChange={() => undefined}
        />
      </ViewSurface>,
    );

    expect(row('Mine').textContent).toContain('A view needs a title.');
    expect(within(row('Mine')).queryByRole('button', { name: 'Retry' })).toBe(
      null,
    );
  });
});
