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
 * The two things every sortable list holds in common — the column settings,
 * the sort editor, the view manager, the series, a funnel's stages, a
 * hierarchy's levels, the board's tabs, its filter bar and its one-column
 * reading: the handle a row is carried by, and what makes a finished drag a
 * drop at all.
 *
 * Each list's own suite still says what its handle is named, when it is off
 * and where a row lands; what is here is the part that would otherwise be
 * asserted once per list over one copy of the same button each.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DragHandle, moveTarget } from '../src/ui/DragHandle.js';
import { dropped } from '../src/ui/dragDrop.js';

afterEach(cleanup);

describe('the handle a row is carried by', () => {
  it('is a button under the name the list gave it', () => {
    render(
      <DragHandle
        label="Reorder Amount"
        index={1}
        total={3}
        onMove={vi.fn()}
      />,
    );

    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    // An icon and nothing else, which is why the name is the only thing a
    // reader has: the grip is `aria-hidden` inside the button.
    expect(handle.textContent).toBe('');
    expect(handle.querySelector('svg')).not.toBeNull();
    expect(handle.hasAttribute('disabled')).toBe(false);
  });

  it('moves the row one place per arrow key, and answers no other key', () => {
    const onMove = vi.fn();
    render(
      <DragHandle label="Reorder Amount" index={1} total={3} onMove={onMove} />,
    );
    const handle = screen.getByRole('button', { name: 'Reorder Amount' });

    fireEvent.keyDown(handle, { key: 'ArrowUp' });
    fireEvent.keyDown(handle, { key: 'ArrowDown' });
    expect(onMove.mock.calls).toEqual([[-1], [1]]);

    // Left and right are the row's own business — a text field on it, the
    // list's roving focus — so the handle lets them through untouched.
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    fireEvent.keyDown(handle, { key: 'Enter' });
    expect(onMove).toHaveBeenCalledTimes(2);
  });

  /**
   * While the library is carrying the row it answers the arrows itself. Two
   * handlers on one press would move the row twice, which is the one bug a
   * user cannot undo by pressing the other arrow.
   */
  it('lets the library have the arrows while the row is in the air', () => {
    const onMove = vi.fn();
    render(
      <DragHandle
        label="Reorder Amount"
        index={1}
        total={3}
        dragging
        onMove={onMove}
      />,
    );

    fireEvent.keyDown(screen.getByRole('button', { name: 'Reorder Amount' }), {
      key: 'ArrowDown',
    });
    expect(onMove).not.toHaveBeenCalled();
  });

  /**
   * A row with no place to move to says so on the handle and points at the
   * sentence that explains it — the reason belongs to the row, the pointing
   * to the control it is about.
   */
  it('goes off with the row’s own reason attached', () => {
    render(
      <>
        <DragHandle
          label="Reorder Amount"
          index={0}
          total={1}
          disabled
          describedBy="note-amount"
          onMove={vi.fn()}
        />
        <p id="note-amount">This column is not in the data.</p>
      </>,
    );

    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    expect(handle.hasAttribute('disabled')).toBe(true);
    expect(handle.getAttribute('aria-describedby')).toBe('note-amount');
  });

  /** The element the library picks the row up by is this button, not the row. */
  it('hands the library the button itself', () => {
    const handleRef = vi.fn();
    render(
      <DragHandle
        label="Reorder Amount"
        index={0}
        total={2}
        ref={handleRef}
        onMove={vi.fn()}
      />,
    );

    expect(handleRef).toHaveBeenCalledWith(
      screen.getByRole('button', { name: 'Reorder Amount' }),
    );
  });
});

/**
 * A pointer that cannot drag — a head pointer, a switch, a hand that shakes
 * — still has to be able to move a row with one press (WCAG 2.5.7). The
 * handle is that press: a click on it opens a menu of the four places the
 * row can go, the same four on every list.
 */
