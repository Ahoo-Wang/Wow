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

import type { KeyboardEvent } from 'react';
import { GripVerticalIcon } from 'lucide-react';
import { IconButton } from './IconButton.js';

/** Arrow keys that move a row, and how far. */
const STEP: Record<string, -1 | 1 | undefined> = {
  ArrowUp: -1,
  ArrowDown: 1,
};

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
   * in the view manager move different things.
   */
  label: string;
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
   * `icon-xs` in the column settings and the sort editor, `icon-sm` in the
   * manager, whose action cells are sized by three `icon-sm` buttons.
   */
  size?: 'icon-xs' | 'icon-sm';
  /** Moves the row one place, from the arrow keys on this handle. */
  onMove(step: -1 | 1): void;
}

/**
 * The handle a sortable row is carried by, and the arrow keys that move it
 * without a pointer.
 *
 * The three sortable lists in this package — the column settings, the sort
 * editor and the view manager — used to write this button out three times,
 * word for word, down to which arrow keys it answers and the reason the
 * tooltip goes quiet mid-drag. One of them would have grown a fourth key or
 * a different name and nobody would have noticed, because there was nothing
 * for the other two to be compared against. It is one component now; what
 * still differs per list is the name, the reason it is off, and the size of
 * the buttons beside it.
 *
 * **Silent while the row is in the air.** The tooltip would otherwise follow
 * the pointer across the list it is meant to be dropping into, saying
 * something the user is in the middle of doing. Keyboard focus still opens
 * it, which is where the name earns its keep — the arrow keys this handle
 * answers are written nowhere else on screen.
 */
export function DragHandle({
  ref,
  label,
  dragging,
  disabled,
  describedBy,
  size = 'icon-xs',
  onMove,
}: DragHandleProps) {
  return (
    <IconButton
      ref={ref}
      type="button"
      label={label}
      silent={dragging}
      variant="ghost"
      size={size}
      className="cursor-grab"
      disabled={disabled}
      aria-describedby={describedBy}
      onKeyDown={(event: KeyboardEvent) => {
        // While the library is carrying the row the arrows are its: two
        // handlers on one press would move the row twice.
        if (dragging) return;
        const step = STEP[event.key];
        if (!step) return;
        event.preventDefault();
        onMove(step);
      }}
    >
      <GripVerticalIcon />
    </IconButton>
  );
}
