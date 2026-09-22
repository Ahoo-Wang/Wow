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
import { ViewStoreError, type ViewInstance } from '../src/index.js';
import type {
  ViewListState,
  ViewManagerController,
} from '../src/react/index.js';
import { ViewManager } from '../src/ui/ViewManager.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { deferred, recordConfig } from './fixtures.js';
import {
  Standalone,
  handle,
  instances,
  manage,
  row,
  rows,
  setup,
} from './fixtures/manager.js';
import { landed } from './fixtures/writes.js';

afterEach(cleanup);

describe('ViewManager outcomes', () => {
  it('offers a way out of a conflict under the row that caused it', async () => {
    const { engine, store } = setup();
    await manage(engine);
    // Somebody else renamed it after this list was read.
    await store.rename('orders-1', 'Theirs', '1', { requestId: 'other' });

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText(/^Rename /), {
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

    await landed(store);
    expect((await store.get('orders-1')).title).toBe('Renamed');
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

    fireEvent.keyDown(handle('Mine'), { key: 'ArrowDown' });

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

    fireEvent.keyDown(handle('Mine'), { key: 'ArrowDown' });
    await screen.findByText('Someone else saved this view first');
    fireEvent.click(screen.getByRole('button', { name: 'Reload list' }));

    const again = await screen.findByRole('button', { name: 'Apply again' });
    expect(screen.queryByRole('button', { name: 'Keep mine' })).toBeNull();
    fireEvent.click(again);

    // It lands this time, and the line goes with it.
    await landed(store);
    expect((await store.getPreferences('orders')).order).toEqual([
      'system:orders:all',
      'orders-2',
      'orders-1',
      'orders-3',
    ]);
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
    const first = await screen.findByRole('alertdialog');
    fireEvent.click(within(first).getByRole('button', { name: 'Delete' }));

    await screen.findByText('Someone else saved this view first');
    fireEvent.click(screen.getByRole('button', { name: 'Keep mine' }));

    // Not deleted yet: the destructive answer is the dialog's, not the line's.
    const second = await screen.findByRole('alertdialog');
    expect((await store.list('orders')).map(item => item.id)).toContain(
      'orders-2',
    );

    fireEvent.click(within(second).getByRole('button', { name: 'Delete' }));
    await landed(store);
    expect((await store.list('orders')).map(item => item.id)).not.toContain(
      'orders-2',
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
    const confirm = await screen.findByRole('alertdialog');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));

    await screen.findByText('The result never came back');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await landed(store);
    expect((await store.list('orders')).map(item => item.id)).not.toContain(
      'orders-2',
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
      moveTo: () => Promise.resolve(false),
      placeOf: () => -1,
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