describe('the handle’s menu, the one-press way to move a row', () => {
  const open = (index: number, total: number) => {
    const onMove = vi.fn();
    render(
      <DragHandle
        label="Reorder Amount"
        index={index}
        total={total}
        onMove={onMove}
      />,
    );
    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    fireEvent.click(handle);
    return { onMove, handle };
  };
  const item = (name: string) => screen.getByRole('menuitem', { name });

  it('offers the four places, named after the row it moves', async () => {
    const { handle } = open(1, 3);
    expect(handle.getAttribute('aria-haspopup')).toBe('menu');
    const menu = await screen.findByRole('menu', { name: 'Reorder Amount' });
    expect(
      [...menu.querySelectorAll('[role="menuitem"]')].map(
        entry => entry.textContent,
      ),
    ).toEqual([
      'Move to the start',
      'Move one place earlier',
      'Move one place later',
      'Move to the end',
    ]);
    await waitFor(() =>
      expect(handle.getAttribute('aria-expanded')).toBe('true'),
    );
  });

  it('asks the list for the place each one names', async () => {
    const { onMove } = open(1, 3);
    await screen.findByRole('menu');
    fireEvent.click(item('Move to the end'));
    expect(onMove).toHaveBeenLastCalledWith('last');
    cleanup();
    const again = open(1, 3);
    await screen.findByRole('menu');
    fireEvent.click(item('Move one place earlier'));
    expect(again.onMove).toHaveBeenLastCalledWith(-1);
  });

  /**
   * A place the row cannot go is there and off, so the menu reads the same
   * on every row: the first row's 「移到最前」 and 「往前移一位」 put it back
   * where it is.
   */
  it('keeps a place the row cannot go on the menu, and off', async () => {
    open(0, 3);
    await screen.findByRole('menu');
    const off = (name: string) => item(name).getAttribute('aria-disabled');
    expect(off('Move to the start')).toBe('true');
    expect(off('Move one place earlier')).toBe('true');
    expect(off('Move one place later')).toBeNull();
    expect(off('Move to the end')).toBeNull();
  });

  it('opens nothing while the row is in the air', () => {
    const onMove = vi.fn();
    render(
      <DragHandle
        label="Reorder Amount"
        index={1}
        total={3}
        dragging
        onMove={onMove}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Reorder Amount' }));
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('reads each move as a place in the list', () => {
    expect(moveTarget('first', 2, 4)).toBe(0);
    expect(moveTarget('last', 0, 4)).toBe(3);
    expect(moveTarget(-1, 2, 4)).toBe(1);
    expect(moveTarget(1, 2, 4)).toBe(3);
  });
});

/**
 * 「可排序的列表一律拖拽排序」 (2026-09-25): every list here that can be put
 * in another order is carried by the one handle, and none by a pair of
 * up／down buttons. Read off the sources, so a new list that reaches for
 * the library without the handle — or a row that grows arrows again — is
 * caught where it is written.
 */
describe('every ordered list is carried by the one handle', () => {
  const root = join(import.meta.dirname, '../src/ui');
  const sources = (dir: string): string[] =>
    readdirSync(dir).flatMap(name => {
      const path = join(dir, name);
      if (statSync(path).isDirectory())
        return name === 'components' || name === 'lib' ? [] : sources(path);
      return /\.tsx?$/.test(name) ? [path] : [];
    });
  const files = sources(root).map(path => ({
    path: path.slice(root.length + 1),
    text: readFileSync(path, 'utf8'),
  }));

  it('draws the shared handle wherever a row is sortable', () => {
    const sortable = files.filter(file => file.text.includes('useSortable('));
    expect(sortable.length).toBeGreaterThanOrEqual(8);
    expect(
      sortable
        .filter(file => !/<DragHandle\b/.test(file.text))
        .map(file => file.path),
    ).toEqual([]);
  });

  it('moves no row by a button of its own', () => {
    expect(
      files
        .filter(file =>
          /data-move=|label\.[\w.-]*(move|order)-(up|down|left|right)/.test(
            file.text,
          ),
        )
        .map(file => file.path),
    ).toEqual([]);
  });

  it('runs every sortable list’s provider through the shared setup', () => {
    const providers = files.filter(file =>
      file.text.includes('<DragDropProvider'),
    );
    expect(providers.length).toBeGreaterThanOrEqual(8);
    expect(
      providers
        .filter(file => !file.text.includes('{...sortableList('))
        .map(file => file.path),
    ).toEqual([]);
  });
});

/**
 * What every list makes of a finished drag before it makes anything of its
 * own. The gesture itself is a browser story — `@dnd-kit/dom` picks its
 * target by measuring boxes, and in jsdom every box is 0×0 at the origin —
 * so what a drop means is tested here.
 */
describe('what makes a finished drag a drop', () => {
  it('is the two ids it ended between', () => {
    expect(
      dropped({ source: { id: 'amount' }, target: { id: 'warehouse' } }, false),
    ).toEqual({ source: 'amount', target: 'warehouse' });
  });

  /** The library types an id `string | number`; every list reads words. */
  it('reads both ids as the words the list holds them by', () => {
    expect(
      dropped({ source: { id: 1 }, target: { id: 2 } }, undefined),
    ).toEqual({ source: '1', target: '2' });
  });

  it('is nothing when the drag was given up', () => {
    expect(
      dropped({ source: { id: 'amount' }, target: { id: 'warehouse' } }, true),
    ).toBeNull();
  });

  /**
   * A drop on the row it started from would spend a write — a query, a
   * revision — to put the list back in the order it is already in.
   */
  it('is nothing when the row landed where it began', () => {
    expect(
      dropped({ source: { id: 'amount' }, target: { id: 'amount' } }, false),
    ).toBeNull();
  });

  it('is nothing when the drag ended on nothing at all', () => {
    expect(dropped({ source: { id: 'amount' } }, false)).toBeNull();
    expect(dropped({ target: { id: 'amount' } }, false)).toBeNull();
    expect(dropped({ source: null, target: null }, false)).toBeNull();
  });
});
