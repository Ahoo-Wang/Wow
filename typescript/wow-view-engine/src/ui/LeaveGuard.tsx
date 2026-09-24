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
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/alert-dialog.js';
import { useViewMessages } from './MessagesProvider.js';
import { AlertDialogContent } from './popups.js';
import { DestructiveAction } from './variants.js';
import { useKindWord } from './kinds.js';

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
 *
 * An `AlertDialog`, not a `Dialog`: the edits this asks about are lost for
 * good, so Base UI keeps it open under an outside click and holds focus on
 * the two answers — staying is a decision, not the absence of one.
 */
export function LeaveDialog({ leave, messages: wording }: LeaveDialogProps) {
  const messages = useViewMessages(wording);
  const word = useKindWord();
  return (
    <AlertDialog
      open={leave.asking}
      onOpenChange={open => !open && leave.cancel()}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {messages.label(word('label.leave.heading'))}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {messages.label('label.leave.consequence')}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.leave.stay')}
          </AlertDialogCancel>
          <DestructiveAction onClick={() => leave.confirm()}>
            {messages.label('label.leave.leave')}
          </DestructiveAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
