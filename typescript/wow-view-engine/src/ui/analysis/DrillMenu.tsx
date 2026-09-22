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

import { CrosshairIcon, ListTreeIcon, TableIcon } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { FilterSummaryItem } from '../../filter/index.js';
import type { RecordData } from '../../model/index.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent, DropdownMenuSubContent } from '../popups.js';
import { summaryText } from '../summary.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** Where a menu is placed: the mark or row pressed, or the point it was pressed at. */
export type PickAnchor = Element | { getBoundingClientRect(): DOMRect };

/** One group of the result the user pressed, and where. */
export interface Pick {
  row: RecordData;
  anchor: PickAnchor;
}

/** A dimension the group can be split by: a field not yet grouped on. */
export interface SplitOption {
  field: string;
  label: string;
}

export interface DrillMenuProps {
  /** The group pressed; null closes the menu. */
  pick: Pick | null;
  onClose(): void;
  /** The row's conditions, described as the applied bar describes them. */
  conditions: readonly FilterSummaryItem[];
  /** Whether the records behind the group can be opened (`canDrill`). */
  canDrill: boolean;
  splits: readonly SplitOption[];
  onRecords(): void;
  onSplit(field: string): void;
  onFocus(): void;
}

/**
 * The follow-up menu on one group of an analysis result (D20 追问): see the
 * records behind it, ask the same question by another dimension, or narrow
 * the range to it. The first opens another view — which is why it wears a
 * "from" line and a way back — and the other two are edits to this one.
 *
 * It has no trigger of its own: a chart mark or a table row opens it and
 * hands over what to anchor to, so one menu serves every layout and every
 * chart family.
 */
export function DrillMenu({
  pick,
  onClose,
  conditions,
  canDrill,
  splits,
  onRecords,
  onSplit,
  onFocus,
}: DrillMenuProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // What the keyboard gets back when the menu closes: the row that opened
  // it, which is still where the user was. Kept in a ref rather than read
  // off `pick`, because by the time focus is restored the pick is already
  // gone. A mark pressed with a pointer leaves nothing focusable behind, and
  // then focus is not moved at all.
  const back = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (pick) back.current = focusable(pick.anchor);
  }, [pick]);
  return (
    <DropdownMenu
      open={pick !== null}
      onOpenChange={open => {
        if (!open) onClose();
      }}
    >
      {/* Base UI hangs a menu's node in its floating tree off the trigger,
          and a root without one is a root whose submenu registers as its
          sibling — opening 「split by」 would close the menu it opened from.
          The mark or the row is this menu's trigger and cannot be this
          element, so the element exists only to be that node: `hidden`, so
          it is not on the page for a pointer, a reader or the Tab order, and
          `finalFocus` says where focus really goes when the menu closes. */}
      <DropdownMenuTrigger hidden tabIndex={-1} />
      <DropdownMenuContent
        anchor={pick?.anchor ?? null}
        finalFocus={back}
        aria-label={messages.label('label.drill.menu')}
        data-slot="drill-menu"
        className="min-w-56"
      >
        <DropdownMenuGroup>
          {/* The group pressed, named by its conditions: what every item
              below is about. The label goes inside the menu group — that is
              what it labels, every item under it being about this one group,
              and a reader entering the group hears the conditions rather than
              nothing. Outside it Base UI has no group to label and throws. */}
          <DropdownMenuLabel data-slot="drill-group">
            {conditions
              .map(item => summaryText(item, messages, display))
              .join(' · ')}
          </DropdownMenuLabel>
          {canDrill && (
            <DropdownMenuItem onClick={onRecords}>
              <TableIcon />
              {messages.label('label.drill.records')}
            </DropdownMenuItem>
          )}
          {splits.length > 0 && (
            <DropdownMenuSub>
              <DropdownMenuSubTrigger>
                <ListTreeIcon />
                {messages.label('label.drill.split')}
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent>
                {splits.map(split => (
                  <DropdownMenuItem
                    key={split.field}
                    onClick={() => onSplit(split.field)}
                  >
                    {split.label}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          )}
          <DropdownMenuItem onClick={onFocus}>
            <CrosshairIcon />
            {messages.label('label.drill.focus')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * The anchor as something focus can go back to, or null: a table row takes
 * focus and a chart mark does not, and a point is not an element at all.
 */
function focusable(anchor: PickAnchor): HTMLElement | null {
  return anchor instanceof HTMLElement && anchor.tabIndex >= 0 ? anchor : null;
}

/** The point a pointer event happened at, as something a menu can anchor to. */
export function pointAnchor(event: {
  clientX: number;
  clientY: number;
}): PickAnchor {
  const { clientX, clientY } = event;
  return {
    getBoundingClientRect: () =>
      DOMRect.fromRect({ x: clientX, y: clientY, width: 0, height: 0 }),
  };
}
