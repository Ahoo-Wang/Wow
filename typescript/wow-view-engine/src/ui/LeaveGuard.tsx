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

import type { LeaveGuard } from '../react/index.js';
import type { ViewMessages } from './messages.js';
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

export interface LeaveDialogProps {
  /** The headless guard from `useWorkbench`; this only draws its question. */
  leave: LeaveGuard;
  /**
   * The wording in force around the workbench. The dialog is rendered by the
   * shell *outside* the `ViewSurface` that carries the provider, so without
   * this a host's `messages` override reaches every component inside the
   * surface and not the one dialog that interrupts them.
   */
  messages?: ViewMessages;
}

/**
 * The confirmation that stands between an open view and the next one.
 *
 * Nothing is decided here: `useLeaveGuard` already knows whether anything
 * would be lost, and this renders the two answers when it asks. A view with
 * nothing to lose never reaches this component at all — a guard that
 * interrupts every switch is one people learn to dismiss without reading.
 */
export function LeaveDialog({ leave, messages: wording }: LeaveDialogProps) {
  const messages = useViewMessages(wording);
  return (
    <Dialog open={leave.asking} onOpenChange={open => !open && leave.cancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{messages.label('label.leave.heading')}</DialogTitle>
          <DialogDescription>
            {messages.label('label.leave.consequence')}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose render={<Button variant="outline" />}>
            {messages.label('label.leave.stay')}
          </DialogClose>
          <Button variant="destructive" onClick={() => leave.confirm()}>
            {messages.label('label.leave.leave')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
