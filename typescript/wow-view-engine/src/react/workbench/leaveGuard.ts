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

import { useCallback, useState } from 'react';
import type { WriteState } from '../../runtime/index.js';
import { blocksNewIntent } from '../writes.js';

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

/**
 * Leave protection with no dialog in it: the question, and the two answers.
 *
 * A host renders whatever it likes from `asking` — `/ui` renders
 * `LeaveDialog` — so the rule about *when* work would be lost lives in one
 * place and is not retold by every surface that has to obey it.
 */
export interface LeaveGuard {
  /** True while a switch is waiting for an answer. */
  asking: boolean;
  /** Runs `next` now, or after the user says it is all right to lose this. */
  request(next: () => void): void;
  /** Yes: settles the pending write, then runs what was held. */
  confirm(): void;
  /** No: drops what was held and stays where it is. */
  cancel(): void;
}

export interface LeaveGuardOptions {
  /**
   * Called on confirm, before the switch. Leaving disposes the runtime, and
   * an unsettled write outlives it inside the engine: the handle would point
   * at a runtime nobody can reach, and `engine.pendingWrites()` would hold it
   * for the rest of the session with nothing on screen able to retry,
   * overwrite or abandon it. `useWorkbench` passes `commands.abandon` here.
   */
  onLeave?(): void;
}

/**
 * Whether anything would be lost by closing this view right now.
 *
 * The second loss is `blocksNewIntent`'s rule, asked rather than restated: an
 * `unknown` is the outcome the engine will not let a new write past, and
 * leaving takes away the retry or the abandon that would settle it. A
 * `conflict` is not one of these — it is answered by a new intent, which is
 * exactly what leaving gives up on deliberately, and a `rejected` never left
 * at all.
 */
function costly(state: LeaveGuardState | null): boolean {
  return state !== null && (state.dirty || blocksNewIntent(state.write));
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
  { onLeave }: LeaveGuardOptions = {},
): LeaveGuard {
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

  return {
    asking: held !== null,
    request,
    confirm() {
      // Settled before the switch, not after: `next` releases this runtime,
      // and the outcome would have nothing left to be an outcome of.
      onLeave?.();
      held?.next();
      setHeld(null);
    },
    cancel() {
      setHeld(null);
    },
  };
}
