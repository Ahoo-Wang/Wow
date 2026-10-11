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
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { DataWorkbench } from '../src/ui/index.js';
import { mine, ordersDefinition, resourcesOf, testSource } from './fixtures.js';

afterEach(cleanup);

/** A workbench over a definition with a search field, and its store. */
async function open() {
  const base = ordersDefinition();
  const definition = ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'keyword', label: 'Search orders', kind: 'search' },
    ],
  });
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => testSource()),
    store,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
    />,
  );
  await screen.findAllByRole('row');
  return { store };
}

/** Somewhere on the surface that is not a field: the result table. */
const table = () => screen.getByRole('table');
const search = () => screen.getByRole('searchbox', { name: 'Search orders' });

describe('the workbench’s keys (R2-100)', () => {
  it('puts the keyboard in the search box on 「/」, and says so on the box', async () => {
    await open();
    table().focus();

    const press = fireEvent.keyDown(table(), { key: '/' });

    expect(press).toBe(false);
    expect(document.activeElement).toBe(search());
    expect(search().getAttribute('aria-keyshortcuts')).toBe('/');
  });

  it('leaves 「/」 to a field, where it is a character', async () => {
    await open();
    search().focus();
    fireEvent.change(search(), { target: { value: 'a' } });

    expect(fireEvent.keyDown(search(), { key: '/' })).toBe(true);
    // Nor with a modifier held, which is somebody else's shortcut.
    expect(fireEvent.keyDown(table(), { key: '/', ctrlKey: true })).toBe(true);
  });

  it('saves on ⌘S as the button would, from anywhere on the surface', async () => {
    const { store } = await open();
    const save = screen.getByRole('button', { name: /^Save/ });
    expect(save.getAttribute('aria-keyshortcuts')).toBe('Meta+S Control+S');

    // Nothing to save: the browser's own save is still not what is meant.
    expect(fireEvent.keyDown(table(), { key: 's', metaKey: true })).toBe(false);
    expect((await store.get('orders-1')).revision).toBe('1');

    // An edit, then the keys pressed inside a field: it saves.
    fireEvent.change(search(), { target: { value: 'late' } });
    fireEvent.keyDown(search(), { key: 's', ctrlKey: true });
    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
    expect(JSON.stringify((await store.get('orders-1')).config)).toContain(
      'late',
    );
  });
});
