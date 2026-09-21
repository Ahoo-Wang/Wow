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
  type ViewAudience,
  type ViewInstanceSummary,
  type ViewKind,
} from '../model/index.js';
import type { ViewListState } from '../react/index.js';
import { Badge } from './components/badge.js';
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
import { useViewMessages } from './MessagesProvider.js';
import { DropdownMenuContent } from './popups.js';

/** The order the switcher shows the two groups in, as the sidebar does. */
const GROUPS: readonly ViewAudience[] = ['personal', 'shared'];

export interface ViewSwitcherProps {
  /** The same list the sidebar draws, already narrowed to this kind. */
  list: ViewListState;
  /**
   * The kind this workbench draws, which is the face the trigger wears.
   * Taken from the workbench rather than from the current row so the trigger
   * still reads while the list is loading — the list is narrowed to this one
   * kind anyway, so the two can never disagree.
   */
  kind: ViewKind;
  /** The open view, checked in the menu; null while none is. */
  currentId: string | null;
  /** The open view's title, on the trigger. */
  currentTitle: string;
  onOpen(instanceId: string): void;
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
  onManage,
}: ViewSwitcherProps) {
  const messages = useViewMessages();
  const Kind = KIND_ICON[kind];

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
            // though pressing it would open that one.
            aria-label={messages.label('label.workbench.switch-view')}
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
            className="w-0 min-w-[calc(6em+3.25rem)] max-w-fit grow justify-start"
          />
        }
      >
        <Kind data-icon="inline-start" />
        {/* The same 6em floor `ViewHeader` puts under the heading, here
            keeping the label off the icons beside it once the trigger is at
            its own floor above. */}
        <span className="min-w-[6em] truncate">{currentTitle}</span>
        {/* The trigger's own foreground, not `muted`: an icon inside a
            button inherits the button's ink, and that grey measures 4.34:1
            on the card this bar sits on (`EditorBand` records the same call
            for the word beside its icon). */}
        <ChevronDownIcon />
      </DropdownMenuTrigger>

      <DropdownMenuContent className="min-w-56">
        {GROUPS.map(audience => {
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

        {onManage && (
          <>
            {/* Managing the list is not choosing from it, so it is set apart
                rather than added to the end of the views. */}
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuItem onClick={onManage}>
                <Settings2Icon />
                {messages.label('label.manage.open')}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * One view in the menu. It carries the two facts a row carries in the
 * sidebar and no others: what it is called, and whether it came with the
 * definition. The kind is on the trigger instead — the list is one kind.
 */
function SwitcherItem({ item }: { item: ViewInstanceSummary }) {
  const messages = useViewMessages();
  return (
    <DropdownMenuRadioItem value={item.id} closeOnClick>
      <span className="truncate">{item.title}</span>
      {isSystemScope(item.scope) && (
        <Badge variant="secondary" className="ml-auto mr-4">
          {messages.label('label.scope.tag.system')}
        </Badge>
      )}
    </DropdownMenuRadioItem>
  );
}
