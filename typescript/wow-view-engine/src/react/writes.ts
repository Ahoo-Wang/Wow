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
 * One vocabulary for what a write ended as.
 *
 * Two hooks ask the same four questions of a write — how does a thrown command
 * become an outcome, did it land, did it save the view, and what may a new
 * intent do to an outcome already on screen — and they used to answer them
 * twice, in two sets of words, for the one contract
 * [management.md#冲突与未知结果](../../docs/design/management.md) states. The
 * answers live here, once.
 *
 * The one thing the two genuinely differ on is how many outcomes they hold:
 * `useSaveCommands` speaks for one open runtime and holds its single outcome,
 * while `useViewManager` holds one per row. That difference is in the names —
 * {@link blocksNewIntent} is the runtime's rule, {@link holdsHandle},
 * {@link strandedHandle} and {@link mayReplace} are the keyed slot's — and
 * nowhere else.
 *
 * Everything here is pure and React-free, so each rule is a question a test
 * can put directly.
 */

import type { Issue, ViewInstance, ViewPreferences } from '../model/index.js';
import {
  isViewWriteError,
  type WriteAction,
  type WriteHandle,
  type WritePayload,
  type WriteState,
} from '../runtime/index.js';
import { toIssue } from './issues.js';

/**
 * The revision and the request id of a write that never left. A refusal is
 * recorded so the caller can say why it did not happen, and its payload is the
 * intent rather than anything sent, so it quotes neither.
 */
export const UNSENT = '';

/**
 * What a command ended as, and the address of the write behind it.
 *
 * The handle is absent when there is nothing left to replay: a command the
 * engine refused before dispatching, or a conflict it has already settled.
 */
export interface SettledWrite {
  state: WriteState;
  handle: WriteHandle | null;
}

/**
 * A refusal, in the shape the caller already renders. `ViewCommandError` means
 * nothing was sent, which is exactly a rejection with no outcome to recover.
 */
export function refused(payload: WritePayload, issue: Issue): WriteState {
  return { requestId: UNSENT, payload, kind: 'rejected', issue };
}

/**
 * What a command threw, as the outcome it is: a `ViewWriteError` carries its
 * own state and handle, and anything else never left, so it becomes a
 * rejection quoting the intent and the Issue {@link toIssue} made of it.
 */
export function settle(
  caught: unknown,
  code: string,
  intent: WritePayload,
): SettledWrite {
  if (isViewWriteError(caught))
    return { state: caught.state, handle: caught.handle };
  return { state: refused(intent, toIssue(caught, code)), handle: null };
}

/** What replaying a write answered: whether it landed, and what it made. */
export interface RecoveredWrite {
  /** The command is done: the outcome it was raised against is settled. */
  landed: boolean;
  /**
   * Whether it saved this view — a `create` or a `save` that reached the
   * store. That is what a "Saved" moment is about, and two other landings
   * are not it: a conflict resolved by `reload` takes the stored state and
   * drops the draft, so it writes nothing at all; and a recovered `rename` or
   * `delete` does write, but not the config on screen — announcing "View
   * saved" after a retried rename would tell the user their unsaved edits are
   * safe when they are not.
   */
  written: boolean;
  /** The instance a recovered create, save or rename produced, if any. */
  instance: ViewInstance | null;
}

/** Nothing was replayed: no target, or no outcome to recover through. */
export const UNRECOVERED: RecoveredWrite = {
  landed: false,
  written: false,
  instance: null,
};

/**
 * What a replay or a conflict choice resolved with, as a {@link
 * RecoveredWrite}. Reaching this at all means the command landed; preferences
 * resolve too, and carry no instance.
 */
export function recovered(
  result: ViewInstance | ViewPreferences | void,
  written: boolean,
): RecoveredWrite {
  return {
    landed: true,
    written,
    instance:
      typeof result === 'object' && result !== null && 'config' in result
        ? result
        : null,
  };
}

/**
 * Whether recovering this write saves the view itself. A `rename` and a
 * `delete` reach the store as much as a `save` does, but neither is the
 * config on screen: "View saved" after a retried rename says the edits are
 * safe when nothing of them has been written. A write the runtime no longer
 * holds — the outcome settled between the render and the click — answers no,
 * which is the quiet way to be wrong.
 */
export function savesView(action: WriteAction | undefined): boolean {
  return action === 'create' || action === 'save';
}

/**
 * Whether the one outcome an open runtime reports refuses a new write.
 *
 * Only `unknown` does: the request left and nothing came back, so a second
 * attempt might be a second write, and the engine refuses it outright. A
 * `conflict` and a `rejected` are definite answers, and what resolves them is
 * often a new intent — "Save my copy" after somebody else moved the baseline,
 * "correct it and save again" after a refusal — which this would disable.
 *
 * A row of the view manager holds one outcome per key rather than one
 * altogether, and a `conflict` costs it the handle it would need to answer
 * for; {@link holdsHandle} is that stricter rule.
 */
export function blocksNewIntent(state: WriteState | null | undefined): boolean {
  return state?.kind === 'unknown';
}

/**
 * Whether the outcome a key holds still addresses a write the engine will
 * answer for, and so refuses a new intent altogether.
 *
 * A row holds one outcome, so a new command for a key whose outcome is still
 * the engine's to answer for has nowhere to put its own: recording it would
 * drop the handle, and the write it addresses would be left in
 * `engine.pendingWrites()` with nothing on screen able to retry, overwrite or
 * abandon it. The engine refuses a second command against an `unknown`
 * outright; a `conflict` it would dispatch over, which is the same problem one
 * step later. A `rejected` outcome is a definite answer with nothing
 * outstanding, so management.md's "correct it and save again" goes through as
 * the new intent it is.
 */
export function holdsHandle(outcome: SettledWrite | null | undefined): boolean {
  return outcome?.handle != null && outcome.state.kind !== 'rejected';
}

/**
 * The handle a new command for this key would strand, or null when it strands
 * none.
 *
 * A `rejected` outcome that still holds a handle is the one unsettled outcome
 * a new command is allowed past ({@link holdsHandle}), and that command is
 * about to take its slot. Abandoning this handle first is what keeps the write
 * it addresses from being left in `engine.pendingWrites()` with nothing on
 * screen able to reach it.
 */
export function strandedHandle(
  outcome: SettledWrite | null | undefined,
): WriteHandle | null {
  return outcome?.handle && !holdsHandle(outcome) ? outcome.handle : null;
}

/**
 * Whether the slot is free for an outcome that has nothing to replay.
 *
 * A refusal never left, so letting it take the place of an outcome that still
 * holds a handle would drop the only way to retry or abandon that write —
 * which is exactly what the engine refusing a second command against an
 * `unknown` outcome would otherwise do to it.
 */
export function mayRefuse(existing: SettledWrite | null | undefined): boolean {
  return existing?.handle == null;
}

/**
 * Whether `incoming` may be recorded over whatever the key holds. An outcome
 * that carries its own handle always may — it is a write the store has, and
 * the engine only dispatched it because the slot was open to it. One that
 * carries none is a refusal, and {@link mayRefuse} decides.
 */
export function mayReplace(
  existing: SettledWrite | null | undefined,
  incoming: SettledWrite,
): boolean {
  return incoming.handle !== null || mayRefuse(existing);
}
