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

import { useCallback, useState, type ReactNode } from 'react';
import type { WriteState } from '../runtime/index.js';
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

/**
 * What the guard reads off the open view. Both facts are losses, and they are
 * different ones: edits that were never sent, and a write whose result never
 * came back — leaving takes the first away and takes away the chance to
 * settle the second.
 */
export interface LeaveGuardState {
  dirty: boolean;
  write: WriteState | null;
}

export interface LeaveGuard {
  /** Runs `next` now, or after the user says it is all right to lose this. */
  request(next: () => void): void;
  /** Render this somewhere inside the surface; it is nothing until asked. */
  dialog: ReactNode;
}

export interface LeaveGuardOptions {
  /**
   * The wording in force around the workbench. The dialog is rendered by the
   * workbench, which sits *outside* the `ViewSurface` that carries the
   * provider, so without this a host's `messages` override reaches every
   * component inside the surface and not the one dialog that interrupts them.
   */
  messages?: ViewMessages;
  /**
   * Called on confirm, before the switch. Leaving disposes the runtime, and
   * an unsettled write outlives it inside the engine: the handle would point
   * at a runtime nobody can reach, and `engine.pendingWrites()` would hold it
   * for the rest of the session with nothing on screen able to retry,
   * overwrite or abandon it. A workbench passes `commands.abandon` here.
   */
  onLeave?(): void;
}

/** Whether anything would be lost by closing this view right now. */
function costly(state: LeaveGuardState | null): boolean {
  return state !== null && (state.dirty || state.write?.kind === 'unknown');
}

/**
 * The confirmation that stands between an open view and the next one.
 *
 * Opening another view releases this one's runtime, and a runtime is where
 * the unsaved draft lives — there is nowhere else it is kept. So leaving is
 * the deletion of work, asked about once, and a view with nothing to lose is
 * never asked about at all: a guard that interrupts every switch is one
 * people learn to dismiss without reading.
 */
export function useLeaveGuard(
  state: LeaveGuardState | null,
  { messages: wording, onLeave }: LeaveGuardOptions = {},
): LeaveGuard {
  const messages = useViewMessages(wording);
  // The continuation, held until it is answered. Stored inside an object so
  // the state setter does not take it for an updater function.
  const [held, setHeld] = useState<{ next: () => void } | null>(null);
  const blocking = costly(state);

  const request = useCallback(
    (next: () => void) => {
      if (blocking) setHeld({ next });
      else next();
    },
    [blocking],
  );

  const dialog = (
    <Dialog open={held !== null} onOpenChange={open => !open && setHeld(null)}>
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
          <Button
            variant="destructive"
            onClick={() => {
              // Settled before the switch, not after: `next` releases this
              // runtime, and the outcome would have nothing left to be an
              // outcome of.
              onLeave?.();
              held?.next();
              setHeld(null);
            }}
          >
            {messages.label('label.leave.leave')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  return { request, dialog };
}
