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

import { createRef, type ComponentProps } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  Accessibility,
  KeyboardSensor,
  PointerActivationConstraints,
  PointerSensor,
} from '@dnd-kit/dom';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { sameJson } from '../src/index.js';
import { RenameInput } from '../src/ui/RenameInput.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';
import { dragAccessibility } from '../src/ui/dragAnnounce.js';
import {
  sortableList,
  withoutOptimisticSorting,
} from '../src/ui/dragPlugins.js';

afterEach(cleanup);

type Props = Partial<ComponentProps<typeof RenameInput>>;

function open(props: Props = {}) {
  const onCommit = vi.fn();
  const onCancel = vi.fn();
  const outside = vi.fn();
  render(
    <ViewSurface>
      {/* What the field sits in — a dialog, a popover — listening for the
          keys the field takes. */}
      <div onKeyDown={outside}>
        <RenameInput
          initial="Orders"
          label="Name"
          onCommit={onCommit}
          onCancel={onCancel}
          {...props}
        />
        <button type="button">Elsewhere</button>
      </div>
    </ViewSurface>,
  );
  const field = screen.getByRole('textbox', {
    name: 'Name',
  }) as HTMLInputElement;
  return { field, onCommit, onCancel, outside, user: userEvent.setup() };
}

/**
 * The panel's title, the tab's name and the view manager's row used to be
 * three fields with three sets of rules (Q-10); these are the one set.
 */
