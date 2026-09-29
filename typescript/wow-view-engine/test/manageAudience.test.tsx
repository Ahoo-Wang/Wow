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
 * 设为共享／设为个人 in the view manager (D18 item 10): a row's button says
 * where it sends the view, the row lands in the other group of the dialog
 * and of the sidebar, the move is said once it has landed, and focus
 * follows the row.
 */

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine, ViewStoreError } from '../src/index.js';
import { zhCN } from '../src/ui/index.js';
import { ViewManager } from '../src/ui/manage/ViewManager.js';
import { ViewSurface } from '../src/ui/kit/ViewSurface.js';
import { useViewList, useViewManager } from '../src/react/index.js';
import { ordersDefinition, resourcesOf, testSource } from './fixtures.js';
import {
  announcement,
  groupsOfRows,
  instances,
  manage,
  permitting,
  row,
  rows,
  setup,
} from './fixtures/manager.js';
import { landed, tracked, withoutAudience } from './fixtures/writes.js';

afterEach(cleanup);

/** The row's button that moves the view to the other audience, if it has one. */
function audienceButton(title: string): HTMLElement | null {
  return row(title).querySelector('[data-audience-target]');
}

/** The sidebar group a title is listed under, read off its heading. */
function sidebarGroupOf(title: string): string | null {
  for (const group of document.querySelectorAll('[data-slot="view-group"]'))
    if (
      Array.from(group.querySelectorAll('button')).some(button =>
        button.textContent?.includes(title),
      )
    )
      return (
        group.querySelector('[data-slot="view-group-heading"]')?.textContent ??
        null
      );
  return null;
}

describe('the view manager’s move between audiences', () => {
  it('shares a personal view in place, and the row and the sidebar follow it', async () => {
    const { engine, store } = setup();
    await manage(engine);
    expect(sidebarGroupOf('Mine')).toBe('My views');

    const share = within(row('Mine')).getByRole('button', {
      name: 'Make shared',
    });
    fireEvent.click(share);
    await landed(store);

    expect((await store.get('orders-1')).scope).toBe('shared');
    await waitFor(() =>
      expect(groupsOfRows()).toContainEqual([
        expect.stringContaining('Mine'),
        'shared',
      ]),
    );
    expect(sidebarGroupOf('Mine')).toBe('Shared views');
    expect(announcement()).toBe('Mine is now shared');
    // The button that was pressed went with its row; focus is on the one
    // the row has now, which says the way back.
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(row('Mine')).getByRole('button', { name: 'Make personal' }),
      ),
    );
  });

  it('makes a shared view personal the same way', async () => {
    const { engine, store } = setup();
    await manage(engine);

    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: 'Make personal' }),
    );
    await landed(store);

    expect((await store.get('orders-3')).scope).toBe('personal');
    await waitFor(() => expect(announcement()).toBe('Ours is now personal'));
    await waitFor(() => expect(sidebarGroupOf('Ours')).toBe('My views'));
  });

  it('puts focus on the heading when the way back is not the user’s to take', async () => {
    const { engine, store } = setup(permitting({ createPersonal: false }));
    await manage(engine);

    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Make shared' }),
    );
    await landed(store);

    await waitFor(() => expect(audienceButton('Mine')).toBeNull());
    expect(document.activeElement).toBe(
      screen.getByRole('heading', { name: 'Manage views' }),
    );
  });

  it('offers no move on a system view, or where the store refuses it', async () => {
    const { engine } = setup(
      permitting({
        instance: id => ({
          save: true,
          rename: true,
          delete: true,
          changeAudience: id !== 'orders-2',
        }),
      }),
    );
    await manage(engine);

    expect(audienceButton('All orders')).toBeNull();
    expect(audienceButton('Yours')).toBeNull();
    expect(audienceButton('Mine')?.getAttribute('data-audience-target')).toBe(
      'shared',
    );
  });

  it('offers no move at all over a store without one', async () => {
    const store = withoutAudience(
      new MemoryViewStore({ instances: instances() }),
    );
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
    });
    await manage(engine);

    expect(document.querySelectorAll('[data-audience-target]').length).toBe(0);
    expect(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    ).toBeDefined();
  });

  it('keeps a refused move on its row and says nothing landed', async () => {
    const memory = tracked(new MemoryViewStore({ instances: instances() }));
    vi.spyOn(memory, 'changeAudience').mockRejectedValueOnce(
      new ViewStoreError(
        'INVALID',
        'Shared dashboards show view orders-3: Team board',
      ),
    );
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store: memory,
    });
    await manage(engine);

    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: 'Make personal' }),
    );

    await waitFor(() =>
      expect(row('Ours').textContent).toContain(
        'Who this view is for could not be changed: Shared dashboards show view orders-3: Team board',
      ),
    );
    expect(groupsOfRows()).toContainEqual([
      expect.stringContaining('Ours'),
      'shared',
    ]);
    expect(announcement()).toBe('');
  });
});

describe('the words, in both catalogues', () => {
  function Chinese({ engine }: { engine: ViewEngine }) {
    const list = useViewList(engine, 'orders');
    const manager = useViewManager(engine, 'orders', list);
    return (
      <ViewSurface messages={zhCN}>
        <ViewManager
          manager={manager}
          list={list}
          open
          onOpenChange={() => undefined}
        />
      </ViewSurface>
    );
  }

  it('says 设为共享 and 设为个人, and what landed, in Chinese', async () => {
    const { engine, store } = setup();
    render(<Chinese engine={engine} />);
    await waitFor(() => expect(rows()).toHaveLength(4));

    expect(
      within(row('Mine')).getByRole('button', { name: '设为共享' }),
    ).toBeDefined();
    fireEvent.click(
      within(row('Ours')).getByRole('button', { name: '设为个人' }),
    );
    await landed(store);

    await waitFor(() => expect(announcement()).toBe('Ours 已设为个人'));
  });
});
