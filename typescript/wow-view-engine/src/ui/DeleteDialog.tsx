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

import type { RefObject } from 'react';
import { audienceOf, type ViewInstanceSummary } from '../model/index.js';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/alert-dialog.js';
import { useViewMessages } from './MessagesProvider.js';
import { AlertDialogContent } from './popups.js';

/**
 * Deleting says what it costs, and only what it costs: the base sentence
 * always, and the two that depend on this view only when they apply.
 *
 * Asked twice for the one delete that conflicted — the first confirmation was
 * about the view as it stood, and the view the conflict reports has changed
 * since, so the second is put with the server's copy in hand.
 *
 * An `AlertDialog`, not a `Dialog`: a delete is not something to click past.
 * Base UI keeps this one open under an outside click and holds focus on the
 * two answers, so the only way out is to pick one.
 */
export function DeleteDialog({
  open,
  onOpenChange,
  item,
  dirty,
  finalFocus,
  onConfirm,
}: {
  open: boolean;
  onOpenChange(open: boolean): void;
  item: ViewInstanceSummary;
  /** True when this is the open view and it has unsaved edits. */
  dirty: boolean;
  /**
   * Where focus goes once this closes. There is nowhere to send it back to
   * by default: the button that opened it belongs to a row that a confirmed
   * delete takes off the list, and a dialog that returns focus to an element
   * no longer in the document leaves it on `<body>` — no keyboard position
   * at all, and the manager still open around it. The caller names something
   * that outlives the row.
   */
  finalFocus?: RefObject<HTMLElement | null>;
  onConfirm(): void;
}) {
  const messages = useViewMessages();
  const consequences = [messages.label('label.delete.consequence')];
  if (audienceOf(item.scope) === 'shared')
    consequences.push(messages.label('label.delete.shared-consequence'));
  if (dirty)
    consequences.push(messages.label('label.delete.dirty-consequence'));

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent finalFocus={finalFocus}>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {messages.label('label.delete.confirm')}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {consequences.join(' ')}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.delete.keep')}
          </AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm}>
            {messages.label('label.manage.delete')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
