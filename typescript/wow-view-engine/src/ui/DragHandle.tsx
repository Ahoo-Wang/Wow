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

import { useCallback, useRef, useState } from 'react';
import type { KeyboardEvent, MouseEvent, PointerEvent } from 'react';
import { GripVerticalIcon } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
} from './components/dropdown-menu.js';
import { IconButton } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';
import { DropdownMenuContent } from './popups.js';

/**
 * One move a handle asks its list for: a place either way (an arrow key,
 * or 「往前移一位」／「往后移一位」 in its menu), or either end of the list.
 * The list turns it into a place of its own — some read where the row is
 * *now* rather than where it was drawn (the view manager, whose moves land
 * after a write).
 */
export type HandleMove = -1 | 1 | 'first' | 'last';

/** The place a move asks for, in a list of `total` read from `index`. */
export function moveTarget(
  move: HandleMove,
  index: number,
  total: number,
): number {
  return move === 'first' ? 0 : move === 'last' ? total - 1 : index + move;
}

/** Arrow keys that move a row, and how far, along the way its list runs. */
const STEP: Record<
  'vertical' | 'horizontal',
  Record<string, -1 | 1 | undefined>
> = {
  vertical: { ArrowUp: -1, ArrowDown: 1 },
  horizontal: { ArrowLeft: -1, ArrowRight: 1 },
};

/** The menu's four places, in the order it lists them. */
const MENU: readonly {
  move: HandleMove;
  key:
    | 'label.reorder.first'
    | 'label.reorder.earlier'
    | 'label.reorder.later'
    | 'label.reorder.last';
}[] = [
  { move: 'first', key: 'label.reorder.first' },
  { move: -1, key: 'label.reorder.earlier' },
  { move: 1, key: 'label.reorder.later' },
  { move: 'last', key: 'label.reorder.last' },
];

export interface DragHandleProps {
  /**
   * The handle element the library holds — `handleRef` from `useSortable`,
   * which is what makes this button the one place a row can be picked up
   * from rather than the whole row.
   */
  ref?: (element: HTMLElement | null) => void;
  /**
   * What this handle carries, named: "Reorder 金额". Each list words it in
   * its own catalogue key, because a handle in a column panel and a handle
   * in the view manager move different things. The menu is named by it too.
   */
  label: string;
  /**
   * Where the row stands among the rows it can trade places with, from 0,
   * and how many there are: which of the menu's places are there to go to.
   */
  index: number;
  total: number;
  /** True while the library is carrying this row, so the arrows are its. */
  dragging?: boolean;
  /**
   * Off while this row has no order to change — a fixed column, a lone sort
   * entry — or while it is busy with something else. Never a permission (D4):
   * a list that cannot be reordered at all draws no handle in the first place.
   */
  disabled?: boolean;
  /** The row's own sentence saying why the handle is off, when it is off. */
  describedBy?: string;
  /**
   * How large the handle is drawn, matching the other buttons on its row:
   * `icon-xs` (24px, WCAG 2.5.8's floor) in the lists of a popover or a
   * panel, `icon-sm` (28px) in the manager and on a dashboard panel's
   * header, whose other buttons are that size.
   */
  size?: 'icon-xs' | 'icon-sm';
  /**
   * The way the list runs, which is the pair of arrows that moves along it:
   * up and down for the lists, left and right for a dashboard's tab bar and
   * its filter bar.
   */
  axis?: 'vertical' | 'horizontal';
  /** Moves the row, from the arrow keys on this handle or from its menu. */
  onMove(move: HandleMove): void;
}

/**
 * The handle a sortable row is carried by — the one way any list in this
 * package is put in another order (「可排序的列表一律拖拽排序」, 2026-09-25).
 *
 * Every list used to write its own: three wrote this button out word for
 * word, and the rest moved a row with a pair of 「上移」／「下移」 arrows
 * beside it. One component now carries every one of them, which is what
 * keeps them the same — the grip, its place at the row's start, its size,
 * the keys it answers, what it says — and a list only names it, says why it
 * is off, and says where the row landed. It is worked three ways:
 *
 * - **dragged** by a pointer (the library, through `handleRef`);
 * - **arrow keys** move the row one place, and Space picks it up for the
 *   library's own keyboard drag (the instructions on the handle,
 *   `label.reorder.instructions`, say both);
 * - **clicked** — a press that does not travel far enough to be a drag
 *   (`sortableList`'s sensors) — opens a menu of the four places it can go:
 *   to the start, one earlier, one later, to the end. That is the one-press
 *   way to move a row for a pointer that cannot drag (WCAG 2.5.7): a head
 *   pointer, a switch, a hand that shakes. A place the row cannot go is
 *   there and off, so the menu reads the same on every row.
 *
 * **Silent while the row is in the air.** The tooltip would otherwise follow
 * the pointer across the list it is meant to be dropping into, saying
 * something the user is in the middle of doing. Keyboard focus still opens
 * it, which is where the name earns its keep.
 */
export function DragHandle({
  ref,
  label,
  index,
  total,
  dragging,
  disabled,
  describedBy,
  size = 'icon-xs',
  axis = 'vertical',
  onMove,
}: DragHandleProps) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLElement | null>(null);
  const held = useCallback(
    (element: HTMLElement | null) => {
      button.current = element;
      ref?.(element);
    },
    [ref],
  );
  // The handle is the menu's anchor and where the keyboard goes back to: a
  // row is keyed by what it holds, so a move carries this very node along.
  const giveBack = useCallback(() => button.current, []);
  const reachable = (move: HandleMove) => {
    const to = moveTarget(move, index, total);
    return to >= 0 && to < total && to !== index;
  };
  return (
    <>
      <IconButton
        ref={held}
        type="button"
        data-slot="drag-handle"
        // Said on the handle as well as on whatever row a list lifts: the one
        // mark that is there on every list while something is in the air.
        data-dragging={dragging ? '' : undefined}
        label={label}
        silent={dragging || open}
        variant="ghost"
        size={size}
        className="cursor-grab"
        disabled={disabled}
        aria-describedby={describedBy}
        aria-haspopup="menu"
        aria-expanded={open}
        onPointerDown={(event: PointerEvent) => {
          // A mouse press leaves the keyboard where it was, as it did while
          // the library took the row on the press itself: the handle is
          // about to be carried, and a list that moves the row's node
          // takes the focus off it — to nowhere — when the drop lands. A
          // click still opens the menu, which takes the focus itself and
          // hands it back to this handle.
          if (event.pointerType === 'mouse') event.preventDefault();
        }}
        onClick={(event: MouseEvent) => {
          // A drag that just ended is not a press; the library swallows its
          // click, and this is the belt to that brace.
          if (dragging || event.defaultPrevented) return;
          setOpen(true);
        }}
        onKeyDown={(event: KeyboardEvent) => {
          // While the library is carrying the row the arrows are its: two
          // handlers on one press would move the row twice.
          if (dragging) return;
          const step = STEP[axis][event.key];
          if (!step) return;
          event.preventDefault();
          onMove(step);
        }}
      >
        <GripVerticalIcon />
      </IconButton>
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuContent
          anchor={button}
          finalFocus={giveBack}
          aria-label={label}
          data-slot="drag-handle-menu"
        >
          <DropdownMenuGroup>
            {MENU.map(({ move, key }) => (
              <DropdownMenuItem
                key={key}
                data-place={String(move)}
                disabled={!reachable(move)}
                onClick={() => onMove(move)}
              >
                {messages.label(key)}
              </DropdownMenuItem>
            ))}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
