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

import { useEffect, useRef } from 'react';

/** What the sync watches, and what it does about a disagreement. */
export interface InstanceSyncOptions {
  /**
   * The value the host holds, or `undefined` when it holds none.
   *
   * `undefined` is the uncontrolled form and the only thing that means it: a
   * host that passes `null` is holding a value, and the value is "whichever
   * view is the effective default".
   */
  instanceId?: string | null;
  /** The pin the workbench holds now — `useWorkbench`'s own `chosen`. */
  chosen: string | null;
  /** Opens another view, through the leave guard. */
  choose(id: string | null): void;
  /** True while the guard's question is still on screen. */
  asking: boolean;
  /** Told what is open, in the same vocabulary `instanceId` is written in. */
  onInstanceChange?(id: string | null): void;
}

/**
 * Keeps the host's value and the open view in agreement.
 *
 * `instanceId` is controlled in the sense `value` is on an input — the host
 * is the one that says which view is open — but a view is not a string: it
 * holds an unsaved draft, and swapping it out is a loss the leave guard has
 * to ask about first. So a pushed value cannot simply be rendered. The
 * workbench *converges* on it instead: whichever side moved last is the side
 * that speaks, and the other one follows.
 *
 * - the host named a view that is not open → it is opened, through the same
 *   guard a click on the sidebar goes through;
 * - the workbench moved on its own — a user's switch, the copy a save-as
 *   opened, a rename, a pin released with its deleted view — → the host is
 *   told, and its route follows;
 * - the guard refused the push and the user stayed → the host is told what is
 *   open, so its route is never left naming a view that is not on screen.
 *
 * What is reported is exactly what may be passed back in: `null` means the
 * effective default in the report as it does in the prop, so a host can store
 * one and hand it over on the next mount without translating it.
 *
 * Switching between the two forms mid-life is unsupported, as it is on an
 * input: the first value decides which side owns the open view.
 */
export function useInstanceSync({
  instanceId,
  chosen,
  choose,
  asking,
  onInstanceChange,
}: InstanceSyncOptions): void {
  /** The host's last value, so a *change* of it can be told from a rerender. */
  const seen = useRef(instanceId);
  /**
   * The last value the host and the workbench agreed on: what it pushed, or
   * what it was told. In the controlled form the prop says the first half and
   * this says the second, and they differ exactly while a report is owed.
   */
  const held = useRef(chosen);

  useEffect(() => {
    const previous = seen.current;
    seen.current = instanceId;
    // Nothing has settled while the question is still up: the answer decides
    // whether the pushed view opens or the old one stays.
    if (asking) return;

    const host = instanceId === undefined ? held.current : instanceId;
    if (host === chosen) {
      held.current = chosen;
      return;
    }
    // The host is the side that moved. Its own move is not reported back to
    // it, so `held` takes the pushed value before the guard sees it.
    if (instanceId !== undefined && instanceId !== previous) {
      held.current = instanceId;
      choose(instanceId);
      return;
    }
    // The workbench is the side that moved, or the push was refused. Either
    // way it is said once: a host that does not follow is not told twice.
    if (held.current === chosen) return;
    held.current = chosen;
    onInstanceChange?.(chosen);
  }, [asking, chosen, choose, instanceId, onInstanceChange]);
}
