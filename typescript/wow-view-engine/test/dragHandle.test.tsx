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
 * The two things the three sortable lists — column settings, sort editor,
 * view manager — hold in common: the handle a row is carried by, and what
 * makes a finished drag a drop at all.
 *
 * Each list's own suite still says what its handle is named and when it is
 * off; what is here is the part that would otherwise be asserted three times
 * over three copies of the same button.
 */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DragHandle } from '../src/ui/DragHandle.js';
import { dropped } from '../src/ui/dragDrop.js';

afterEach(cleanup);

describe('the handle a row is carried by', () => {
  it('is a button under the name the list gave it', () => {
    render(<DragHandle label="Reorder Amount" onMove={vi.fn()} />);

    const handle = screen.getByRole('button', { name: 'Reorder Amount' });
    // An icon and nothing else, which is why the name is the only thing a
    // reader has: the grip is `aria-hidden` inside the button.
    expect(handle.textContent).toBe('');
    expect(handle.querySelector('svg')).not.toBeNull();
    expect(handle.hasAttribute('disabled')).toBe(false);
  });

  it('moves the row one place per arrow key, and answers no other key', () => {
    const onMove = vi.fn();
    render(<DragHandle label="Reorder Amount" onMove={onMove} />);
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
    render(<DragHandle label="Reorder Amount" dragging onMove={onMove} />);

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
      <DragHandle label="Reorder Amount" ref={handleRef} onMove={vi.fn()} />,
    );

    expect(handleRef).toHaveBeenCalledWith(
      screen.getByRole('button', { name: 'Reorder Amount' }),
    );
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
