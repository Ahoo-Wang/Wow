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

import { audienceOf, type ViewInstanceSummary } from '../model/index.js';
import { Button } from './components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './components/dialog.js';
import { useViewMessages } from './MessagesProvider.js';
import { DialogContent } from './popups.js';

/**
 * Deleting says what it costs, and only what it costs: the base sentence
 * always, and the two that depend on this view only when they apply.
 *
 * Asked twice for the one delete that conflicted — the first confirmation was
 * about the view as it stood, and the view the conflict reports has changed
 * since, so the second is put with the server's copy in hand.
 */
export function DeleteDialog({
  open,
  onOpenChange,
  item,
  dirty,
  onConfirm,
}: {
  open: boolean;
  onOpenChange(open: boolean): void;
  item: ViewInstanceSummary;
  /** True when this is the open view and it has unsaved edits. */
  dirty: boolean;
  onConfirm(): void;
}) {
  const messages = useViewMessages();
  const consequences = [messages.label('label.delete.consequence')];
  if (audienceOf(item.scope) === 'shared')
    consequences.push(messages.label('label.delete.shared-consequence'));
  if (dirty)
    consequences.push(messages.label('label.delete.dirty-consequence'));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{messages.label('label.delete.confirm')}</DialogTitle>
          <DialogDescription>{consequences.join(' ')}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose render={<Button variant="outline" />}>
            {messages.label('label.delete.keep')}
          </DialogClose>
          <Button variant="destructive" onClick={onConfirm}>
            {messages.label('label.manage.delete')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
