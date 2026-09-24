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

import { useRef } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { Accessibility } from '@dnd-kit/dom';
import {
  audienceOf,
  VIEW_AUDIENCES,
  type ViewAudience,
  type ViewInstanceSummary,
} from '../model/index.js';
import {
  PREFERENCES_KEY,
  type ViewListState,
  type ViewManagerController,
} from '../react/index.js';
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from './components/dialog.js';
import { manageDragAccessibility, managerDrop } from './manage/drag.js';
import { useViewMessages } from './MessagesProvider.js';
import { useAnnouncer } from './Announcer.js';
import { DialogContent } from './popups.js';
import {
  SortableViewManagerRow,
  ViewManagerOutcome,
  ViewManagerRow,
} from './ViewManagerRow.js';
import { TEXT_UI } from './layout.js';
import { cn } from 'cn';
import { useKindWord } from './kinds.js';

export interface ViewManagerProps {
  manager: ViewManagerController;
  list: ViewListState;
  open: boolean;
  onOpenChange(open: boolean): void;
  /**
   * The open view, when it has edits that were never saved. Deleting that one
   * takes them with it, which is worth saying in the confirmation and cannot
   * be worked out from the list.
   */
  openDirtyId?: string | null;
}

/**
 * One sortable group per audience, so a drag cannot cross one: a row of the
 * other group is not a drop target at all. Both lists draw personal views
 * above shared ones whatever order is stored, so carrying one across that
 * line would store a new order, spend a revision and move nothing anybody can
 * see — a view joins the other group by being saved into it, not by being
 * dragged there.
 */
const GROUP_ID: Record<ViewAudience, string> = {
  personal: 'views-personal',
  shared: 'views-shared',
};

/** The rows of one audience, in the order the dialog draws them. */
interface RowGroup {
  audience: ViewAudience;
  items: ViewInstanceSummary[];
}

/**
 * Managing the views rather than looking at one: rename, delete, reorder and
 * choose which one opens first.
 *
 * It is a dialog rather than a mode of the sidebar because none of it is
 * navigation — every button here writes. Each one exists only where it is
 * permitted: a row the user may not rename has no rename button, not a
 * greyed one, so what the list offers is exactly what the store will take.
 */
export function ViewManager({
  manager,
  list,
  open,
  onOpenChange,
  openDirtyId = null,
}: ViewManagerProps) {
  const messages = useViewMessages();
  const word = useKindWord();
  const { say: announce, region: announcement } = useAnnouncer(
    'view-manager-announcement',
  );
  const preferences = manager.outcomes.get(PREFERENCES_KEY);
  // Where a row's delete confirmation leaves focus. A confirmed delete takes
  // the row — and with it the Delete button the dialog would otherwise
  // return to — off the list, and focus on an element that has left the
  // document is focus on `<body>`: no keyboard position at all, with this
  // dialog still open around it. The heading is the one thing in here that
  // outlives every row, and it is where a reader would start again anyway.
  const heading = useRef<HTMLHeadingElement>(null);

  const groups: RowGroup[] = VIEW_AUDIENCES.map(audience => ({
    audience,
    items: list.items.filter(item => audienceOf(item.scope) === audience),
  })).filter(group => group.items.length > 0);
  const groupOf = (id: string) =>
    groups.find(group => group.items.some(item => item.id === id));
  const titleOf = (id: string) =>
    list.items.find(item => item.id === id)?.title ?? id;

  /**
   * Commits one move and says where the view landed, for both inputs.
   *
   * The place is announced only once the store has taken it. Nothing on
   * screen moves before that — the optimistic plugin is off and the list
   * catches up on the reload — so a write that was refused leaves the order
   * as it was, said once in the line above the rows rather than twice.
   */
  const move = (id: string, index: number) => {
    const group = groupOf(id);
    if (!group) return;
    const total = group.items.length;
    const to = Math.min(Math.max(index, 0), total - 1);
    void manager.moveTo(id, to).then(landed => {
      if (!landed) return;
      // Counted inside the group and from one, because that is the list the
      // user is looking at: "second of two personal views", never a place in
      // a flat order that no heading names.
      announce(
        messages.label('label.manage.moved', {
          title: titleOf(id),
          index: to + 1,
          total,
        }),
      );
    });
  };

  const rows = (
    <div data-slot="view-manager" className="flex flex-col gap-3">
      {groups.map(group => (
        <div
          key={group.audience}
          data-slot="view-manager-group"
          data-audience={group.audience}
          className="flex flex-col gap-1"
        >
          <span className={cn('text-muted-foreground px-1', TEXT_UI)}>
            {messages.label(word(`label.scope.group.${group.audience}`))}
          </span>
          {group.items.map((item, index) => {
            const shared = {
              item,
              manager,
              list,
              openDirtyId,
              returnFocus: heading,
              // Where the row is *now*, asked for as the key is pressed
              // rather than read off this render: a move that is queued but
              // has not landed is not on screen yet, and the second of two
              // quick presses would otherwise ask for the place the first
              // already gave the row.
              onMove: (step: -1 | 1) =>
                move(item.id, manager.placeOf(item.id) + step),
            };
            return manager.can.reorder ? (
              <SortableViewManagerRow
                key={item.id}
                {...shared}
                index={index}
                group={GROUP_ID[group.audience]}
              />
            ) : (
              <ViewManagerRow key={item.id} {...shared} />
            );
          })}
        </div>
      ))}
    </div>
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* Opening on the heading rather than on whatever is focusable first.
          That used to be a row's star — "Open this one first" — and is now
          its drag handle: either way the dialog opens with a write under the
          user's finger and no word about what they are looking at. The
          heading is where a reader starts, and Tab from it is the first row. */}
      <DialogContent className="sm:max-w-lg" initialFocus={heading}>
        <DialogHeader>
          {/* `tabIndex={-1}` makes it a focus target without making it a tab
              stop: it is reachable when focus is *sent* here, and invisible
              to Tab, which is what a heading should be. */}
          <DialogTitle ref={heading} tabIndex={-1}>
            {messages.label(word('label.manage.heading'))}
          </DialogTitle>
          <DialogDescription>
            {messages.label(word('label.manage.description'))}
          </DialogDescription>
        </DialogHeader>

        {/* The order and the default are one record, so their outcome belongs
            to the list rather than to any row that moved. */}
        {preferences && (
          <ViewManagerOutcome
            state={preferences}
            manager={manager}
            list={list}
            outcomeKey={PREFERENCES_KEY}
          />
        )}

        {manager.can.reorder ? (
          <>
            <DragDropProvider
              plugins={defaults =>
                defaults.map(plugin =>
                  plugin === Accessibility
                    ? Accessibility.configure(
                        manageDragAccessibility(messages, titleOf, word),
                      )
                    : plugin,
                )
              }
              onDragEnd={({ operation, canceled }) => {
                // The library holds a drag inside its own group; `managerDrop`
                // says the same thing where the order is decided, so a drop
                // the sensor let through cannot store one audience among the
                // other's rows.
                const drop = managerDrop(
                  operation,
                  canceled,
                  id => groupOf(id)?.audience,
                );
                // The place of the row it was dropped on, asked for now for
                // the same reason the arrow keys ask for it now.
                if (drop) move(drop.source, manager.placeOf(drop.target));
              }}
            >
              {rows}
            </DragDropProvider>

            {/* One voice for a move the user asked for with the arrow keys;
                the library announces its own pick-up and cancel. */}
            {announcement}
          </>
        ) : (
          rows
        )}
      </DialogContent>
    </Dialog>
  );
}
