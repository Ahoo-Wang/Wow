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
 * What a view manager's commands have produced, and the rules that decide
 * which outcome may take a row's one slot.
 *
 * Everything here is a pure function over one `Outcome` or over the map of
 * them: the hook holds that map in React state, but none of these rules needs
 * to know that, and each is a rule a test can put a question to directly.
 */

import {
  SYSTEM_INSTANCE_ID_PREFIX,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  type Issue,
} from '../../model/index.js';
import type {
  WriteHandle,
  WritePayload,
  WriteState,
} from '../../runtime/index.js';

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
 * The revision and the request id of a write that never left. A refusal is
 * recorded so the row can say why it did not happen, and its payload is the
 * intent rather than anything sent, so it quotes neither.
 */
export const UNSENT = '';

/**
 * One recorded outcome. The handle is the engine's, kept here rather than
 * handed out: a caller acts on the row it can see, and `retry`, `abandon` and
 * `resolveConflict` address the write for it.
 */
export interface Outcome {
  state: WriteState;
  /**
   * Absent when there is nothing left to replay: a command the engine refused
   * before dispatching, or a conflict it has already settled.
   */
  handle: WriteHandle | null;
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
 * A refusal, in the shape the row already renders. `ViewCommandError` means
 * nothing was sent, which is exactly a rejection with no outcome to recover.
 */
export function refused(payload: WritePayload, issue: Issue): WriteState {
  return { requestId: UNSENT, payload, kind: 'rejected', issue };
}

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
 * Whether the outcome a key holds refuses a new intent altogether.
 *
 * A row holds one outcome, so a new command for a key whose outcome is still
 * the engine's to answer for has nowhere to put its own: recording it would
 * drop the handle, and the write it addresses would be left in
 * `engine.pendingWrites()` with nothing on screen able to retry, overwrite or
 * abandon it. The engine refuses a second command against an `unknown`
 * outright; a `conflict` it would dispatch over, which is the same problem one
 * step later. A `rejected` outcome is a definite answer with nothing
 * outstanding, so design/management.md's "correct it and save again" goes through as
 * the new intent it is.
 */
export function blocks(outcome: Outcome | null): boolean {
  return (
    outcome?.handle != null &&
    (outcome.state.kind === 'unknown' || outcome.state.kind === 'conflict')
  );
}

/**
 * The handle a new command for this key would strand, or null when it strands
 * none.
 *
 * A `rejected` outcome that still holds a handle is the one unsettled outcome
 * a new command is allowed past ({@link blocks}), and that command is about to
 * take its slot. Abandoning this handle first is what keeps the write it
 * addresses from being left in `engine.pendingWrites()` with nothing on screen
 * able to reach it.
 */
export function strandedHandle(outcome: Outcome | null): WriteHandle | null {
  return outcome?.handle && outcome.state.kind === 'rejected'
    ? outcome.handle
    : null;
}

/**
 * Whether a refusal may be recorded over whatever the key holds.
 *
 * A refusal never left, so it has nothing to replay. Letting it take the place
 * of an outcome that still holds a handle would drop the only way to retry or
 * abandon that write — which is exactly what the engine refusing a second
 * command against an `unknown` outcome would otherwise do to it.
 */
export function mayRefuse(outcome: Outcome | null): boolean {
  return !outcome?.handle;
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
