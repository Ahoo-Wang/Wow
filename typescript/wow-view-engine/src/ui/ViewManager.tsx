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
import { audienceOf, type ViewAudience } from '../model/index.js';
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
import { useViewMessages } from './MessagesProvider.js';
import { DialogContent } from './popups.js';
import { ViewManagerOutcome, ViewManagerRow } from './ViewManagerRow.js';

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

/** The order the manager shows the two groups in, as the sidebar does. */
const GROUPS: readonly ViewAudience[] = ['personal', 'shared'];

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
  const preferences = manager.outcomes.get(PREFERENCES_KEY);
  // Where a row's delete confirmation leaves focus. A confirmed delete takes
  // the row — and with it the Delete button the dialog would otherwise
  // return to — off the list, and focus on an element that has left the
  // document is focus on `<body>`: no keyboard position at all, with this
  // dialog still open around it. The heading is the one thing in here that
  // outlives every row, and it is where a reader would start again anyway.
  const heading = useRef<HTMLHeadingElement>(null);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          {/* `tabIndex={-1}` makes it a focus target without making it a tab
              stop: it is reachable when focus is *sent* here, and invisible
              to Tab, which is what a heading should be. */}
          <DialogTitle ref={heading} tabIndex={-1}>
            {messages.label('label.manage.heading')}
          </DialogTitle>
          <DialogDescription>
            {messages.label('label.manage.description')}
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

        <div data-slot="view-manager" className="flex flex-col gap-3">
          {GROUPS.map(audience => {
            const items = list.items.filter(
              item => audienceOf(item.scope) === audience,
            );
            return items.length === 0 ? null : (
              <div key={audience} className="flex flex-col gap-1">
                <span className="text-muted-foreground px-1 text-xs">
                  {messages.label(`label.scope.group.${audience}`)}
                </span>
                {items.map(item => (
                  <ViewManagerRow
                    key={item.id}
                    item={item}
                    manager={manager}
                    list={list}
                    openDirtyId={openDirtyId}
                    returnFocus={heading}
                  />
                ))}
              </div>
            );
          })}
        </div>
      </DialogContent>
    </Dialog>
  );
}
