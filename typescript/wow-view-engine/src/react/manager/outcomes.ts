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

/**
 * What a view manager's commands have produced: one outcome per row, held in a
 * map. The rules that decide what a row's slot accepts are `react/writes.ts`'s,
 * asked from there directly — this is the map around them.
 *
 * Everything here is a pure function over one `Outcome` or over the map of
 * them: the hook holds that map in React state, but none of these rules needs
 * to know that, and each is a rule a test can put a question to directly.
 */

import {
  SYSTEM_INSTANCE_ID_PREFIX,
  SYSTEM_INSTANCE_ID_SEPARATOR,
} from '../../model/index.js';
import type { WriteState } from '../../runtime/index.js';
import type { SettledWrite } from '../writes.js';

/**
 * The key the order and the default view are recorded under.
 *
 * Both are one preference record, so both contend for one slot and share one
 * outcome: there is no instance to hang them off, and a row cannot be asked
 * to recover a write that was never about it.
 *
 * It shares the key space with instance ids, so it is taken from the one
 * namespace a `ViewStore` may not issue into — the `system:` prefix
 * `isSystemInstanceId` reserves — rather than from a bare word a store could
 * hand out as an id and collide with.
 */
export const PREFERENCES_KEY =
  `${SYSTEM_INSTANCE_ID_PREFIX}${SYSTEM_INSTANCE_ID_SEPARATOR}preferences` as const;

/**
 * One recorded outcome: what the command settled as, plus the intent a
 * preference write keeps across a reload. The handle is the engine's, kept
 * here rather than handed out — a caller acts on the row it can see, and
 * `retry`, `abandon` and `resolveConflict` address the write for it.
 */
export interface Outcome extends SettledWrite {
  /**
   * The command as the user meant it, for a preference write whose conflict
   * was answered with a reload. Running it again is a new write — it reads
   * the revision the reload brought in — so the intent survives the reload
   * without ever being replayed behind the user's back.
   */
  again?: () => Promise<unknown>;
}

export const NO_OUTCOMES: ReadonlyMap<string, Outcome> = new Map();

/**
 * Whether an outcome is a conflict the engine has already settled and whose
 * intent is still the user's to put again: after a reload there is no handle
 * to recover through, and the button offers the write once more rather than
 * a recovery that would answer `false`.
 */
export function kept(outcome: Outcome | undefined): boolean {
  return (
    outcome !== undefined &&
    outcome.handle === null &&
    outcome.again !== undefined &&
    outcome.state.kind === 'conflict'
  );
}

/**
 * The map with this key recorded or dropped, or null when it would not change
 * — dropping a key nothing is held under is the one no-op, and answering it
 * with a new map would re-render every row for nothing.
 */
export function withOutcome(
  outcomes: ReadonlyMap<string, Outcome>,
  key: string,
  outcome: Outcome | null,
): ReadonlyMap<string, Outcome> | null {
  if (outcome === null && !outcomes.has(key)) return null;
  const next = new Map(outcomes);
  if (outcome) next.set(key, outcome);
  else next.delete(key);
  return next;
}

/**
 * The outcomes as a caller reads them: the state alone, because the handle is
 * the engine's and the recovery actions address it on the row's behalf.
 */
export function projectStates(
  outcomes: ReadonlyMap<string, Outcome>,
): ReadonlyMap<string, WriteState> {
  const projected = new Map<string, WriteState>();
  for (const [key, outcome] of outcomes) projected.set(key, outcome.state);
  return projected;
}
