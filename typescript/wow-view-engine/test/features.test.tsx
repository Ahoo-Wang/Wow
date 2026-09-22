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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { RecordWorkbench } from '../src/ui/index.js';
import { ordersDefinition, testSource } from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

function engineWith(rows = true) {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [mine] }),
    resolveSource: () =>
      rows
        ? testSource()
        : testSource({
            paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
          }),
  });
}

const OFF = {
  export: false,
  layouts: false,
  columns: false,
  sort: false,
  manage: false,
};

/**
 * Which of the workbench's own controls exist is the host's to say (D18 XI),
 * and one it says no to is absent rather than disabled (D4). Before this
 * the only switch was `expandable`; a view that must not be exported had to
 * be a workbench rebuilt from parts.
 */
describe('workbench features', () => {
  it('draws every control by default', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    for (const name of ['Export', 'Columns', 'Sort', 'Manage views'])
      expect(screen.getByRole('button', { name })).toBeDefined();
    expect(screen.getByRole('group', { name: 'Layout' })).toBeDefined();
  });

  it('leaves out every control a host switched off', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
        features={OFF}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    for (const name of ['Export', 'Columns', 'Sort', 'Manage views'])
      expect(screen.queryByRole('button', { name })).toBeNull();
    expect(screen.queryByRole('group', { name: 'Layout' })).toBeNull();
    // Absent, not disabled: nothing on the page is a grey control.
    expect(screen.queryByRole('group', { name: 'Table settings' })).toBeNull();
    // The rows, the pagination and the filter are not features; they stay.
    expect(
      screen.getByRole('navigation', { name: 'Pagination' }),
    ).toBeDefined();
  });

  it('keeps the manager out of the switcher too', async () => {
    render(
      <RecordWorkbench
        engine={engineWith()}
        definitionId="orders"
        instanceId="orders-1"
        defaultSidebarOpen={false}
        features={{ manage: false }}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    fireEvent.click(screen.getByRole('button', { name: 'Switch view' }));
    expect(
      await screen.findByRole('menuitem', { name: 'New view' }),
    ).toBeDefined();
    expect(screen.queryByRole('menuitem', { name: 'Manage views' })).toBeNull();
  });
});

describe("the empty result's action", () => {
  it("is the workbench's by default, the host's when given, and none on null", async () => {
    const own = vi.fn();
    const { rerender } = render(
      <RecordWorkbench
        engine={engineWith(false)}
        definitionId="orders"
        instanceId="orders-1"
        emptyAction={own}
      />,
    );
    // Which of the two ways out it is depends on the conditions; either
    // way the press is the host's.
    const button = await screen.findByRole('button', { name: /condition/ });
    fireEvent.click(button);
    expect(own).toHaveBeenCalledTimes(1);

    rerender(
      <RecordWorkbench
        engine={engineWith(false)}
        definitionId="orders"
        instanceId="orders-1"
        emptyAction={null}
      />,
    );
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /condition/ })).toBeNull(),
    );
    // The sentence stays (drawn once, and announced once more).
    expect(
      document.querySelector('[data-slot="empty-title"]')?.textContent,
    ).toBe('Nothing to show');
  });
});
