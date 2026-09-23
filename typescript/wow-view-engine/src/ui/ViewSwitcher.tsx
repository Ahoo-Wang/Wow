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

import { ChevronDownIcon, Settings2Icon } from 'lucide-react';
import {
  audienceOf,
  isSystemScope,
  VIEW_AUDIENCES,
  type ViewInstanceSummary,
  type ViewKind,
} from '../model/index.js';
import type { ViewListState } from '../react/index.js';
import { Button } from './components/button.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { KIND_ICON } from './kinds.js';
import { SystemMark } from './SystemMark.js';
import { useViewMessages } from './MessagesProvider.js';
import { DropdownMenuContent } from './popups.js';
import { NewViewItem, type NewViewCommand } from './workbench/NewView.js';

export interface ViewSwitcherProps {
  /** The same list the sidebar draws, already narrowed to the kinds drawn. */
  list: ViewListState;
  /**
   * The face the trigger wears: the open view's kind, or the one kind a
   * workbench draws while nothing is open yet. A workbench of several kinds
   * with nothing open has no face to show, and the trigger shows none.
   */
  kind?: ViewKind;
  /** The open view, checked in the menu; null while none is. */
  currentId: string | null;
  /**
   * The open view's title, on the trigger. Empty where there is no open view
   * to name — the screen that reports one which will not open, and the moment
   * before the first one arrives — and the trigger then says what it is for
   * instead of showing nothing.
   */
  currentTitle: string;
  onOpen(instanceId: string): void;
  /**
   * Makes a view from nothing: the sidebar's `+`, in the one control that
   * stands in for the sidebar. Absent when nothing is behind it (D4).
   */
  create?: NewViewCommand;
  /**
   * Opens the view manager. The item is absent when it is left out, which is
   * how a user with no write permission at all is spared an entry whose only
   * lesson is that it leads to a dialog of read-only rows.
   */
  onManage?(): void;
}

/**
 * The view list as one control, for when there is no room for the list.
 *
 * It is the sidebar's job done in a header's worth of space: the same views,
 * the same two groups in the same order, the same tag on the ones that ship
 * with the definition. Only one of them is ever on screen — collapsing the
 * sidebar is what puts this here — so nothing about which view is open is
 * ever said twice.
 *
 * Choosing goes out through the same `choose` the sidebar calls, so the
 * leave guard still asks before a draft is released: the shortcut is a
 * shorter way to the same door, not a way around it.
 */