describe('RenameInput, one in-place rename', () => {
  it('appears focused with the name selected, so typing replaces it', async () => {
    const { field, onCommit, user } = open();
    expect(document.activeElement).toBe(field);
    expect(field.selectionStart).toBe(0);
    expect(field.selectionEnd).toBe('Orders'.length);
    await user.keyboard('Sales{Enter}');
    expect(onCommit).toHaveBeenCalledWith('Sales');
  });

  it('keeps a trimmed name on Enter, and the key stops at the field', async () => {
    const { field, onCommit, onCancel, outside, user } = open();
    await user.clear(field);
    await user.type(field, '  Sales  {Enter}');
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith('Sales');
    expect(onCancel).not.toHaveBeenCalled();
    expect(outside).not.toHaveBeenCalledWith(
      expect.objectContaining({ key: 'Enter' }),
    );
  });

  it('puts the name back on Escape, which goes no further', async () => {
    const { field, onCommit, onCancel, outside, user } = open();
    await user.type(field, 'x{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCommit).not.toHaveBeenCalled();
    expect(outside).not.toHaveBeenCalledWith(
      expect.objectContaining({ key: 'Escape' }),
    );
  });

  it('calls an unchanged name no rename', async () => {
    const { onCommit, onCancel, user } = open({ initial: 'Orders ' });
    await user.keyboard('{End} {Enter}');
    expect(onCommit).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('keeps what was typed when the field is left, and ends once', async () => {
    const { field, onCommit, onCancel, user } = open();
    await user.clear(field);
    await user.type(field, 'Sales');
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(onCommit).toHaveBeenCalledWith('Sales');
    // The blur after an Enter is the same edit, not a second one.
    fireEvent.keyDown(field, { key: 'Enter' });
    fireEvent.blur(field);
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('takes a blank name where it is not refused', async () => {
    const { field, onCommit, user } = open();
    await user.clear(field);
    await user.keyboard('{Enter}');
    expect(onCommit).toHaveBeenCalledWith('');
  });

  it('refuses a blank name where asked: Enter waits, leaving puts it back', async () => {
    const { field, onCommit, onCancel, user } = open({ required: true });
    await user.clear(field);
    await user.type(field, '   {Enter}');
    expect(onCommit).not.toHaveBeenCalled();
    expect(onCancel).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(field);
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(onCommit).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('waits while the name cannot be taken, and stays open when left', async () => {
    const { field, onCommit, onCancel, user } = open({ held: true });
    await user.type(field, 'x{Enter}');
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(onCommit).not.toHaveBeenCalled();
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: 'Name' })).toBeTruthy();
  });

  it('hands the keyboard back where asked after Enter or Escape', async () => {
    const returnTo = createRef<HTMLButtonElement>();
    const onCommit = vi.fn();
    render(
      <ViewSurface>
        <button type="button" ref={returnTo}>
          Menu
        </button>
        <RenameInput
          initial="Orders"
          label="Name"
          onCommit={onCommit}
          onCancel={vi.fn()}
          returnTo={returnTo}
        />
      </ViewSurface>,
    );
    await userEvent.setup().keyboard('Sales{Enter}');
    expect(onCommit).toHaveBeenCalledWith('Sales');
    expect(document.activeElement).toBe(returnTo.current);
  });

  describe('with ✓ and ✕ in the field', () => {
    const answers = { confirm: 'Save the name', cancel: 'Keep the name' };

    it('keeps the name on ✓ and puts it back on ✕, the press not a leave', async () => {
      const first = open({ answers });
      await first.user.keyboard('Sales');
      await first.user.click(
        screen.getByRole('button', { name: 'Save the name' }),
      );
      expect(first.onCommit).toHaveBeenCalledWith('Sales');
      expect(first.onCancel).not.toHaveBeenCalled();
      cleanup();

      const second = open({ answers });
      await second.user.keyboard('Sales');
      await second.user.click(
        screen.getByRole('button', { name: 'Keep the name' }),
      );
      expect(second.onCommit).not.toHaveBeenCalled();
      expect(second.onCancel).toHaveBeenCalledTimes(1);
    });

    it('is still being edited while the keyboard is on its own buttons', () => {
      const { field, onCommit, onCancel } = open({ answers });
      fireEvent.blur(field, {
        relatedTarget: screen.getByRole('button', { name: 'Save the name' }),
      });
      expect(onCommit).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });

    it('offers no ✓ for a name it refuses', async () => {
      const { field, user } = open({ answers, required: true });
      await user.clear(field);
      expect(
        screen
          .getByRole('button', { name: 'Save the name' })
          .hasAttribute('disabled'),
      ).toBe(true);
    });
  });
});

/** Every sortable list's plugin and sensor boilerplate, set once (Q-11). */
describe('the drag plugins', () => {
  const say = {
    instructions: 'Carry it',
    picked: (name: string) => `Picked ${name}`,
    cancelled: (name: string) => `Dropped ${name}`,
  };

  it('words the library’s Accessibility plugin and leaves the rest alone', () => {
    const other = { name: 'other' };
    const defaults = [other, Accessibility] as unknown as Parameters<
      ReturnType<typeof sortableList>['plugins']
    >[0];
    const plugins = sortableList(dragAccessibility(say, id => id)).plugins(
      defaults,
    );
    expect(plugins).toHaveLength(2);
    expect(plugins[0]).toBe(other);
    expect(plugins[1]).not.toBe(Accessibility);
    expect(plugins[1]).toMatchObject({
      plugin: Accessibility,
      options: {
        screenReaderInstructions: { draggable: 'Carry it' },
      },
    });
  });

  /**
   * A mouse used to pick a row up on the press itself, so the click that
   * followed was swallowed as a drag's end and the handle's menu — the
   * one-press way to move a row (WCAG 2.5.7) — never opened. A press is a
   * drag only once the pointer has travelled, or a finger has rested.
   */
  it('waits for a press to travel before it is a drag, and leaves the keyboard alone', () => {
    const sensors = sortableList(dragAccessibility(say, id => id)).sensors([
      PointerSensor,
      KeyboardSensor,
    ] as unknown as Parameters<ReturnType<typeof sortableList>['sensors']>[0]);
    expect(sensors).toHaveLength(2);
    expect(sensors[1]).toBe(KeyboardSensor);
    const pointer = sensors[0] as unknown as {
      plugin: unknown;
      options: {
        activationConstraints(event: { pointerType: string }): unknown[];
      };
    };
    expect(pointer.plugin).toBe(PointerSensor);
    const [mouse] = pointer.options.activationConstraints({
      pointerType: 'mouse',
    });
    expect(mouse).toBeInstanceOf(PointerActivationConstraints.Distance);
    const [finger] = pointer.options.activationConstraints({
      pointerType: 'touch',
    });
    expect(finger).toBeInstanceOf(PointerActivationConstraints.Delay);
  });

  it('takes the optimistic sorting out of a row’s plugins', () => {
    const other = { name: 'other' };
    expect(withoutOptimisticSorting([other, OptimisticSortingPlugin])).toEqual([
      other,
    ]);
  });
});

describe('sameJson', () => {
  it('reads two JSON values as the same whatever their key order', () => {
    expect(
      sameJson(
        { a: 1, b: { c: [1, 2], d: null } },
        {
          b: { d: null, c: [1, 2] },
          a: 1,
        },
      ),
    ).toBe(true);
    expect(sameJson({ a: [1, 2] }, { a: [2, 1] })).toBe(false);
    expect(sameJson({ a: undefined }, {})).toBe(true);
    expect(sameJson(null, undefined)).toBe(false);
    expect(sameJson('x', 'x')).toBe(true);
  });
});
