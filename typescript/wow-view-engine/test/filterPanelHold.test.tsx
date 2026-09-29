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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import { FilterPanel } from '../src/ui/index.js';
import {
  ordersDefinition,
  recordConfig,
  resourcesOf,
  testSource,
} from './fixtures.js';
import { mine } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The panel holds the auto-refresh timer while the user is in it
 * (`editing`), and lets go when focus leaves — a popup of one of its own
 * controls, portalled outside, is still inside (`leavesEditor`). Moved out
 * of `filterPanel.test.tsx`, which had reached its line ceiling.
 */
describe('FilterPanel and auto refresh', () => {
  /** The panel over a fresh runtime, with the runtime in reach. */
  function panelWithRuntime() {
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store: new MemoryViewStore({ instances: [mine] }),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig({
        filter: {
          op: 'and',
          children: [
            { field: 'warehouse', operator: 'EQ', value: 'CN' },
            { field: 'status', operator: 'EQ', value: 'open' },
          ],
        },
      }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <>
        <Probe />
        <button type="button">Elsewhere</button>
      </>,
    );
    const editing = () => runtime.getSnapshot().editing;
    return { runtime, editing };
  }

  it('holds the timer while an input inside has focus, and lets go after', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');

    expect(editing()).toBe(false);
    fireEvent.focus(input, { relatedTarget: null });
    expect(editing()).toBe(true);

    fireEvent.blur(input, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('does not let go while focus moves between two inputs inside', () => {
    const { runtime, editing } = panelWithRuntime();
    const setEditing = vi.spyOn(runtime, 'setEditing');
    const first = screen.getByLabelText('Warehouse value');
    const second = screen.getByLabelText('Status value');

    fireEvent.focus(first, { relatedTarget: null });
    // A move within the panel: the blur names the input gaining focus and
    // the focus names the one losing it. Neither crosses the panel's edge.
    fireEvent.blur(first, { relatedTarget: second });
    fireEvent.focus(second, { relatedTarget: first });

    expect(editing()).toBe(true);
    expect(setEditing).toHaveBeenCalledTimes(1);
    expect(setEditing).toHaveBeenCalledWith(true);
  });

  it('keeps holding while focus is in a popup of one of its controls', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');
    const trigger = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });
    fireEvent.focus(input, { relatedTarget: null });

    // A select's list renders in a portal outside the panel, so focus moving
    // into it looks like leaving. Base UI marks the trigger of an open popup
    // with `data-popup-open`, which is what the panel goes by.
    trigger.setAttribute('data-popup-open', '');
    fireEvent.blur(trigger, { relatedTarget: document.body });
    expect(editing()).toBe(true);

    // Closed again, focus back on the trigger: a later blur is a real leave.
    trigger.removeAttribute('data-popup-open');
    fireEvent.focus(trigger, { relatedTarget: document.body });
    fireEvent.blur(trigger, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  /**
   * A button can be a menu's trigger and a tooltip's at once (「添加分组」's
   * chevron: `IconTooltip render={<DropdownMenuTrigger/>}`). Its menu open,
   * focus going into that portalled menu is still focus in the editor —
   * only a tooltip with no popup of its own is let go of.
   */
  it('keeps holding while the menu of a trigger that is also a tooltip is open', async () => {
    const user = userEvent.setup();
    const { editing } = panelWithRuntime();
    // Only the advanced editor adds groups.
    await user.click(screen.getByRole('button', { name: 'Advanced' }));
    const chevron = (
      await screen.findAllByRole('button', { name: 'Add a group' })
    )[0];
    fireEvent.focus(chevron, { relatedTarget: null });
    expect(editing()).toBe(true);

    await user.click(chevron);
    const item = (await screen.findAllByRole('menuitem'))[0];
    expect(chevron.hasAttribute('data-base-ui-tooltip-trigger')).toBe(true);
    expect(chevron.hasAttribute('data-popup-open')).toBe(true);
    fireEvent.blur(chevron, { relatedTarget: item });
    expect(editing()).toBe(true);
  });

  it('lets go past a tooltip whose trigger holds no popup of its own', async () => {
    const { editing } = panelWithRuntime();
    fireEvent.click(screen.getByRole('button', { name: 'Advanced' }));
    const chevron = (
      await screen.findAllByRole('button', { name: 'Add a group' })
    )[0];
    fireEvent.focus(chevron, { relatedTarget: null });

    // Its tooltip open, its menu closed: the tooltip is open only because
    // the focus that is leaving is on it.
    expect(chevron.getAttribute('aria-expanded')).not.toBe('true');
    chevron.setAttribute('data-popup-open', '');
    fireEvent.blur(chevron, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('lets go when focus leaves the document altogether', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('Warehouse value');

    fireEvent.focus(input, { relatedTarget: null });
    fireEvent.blur(input, { relatedTarget: null });

    expect(editing()).toBe(false);
  });
});