export function ViewSwitcher({
  list,
  kind,
  currentId,
  currentTitle,
  onOpen,
  create,
  onManage,
}: ViewSwitcherProps) {
  const messages = useViewMessages();
  const Kind = kind === undefined ? null : KIND_ICON[kind];
  // With no view behind it the trigger used to be an icon, a chevron and a
  // gap where the name would have been — on the "cannot open" screen, where
  // it is the only way anywhere, that reads as a control that is broken too.
  // The placeholder is the button's name as well as its label: a button whose
  // visible word and accessible name differ is one that cannot be asked for
  // out loud (WCAG 2.5.3), and "Switch view" over an empty trigger names a
  // view that is not there and offers to leave it.
  const choosing = currentTitle === '';
  const name = messages.label(
    choosing ? 'label.workbench.choose-view' : 'label.workbench.switch-view',
  );

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            data-slot="view-switcher"
            variant="ghost"
            size="sm"
            // The title is the label, so the button needs a name for what it
            // *does* — a screen reader otherwise hears the view it is on as
            // though pressing it would open that one. With no title it is
            // the label, and the two are the same word.
            aria-label={name}
            // `max-w-fit` is where the taking stops, and it is the whole of
            // the 470px pill: `grow` alone stretched the trigger across the
            // group whatever it had to say, and the vendored button centres
            // its contents, so a short name floated in the middle of half a
            // title bar. Capped at `fit-content` the button is as wide as
            // the name it shows — no wider — and `justify-start` puts the
            // label where the icon ends, so a truncated name reads from its
            // beginning. A name longer than the bar still fills it, which is
            // the difference between a control at the size of its contents
            // and a control at the size of the room.
            //
            // The floor is on the button rather than on the label, because
            // `width: 0` is also what clamps this item's intrinsic
            // contribution: without it the label's `white-space: nowrap`
            // asks the group for the whole string however hidden the
            // overflow is (the same trap `ViewHeader` documents for
            // `truncate`), the identity group inherits that as its minimum
            // and overflows the bar at every width. With it, what the group
            // is told is exactly this `min-width` — so it has to be the
            // label's 6em plus the icon, the two gaps, the chevron and the
            // padding around them, which is what the `calc` says.
            //
            // `text-sm` is the second half of the collapsed path's two
            // levels, and it is a *size* rather than a colour or a shape,
            // so it belongs in `className` (D16-8). The registry's `sm`
            // button is set at `text-[0.8rem]`, which this package pins to
            // the 13px rung of its own scale — the rung of a sidebar item
            // and a group label. That is right for a control in a row of
            // controls and wrong for this one: with the list folded away
            // the switcher *is* the view's name, and at 13 it read a whole
            // step below the 14 the definition's title was at, so the two
            // halves of "Orders / Pending" came out one and a half levels
            // apart instead of two. 14/500 under the title's 16/600 is the
            // same pair the sidebar shows when it is open. `cn` drops the
            // vendored size along with it, so the pinning rule in
            // `styles.css` no longer matches this button.
            className="w-0 min-w-[calc(6em+3.25rem)] max-w-fit grow justify-start text-sm"
          />
        }
      >
        {Kind && <Kind data-icon="inline-start" />}
        {/* The same 6em floor `ViewHeader` puts under the heading, here
            keeping the label off the icons beside it once the trigger is at
            its own floor above. */}
        <span data-slot="view-switcher-label" className="min-w-[6em] truncate">
          {choosing ? name : currentTitle}
        </span>
        {/* The trigger's own foreground, not `muted`: an icon inside a
            button inherits the button's ink, and that grey measures 4.34:1
            on the card this bar sits on (`EditorBand` records the same call
            for the word beside its icon). */}
        <ChevronDownIcon />
      </DropdownMenuTrigger>

      <DropdownMenuContent className="min-w-56">
        {VIEW_AUDIENCES.map(audience => {
          const items = list.items.filter(
            item => audienceOf(item.scope) === audience,
          );
          return items.length === 0 ? null : (
            <DropdownMenuRadioGroup
              key={audience}
              value={currentId ?? ''}
              // Base UI types a radio group's value as `any`. Naming the
              // parameter's type is what keeps that `any` out of this file —
              // a runtime guard here would be a branch nothing can reach,
              // since the only values in the group are the ids put in above.
              onValueChange={(next: string) => onOpen(next)}
            >
              <DropdownMenuLabel>
                {messages.label(`label.scope.group.${audience}`)}
              </DropdownMenuLabel>
              {items.map(item => (
                <SwitcherItem key={item.id} item={item} />
              ))}
            </DropdownMenuRadioGroup>
          );
        })}

        {(create || onManage) && (
          <>
            {/* Making a view and managing the list are not choosing from it,
                so they are set apart rather than added to the end of the
                views — in the sidebar's order: add first, then manage. */}
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              {create && <NewViewItem command={create} />}
              {onManage && (
                <DropdownMenuItem onClick={onManage}>
                  <Settings2Icon />
                  {messages.label('label.manage.open')}
                </DropdownMenuItem>
              )}
            </DropdownMenuGroup>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * One view in the menu, carrying the facts a row carries in the sidebar and
 * drawn with the same marks, so the folded list reads as the open one: the
 * kind in front of the name, because one data definition holds record and
 * analysis views together and a name alone does not say which is which; and
 * the lock when the view came with the definition (`SystemMark`).
 *
 * The lock stands clear of the radio's check at the item's end (`mr-4`) —
 * the open view's check is drawn there by the vendored item.
 */
function SwitcherItem({ item }: { item: ViewInstanceSummary }) {
  const Kind = KIND_ICON[item.kind];
  return (
    <DropdownMenuRadioItem value={item.id} closeOnClick>
      <Kind aria-hidden />
      <span className="truncate">{item.title}</span>
      {isSystemScope(item.scope) && <SystemMark className="mr-4 ml-auto" />}
    </DropdownMenuRadioItem>
  );
}
