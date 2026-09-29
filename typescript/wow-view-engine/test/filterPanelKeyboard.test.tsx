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
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { FilterPanel, MessagesProvider } from '../src/ui/index.js';
import type { FilterPanelProps } from '../src/ui/index.js';
import {
  ordersDefinition,
  recordConfig,
  testSource,
  resourcesOf,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * Where the keyboard goes when the filter panel takes away the control it
 * was on, or hands over a condition waiting for its value (review
 * R1-P1-1, R1-P1-10).
 */
describe('FilterPanel and the keyboard', () => {
  function panel(props: Partial<FilterPanelProps> = {}) {
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store: new MemoryViewStore({ instances: [mine] }),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} {...props} />;
    }
    render(
      <MessagesProvider>
        <Probe />
      </MessagesProvider>,
    );
    return { filter: () => latest as ReturnType<typeof useFilterEditor> };
  }

  async function openPicker(): Promise<HTMLElement> {
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    return screen.findByRole('dialog', { name: 'Choose fields' });
  }

  /**
   * Where the keyboard stands after a condition or a group is taken out
   * (review R1-P1-1): the entry that took its place, the one before it at
   * the end of the strip, and the group's own 「添加」 when it is empty —
   * never `<body>`.
   */
  describe('the keyboard after a removal', () => {
    it('lands on the next condition, then the one before, then Add', async () => {
      const { filter } = panel();
      const user = userEvent.setup();
      act(() => {
        filter().addLeaf('warehouse');
        filter().addLeaf('status');
      });

      screen.getByRole('button', { name: 'Remove Warehouse' }).focus();
      await user.keyboard('{Enter}');
      await waitFor(() => expect(filter().tree.children).toHaveLength(1));
      expect(
        screen
          .getByRole('group', { name: 'Status condition' })
          .contains(document.activeElement),
      ).toBe(true);

      act(() => filter().addLeaf('amount'));
      screen.getByRole('button', { name: 'Remove Amount' }).focus();
      await user.keyboard('{Enter}');
      await waitFor(() => expect(filter().tree.children).toHaveLength(1));
      expect(
        screen
          .getByRole('group', { name: 'Status condition' })
          .contains(document.activeElement),
      ).toBe(true);

      screen.getByRole('button', { name: 'Remove Status' }).focus();
      await user.keyboard('{Enter}');
      await waitFor(() => expect(filter().tree.children).toHaveLength(0));
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Add' }),
      );
    });

    it('lands inside the group a condition was removed from, in advanced mode', async () => {
      const { filter } = panel();
      const user = userEvent.setup();
      act(() => {
        filter().addGroup('or');
        filter().addLeaf('warehouse', [0]);
      });
      const group = screen.getByRole('group', { name: 'Any condition' });

      within(group).getByRole('button', { name: 'Remove Warehouse' }).focus();
      await user.keyboard('{Enter}');
      await waitFor(() =>
        expect(filter().tree.children[0]).toMatchObject({ children: [] }),
      );
      expect(document.activeElement).toBe(
        within(group).getByRole('button', { name: 'Add condition' }),
      );
    });

    it('lands on what follows a removed group', async () => {
      const { filter } = panel();
      const user = userEvent.setup();
      act(() => {
        filter().addGroup('or');
        filter().addLeaf('status', [0]);
        filter().addLeaf('warehouse');
      });

      screen.getByRole('button', { name: 'Remove group' }).focus();
      await user.keyboard('{Enter}');
      await waitFor(() => expect(filter().tree.children).toHaveLength(1));
      expect(
        screen
          .getByRole('group', { name: 'Warehouse condition' })
          .contains(document.activeElement),
      ).toBe(true);
    });
  });

  describe('the field list, closed', () => {
    it('hands the keyboard to the value of the first field it added', async () => {
      panel();
      const picker = await openPicker();
      fireEvent.click(within(picker).getByRole('checkbox', { name: 'Status' }));
      fireEvent.click(
        within(picker).getByRole('checkbox', { name: 'Warehouse' }),
      );
      fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      await waitFor(() =>
        expect(
          screen
            .getByRole('group', { name: 'Status condition' })
            .querySelector('[data-slot="filter-value"]')
            ?.contains(document.activeElement),
        ).toBe(true),
      );
    });

    it('hands it back to Add when nothing was added', async () => {
      panel();
      const picker = await openPicker();
      fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      await waitFor(() =>
        expect(document.activeElement).toBe(
          screen.getByRole('button', { name: 'Add' }),
        ),
      );
    });

    it('opens when asked to pick', async () => {
      const { filter } = panel({ pick: 1 });
      expect(
        await screen.findByRole('dialog', { name: 'Choose fields' }),
      ).toBeDefined();
      expect(filter().tree.children).toHaveLength(0);
    });
  });
});
