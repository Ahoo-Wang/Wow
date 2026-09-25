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
  ArrowUpRightIcon,
  CrosshairIcon,
  FunnelIcon,
  ListTreeIcon,
  TableIcon,
} from 'lucide-react';
import { useCallback, useEffect, useId, useRef } from 'react';
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
import { useSurfaceAnnouncer } from '../Announcer.js';
import type { DisplayContext } from '../display.js';
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
  /**
   * The other end of a span (D33 Q52): the menu is about every bucket from
   * `row`'s through this one's, not about one group.
   */
  through?: RecordData;
}

/** A press as the menu holds it, from what `OnPick` hands over. */
export function pickOf(
  row: RecordData,
  anchor: PickAnchor,
  origin?: HTMLElement,
  through?: RecordData,
): Pick {
  return {
    row,
    anchor,
    ...(origin ? { origin } : {}),
    ...(through ? { through } : {}),
  };
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
  /**
   * What else the group is read under, said under it: a dashboard panel's
   * board filters as they reach it (D22 H, 「仓库 是 华南 · 本月」).
   */
  context?: string;
  /**
   * Whether every follow-up opens away from here — a dashboard's go to the
   * workbench through the host's route — which each item says with ↗.
   */
  away?: boolean;
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
export function DrillMenu({
  pick,
  onClose,
  followUp,
  context,
  away = false,
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
    if (pick) back.current = pick.origin ?? null;
  }, [pick]);
  // Only while focus is still the menu's to give. A menu hands focus back
  // once its exit is over, and a reader who picked a follow-up and went
  // straight on to another menu's trigger had that menu snatched shut under
  // them: focus landing on the row is focus leaving the menu just opened.
  // Base UI makes this check for a target it picks itself, not for one it is
  // handed, so it is made here. `null` leaves the choice to Base UI, as an
  // empty ref did.
  const menuId = useId();
  // Stable, as a popup's focus management re-arms on a new `finalFocus`.
  const giveBack = useCallback(() => {
    const target = back.current;
    const active = target?.ownerDocument.activeElement;
    const taken =
      !!active &&
      active !== active.ownerDocument.body &&
      active !== target &&
      active.closest('[data-drill-menu]')?.getAttribute('data-drill-menu') !==
        menuId;
    return taken ? false : target;
  }, [menuId]);
  // The group pressed, in words: the menu's heading, and the second half of
  // the name a view opened from it goes by.
  const group = (followUp?.groups ?? [])
    .map(entry => groupText(entry, messages, display))
    .join(' · ');
  const titled = (subject: string) =>
    messages.label('label.drill.titled', { subject, group });
  // A span is chosen by a drag nobody hears (D33 Q52): what it came to is
  // said once the menu over it opens, in the surface's one voice.
  const spanned = pick?.through !== undefined && followUp !== null;
  const { say, region } = useSurfaceAnnouncer('drill-announcement');
  useEffect(() => {
    if (spanned) say(messages.label('label.drill.spanned', { group }));
  }, [spanned, group, say, messages]);
  return (
    <>
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
          finalFocus={giveBack}
          data-drill-menu={menuId}
          aria-label={messages.label(
            spanned ? 'label.drill.menu-span' : 'label.drill.menu',
          )}
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
            <DropdownMenuLabel data-slot="drill-group">
              {group}
              {context && (
                <span
                  data-slot="drill-context"
                  className="text-muted-foreground block font-normal"
                >
                  {context}
                </span>
              )}
            </DropdownMenuLabel>
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
                  // Offered and greyed, with why (D38): the item stays in
                  // its place, so the menu reads the same over every result.
                  if (action.gap)
                    return (
                      <DropdownMenuItem
                        key="records"
                        disabled
                        data-slot="drill-records"
                        data-gap={action.gap}
                        aria-describedby={`${menuId}-gap`}
                      >
                        <TableIcon />
                        <span className="flex flex-col">
                          {messages.label('label.drill.records')}
                          <span
                            id={`${menuId}-gap`}
                            className="text-muted-foreground text-xs"
                          >
                            {messages.label(`label.drill.gap.${action.gap}`)}
                          </span>
                        </span>
                      </DropdownMenuItem>
                    );
                  return (
                    <DropdownMenuItem
                      key="records"
                      onClick={done(() => action.run(titled(action.subject)))}
                    >
                      <TableIcon />
                      {messages.label('label.drill.records')}
                      {away && <Away />}
                    </DropdownMenuItem>
                  );
                case 'split':
                  return (
                    <DropdownMenuSub key="split">
                      <DropdownMenuSubTrigger>
                        <ListTreeIcon />
                        {messages.label(
                          spanned
                            ? 'label.drill.split-span'
                            : 'label.drill.split',
                        )}
                      </DropdownMenuSubTrigger>
                      <DropdownMenuSubContent data-drill-menu={menuId}>
                        {action.options.map(option => (
                          <DropdownMenuItem
                            key={option.field}
                            onClick={done(() =>
                              action.run(option.field, titled(action.subject)),
                            )}
                          >
                            {option.label}
                            {away && <Away />}
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
                      {messages.label(
                        spanned
                          ? 'label.drill.focus-span'
                          : 'label.drill.focus',
                      )}
                      {away && <Away />}
                    </DropdownMenuItem>
                  );
                case 'filter':
                  // Here, not away: the board's own filter takes the span.
                  return (
                    <DropdownMenuItem
                      key={`filter:${action.filter}`}
                      data-slot="drill-set-filter"
                      onClick={done(() => action.run())}
                    >
                      <FunnelIcon />
                      {messages.label('label.drill.set-filter', {
                        filter: action.filter,
                      })}
                    </DropdownMenuItem>
                  );
              }
            })}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      {region}
    </>
  );
}

/**
 * ↗ at an item's end, and the words for whoever does not see it: the item
 * opens in the workbench rather than here (D22 H).
 */
function Away() {
  const messages = useViewMessages();
  return (
    <>
      <ArrowUpRightIcon data-slot="drill-away" className="ml-auto" />
      <span className="sr-only">{messages.label('label.drill.away')}</span>
    </>
  );
}

/**
 * One dimension of the group pressed, in the applied bar's words — the
 * words the view opened from it will say it in, so the menu's heading, that
 * view's title and its applied bar are one reading of one condition
 * (2026-09-23 review P2). A date bucket's condition is the range bounding
 * it, which the bar reads as the period it is — 「事件时间 在 2026年9月22日」,
 * printed as the axis and the table column print the bucket — rather than as
 * two instants to the millisecond (`label.filter.period`). A value is
 * 「仓库 是 华南」; no value at all is the bucket's sentinel, which is how a
 * bucket with no key reads too. Each is named by its field, as a condition
 * is: a dimension's own label (「日期」) is a column heading of this result,
 * and the view opened from it has no such column.
 *
 * A number band's conditions are two comparisons, `GTE` its key and `LT`
 * the key plus the interval, which the bar reads as the one segment they
 * bound, written as its column prints the band — 「单价 在 ¥0～500」
 * (`label.filter.segment`). So every dimension reads through the bar's own
 * sentence, and the two agree by construction.
 */
export function groupText(
  entry: FollowUpGroup,
  messages: MessageFormatters,
  display: DisplayContext,
): string {
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
