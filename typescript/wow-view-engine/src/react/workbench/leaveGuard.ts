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

import { useCallback, useEffect, useState } from 'react';
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
  /**
   * Whether closing the tab or navigating away is guarded too, on top of the
   * switch this hook asks about itself. On by default, and only a host that
   * owns the whole page should be turning it off: the prompt belongs to the
   * document, so a workbench that puts it up is speaking for every other
   * thing on that page (D18 Ⅸ).
   */
  guardUnload?: boolean;
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
 * The same question asked of the browser: closing the tab, going back, or
 * following a link off the page takes the draft with it, and none of that
 * goes through `request` — nothing in this package is even told it happened.
 * So while there is something to lose the page says so the one way a page
 * can, and while there is not it says nothing at all: a tab that argues
 * about being closed every time is a tab people learn to close twice.
 *
 * What it asks with is not ours to choose. Every browser shows its own
 * sentence and has ignored a custom one for a decade, so the handler carries
 * no wording — the wording that matters is `LeaveDialog`'s, which is the
 * question we *can* phrase, and this is the fallback for the exits that never
 * reach it.
 *
 * `window` is checked for, not assumed: `/react` renders on a server too, and
 * a hook that reached for a global there would take the whole page down (see
 * `environment.ts`, which answers page visibility the same way).
 */
function useUnloadGuard(blocking: boolean): void {
  useEffect(() => {
    if (!blocking || typeof window === 'undefined') return;
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Legacy, and still what Chrome and Edge before 119 read. The value
      // itself is never shown; being non-empty is the whole of its meaning.
      event.returnValue = true;
    };
    window.addEventListener('beforeunload', ask);
    return () => {
      window.removeEventListener('beforeunload', ask);
    };
  }, [blocking]);
}

/**
 * The confirmation that stands between an open view and the next one.
 *
 * Opening another view releases this one's runtime, and a runtime is where
 * the unsaved draft lives — there is nowhere else it is kept. So leaving is
 * the deletion of work, asked about once, and a view with nothing to lose is
 * never asked about at all: a guard that interrupts every switch is one
 * people learn to dismiss without reading.
 *
 * The switch inside the page and the way out of the page are the same rule,
 * so they are read off the same `blocking`: the sidebar, the switcher and a
 * pushed `instanceId` come through `request`, and the tab's own close button
 * comes through `beforeunload` (D18 Ⅸ). An embedded view has neither — it
 * has no editor, no draft and no save, and `EmbeddedView` never calls this
 * hook — so a business page that shows one is never argued with on the way
 * out.
 */
export function useLeaveGuard(
  state: LeaveGuardState | null,
  { onLeave, guardUnload = true }: LeaveGuardOptions = {},
): LeaveGuard {
  // The continuation, held until it is answered. Stored inside an object so
  // the state setter does not take it for an updater function.
  const [held, setHeld] = useState<{ next: () => void } | null>(null);
  const blocking = costly(state);
  useUnloadGuard(guardUnload && blocking);

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
