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
import type { RecordData } from '../../model/index.js';
import type { FollowUp, FollowUpGroup } from '../../react/index.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { columnTitle, displayValue, type DisplayContext } from '../display.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { DropdownMenuContent, DropdownMenuSubContent } from '../popups.js';
import { summaryText } from '../summary.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** Where a menu is placed: the mark or row pressed, or the point it was pressed at. */
export type PickAnchor = Element | { getBoundingClientRect(): DOMRect };

/** One group of the result the user pressed, and where. */
export interface Pick {
  row: RecordData;
  /** What the menu hangs from: the cell or the point pressed, or a mark. */
  anchor: PickAnchor;
  /** Where the keyboard goes back to on close: the row, when it was one. */
  origin?: HTMLElement;
}

export interface DrillMenuProps {
  /** The group pressed; null closes the menu. */
  pick: Pick | null;
  onClose(): void;
  /**
   * What the menu offers on that group (`useAnalysisResult`): the group's
   * conditions and the follow-ups, in order. Null while nothing is pressed.
   */
  followUp: FollowUp | null;
}

/**
 * The follow-up menu on one group of an analysis result (D20 追问): see the
 * records behind it, ask the same question by another dimension, or ask it
 * of this group alone. Each opens a view of its own beside this one — which
 * is why each wears a way back, and is named by what it is, 「{what} · {the
 * group}」 — and none edits this one.
 *
 * It has no trigger of its own: a chart mark or a table row opens it and
 * hands over what to anchor to, so one menu serves every layout and every
 * chart family.
 */
export function DrillMenu({ pick, onClose, followUp }: DrillMenuProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // What the keyboard gets back when the menu closes: the row that opened
  // it, which is still where the user was. Kept in a ref rather than read
  // off `pick`, because by the time focus is restored the pick is already
  // gone. A mark pressed with a pointer leaves nothing focusable behind, and
  // then focus is not moved at all.
  const back = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (pick) back.current = pick.origin ?? null;
  }, [pick]);
  // The group pressed, in words: the menu's heading, and the second half of
  // the name a view opened from it goes by.
  const group = (followUp?.groups ?? [])
    .map(entry => groupText(entry, messages, display))
    .join(' · ');
  const titled = (subject: string) =>
    messages.label('label.drill.titled', { subject, group });
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
        // As wide as its words, as the registry's own menus are sized (`w-auto
        // min-w-56`), not as its anchor: the popup's recipe takes
        // `--anchor-width`, which is right for a trigger it drops from and
        // wrong for a table row it used to hang from — the menu came out as
        // wide as the table. Capped, so a long condition in its heading
        // wraps rather than stretching it back.
        className="w-auto min-w-56 max-w-80"
      >
        <DropdownMenuGroup>
          {/* The group pressed, named by its conditions: what every item
              below is about. The label goes inside the menu group — that is
              what it labels, every item under it being about this one group,
              and a reader entering the group hears the conditions rather than
              nothing. Outside it Base UI has no group to label and throws. */}
          <DropdownMenuLabel data-slot="drill-group">{group}</DropdownMenuLabel>
          {followUp?.actions.map(action => {
            // Every follow-up is run, then the menu goes: it is about a
            // group of a result that the action is about to replace.
            const done =
              <T extends unknown[]>(run: (...args: T) => void) =>
              (...args: T) => {
                run(...args);
                onClose();
              };
            switch (action.kind) {
              case 'records':
                return (
                  <DropdownMenuItem
                    key="records"
                    onClick={done(() => action.run(titled(action.subject)))}
                  >
                    <TableIcon />
                    {messages.label('label.drill.records')}
                  </DropdownMenuItem>
                );
              case 'split':
                return (
                  <DropdownMenuSub key="split">
                    <DropdownMenuSubTrigger>
                      <ListTreeIcon />
                      {messages.label('label.drill.split')}
                    </DropdownMenuSubTrigger>
                    <DropdownMenuSubContent>
                      {action.options.map(option => (
                        <DropdownMenuItem
                          key={option.field}
                          onClick={done(() =>
                            action.run(option.field, titled(action.subject)),
                          )}
                        >
                          {option.label}
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuSubContent>
                  </DropdownMenuSub>
                );
              case 'focus':
                return (
                  <DropdownMenuItem
                    key="focus"
                    onClick={done(() => action.run(titled(action.subject)))}
                  >
                    <CrosshairIcon />
                    {messages.label('label.drill.focus')}
                  </DropdownMenuItem>
                );
            }
          })}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * One dimension of the group pressed, as the result reads it (2026-09-23
 * audit). A date bucket is its value the way its axis and its table column
 * print it — 「创建时间 在 2026年9月」 — through the table's own
 * `displayValue`: its conditions are the two instants bounding the bucket,
 * a long range nobody pressed. A week says it is one, since its value is
 * only the day it starts. Every other dimension is its conditions in the
 * applied bar's words: a value (「仓库 属于 华南」), a band of numbers, or no
 * value at all — the bucket's sentinel, which is how a date bucket with no
 * key reads too.
 */
export function groupText(
  entry: FollowUpGroup,
  messages: MessageFormatters,
  display: DisplayContext,
): string {
  const { column } = entry;
  const bucket =
    column?.dateUnit === undefined
      ? undefined
      : displayValue(entry.value, column, display);
  if (column !== undefined && bucket !== undefined)
    return messages.label(
      column.dateUnit === 'WEEK'
        ? 'label.drill.bucket-week'
        : 'label.drill.bucket',
      // The field, not the column: the column's title carries its
      // granularity (「创建时间（按月）」), which the bucket already says.
      {
        field: columnTitle({ ...column, dateUnit: undefined }, messages),
        bucket,
      },
    );
  return entry.conditions
    .map(item => summaryText(item, messages, display))
    .join(' · ');
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
