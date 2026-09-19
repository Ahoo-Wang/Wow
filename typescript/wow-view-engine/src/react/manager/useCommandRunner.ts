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
 * The protocol every view-manager command runs under: one outcome per key,
 * one write in flight, and both tagged with the inputs they answer for.
 *
 * It is the React half of the split — `queue.ts` serializes, `react/writes.ts`
 * decides what a slot accepts and `outcomes.ts` is the map of slots; this
 * holds the state those rules are asked about and turns a command into an
 * outcome the row can render.
 */

import { useCallback, useRef, useState } from 'react';
import type { ViewEngine, WritePayload } from '../../runtime/index.js';
import type { ViewListState } from '../useViewList.js';
import { holdsHandle, mayReplace, settle, strandedHandle } from '../writes.js';
import { NO_OUTCOMES, withOutcome, type Outcome } from './outcomes.js';
import { createCommandQueue, enqueue, type CommandQueue } from './queue.js';

/**
 * The inputs a command, an outcome and a queued order all answer for.
 *
 * The tag is the derivation `useViewList` makes — "which request does this
 * answer belong to", asked of a command rather than of a load. A workbench
 * swaps `definitionId` or `engine` while a write is in flight, and without
 * the tag the rows of the definition just left keep their outcomes and their
 * progress against a list that no longer holds them, and a completion from
 * the old inputs repopulates the new one.
 */
export interface ManagerTag {
  /** Null only before the first command; nothing is tagged with it. */
  engine: ViewEngine | null;
  definitionId: string;
}

export function sameTag(one: ManagerTag, other: ManagerTag): boolean {
  return one.engine === other.engine && one.definitionId === other.definitionId;
}

/** What the commands have produced, tagged with the inputs they were raised under. */
interface RunnerState extends ManagerTag {
  outcomes: ReadonlyMap<string, Outcome>;
  /** The key of the one write in flight; see `CommandRunner.pending`. */
  pending: string | null;
}

/** State belonging to no inputs, which is also how other inputs' state reads. */
const NOTHING_RUN: RunnerState = {
  engine: null,
  definitionId: '',
  outcomes: NO_OUTCOMES,
  pending: null,
};

/**
 * What a command tells the runner about itself, beyond what it writes.
 *
 * Both halves arrived with a different question — one keeps an intent alive
 * across a reload, the other decides whether an unsettled key accepts this
 * command at all — and they are independent: `resubmit` carries an intent and
 * is still an ordinary write, so the `unknown` guard applies to it.
 */
export interface RunOptions {
  /**
   * Kept with the outcome so the same intent can be put again later. Only
   * the preference commands pass one: everything else recovers through its
   * handle, which addresses the write the store already has.
   */
  again?: () => Promise<unknown>;
  /** A replay or a conflict choice, which is the one thing an unsettled outcome accepts. */
  recovery?: boolean;
  /**
   * Asked once more at the front of the queue, for a command that addresses
   * a handle it read when it was queued. The command ahead may have settled
   * that outcome — retried it, abandoned it, answered its conflict — and the
   * engine would refuse the second address with an error the row would show
   * as a failure of the click the user just made. False means "nothing to do
   * any more": the command is skipped and resolves `false`.
   */
  guard?: () => boolean;
}

export interface CommandRunner {
  /**
   * Runs one command, resolving whether it landed rather than rejecting: what
   * happened is recorded under `key`, so a click handler needs no try/catch.
   */
  run(
    key: string,
    intent: WritePayload,
    code: string,
    command: () => Promise<unknown>,
    options?: RunOptions,
  ): Promise<boolean>;
  /** Unresolved outcomes raised under these inputs, by key. */
  outcomes: ReadonlyMap<string, Outcome>;
  /** The key of the write in flight, or null. */
  pending: string | null;
  /**
   * The outcome a command would be landing on, or null when the state on
   * hand was raised under other inputs and says nothing about this key. It is
   * read between renders, so a queued command sees what the one ahead of it
   * recorded.
   */
  held(key: string): Outcome | null;
  /** Records an outcome under a key, or drops the one it holds. */
  record(key: string, outcome: Outcome | null): void;
  /** Whether something raised elsewhere was raised under these same inputs. */
  owns(tagged: ManagerTag): boolean;
}

/**
 * One write at a time against one engine and definition, with what each
 * command produced kept under the key of the row that raised it.
 */
export function useCommandRunner(
  engine: ViewEngine,
  definitionId: string,
  reload: ViewListState['reload'],
): CommandRunner {
  // Kept here rather than read back from `engine.pendingWrites()`: that map is
  // keyed by request id and says nothing about which row raised a write, and
  // the error each command rejects with already carries both halves.
  const [state, setState] = useState<RunnerState>(NOTHING_RUN);
  // The same state, readable between renders. A queued command decides
  // whether it may run at all from what the command before it recorded, and
  // no render has necessarily happened in between.
  const live = useRef<RunnerState>(NOTHING_RUN);
  // The write in flight, so the next one waits for it rather than racing it.
  const queue = useRef<CommandQueue<ManagerTag> | null>(null);

  const owns = useCallback(
    (tagged: ManagerTag): boolean => sameTag(tagged, { engine, definitionId }),
    [definitionId, engine],
  );

  // State raised under other inputs is about rows this render does not list,
  // so it reads as nothing rather than being shown against these ones.
  const own = owns(state) ? state : NOTHING_RUN;

  const commit = useCallback((next: RunnerState): void => {
    live.current = next;
    setState(next);
  }, []);

  const record = useCallback(
    (key: string, outcome: Outcome | null): void => {
      const current = live.current;
      // A completion from inputs the hook has moved on from answers for a
      // row the list no longer holds; it must not land on the new one.
      if (!owns(current)) return;
      const next = withOutcome(current.outcomes, key, outcome);
      if (next === null) return;
      commit({ ...current, outcomes: next });
    },
    [commit, owns],
  );

  const held = useCallback(
    (key: string): Outcome | null => {
      const current = live.current;
      return owns(current) ? (current.outcomes.get(key) ?? null) : null;
    },
    [owns],
  );

  const execute = useCallback(
    async (
      key: string,
      intent: WritePayload,
      code: string,
      command: () => Promise<unknown>,
      again?: () => Promise<unknown>,
      /** True for a replay or a conflict choice; see `RunOptions.recovery`. */
      recovery = false,
    ): Promise<boolean> => {
      // This command is about to take the slot of whatever the key holds, so
      // a write that would be left unreachable by that is settled first; see
      // `strandedHandle`. A recovery is not a new intent — it is that very
      // handle being used.
      const stranded = recovery ? null : strandedHandle(held(key));
      if (stranded) {
        try {
          engine.abandonWrite(stranded);
        } catch {
          // Already settled, which is the state this wanted it in.
        }
      }
      // Claiming the slot also tags it: a command under new inputs starts
      // from nothing rather than inheriting the outcomes of the old ones.
      const current = live.current;
      commit(
        owns(current)
          ? { ...current, pending: key }
          : { engine, definitionId, outcomes: NO_OUTCOMES, pending: key },
      );
      try {
        await command();
        // It landed: nothing is left to recover, and the list it changed —
        // the titles, the order, the default — is now a revision behind. A
        // delete also takes its row with it: the reload keeps what is on hand
        // on screen until the store answers, so the id goes with the request
        // rather than being listed, and named as the default, a moment longer
        // than it exists.
        record(key, null);
        reload(intent.action === 'delete' ? { without: intent.id } : undefined);
        return true;
      } catch (caught) {
        // A refusal never left, and it may not displace a handle; see
        // `mayReplace`. The intent still rides along either way: a refusal is
        // the user's command all the same, and `resubmit` is how a preference
        // write is put again.
        const outcome = settle(caught, code, intent);
        if (mayReplace(held(key), outcome)) record(key, { ...outcome, again });
        return false;
      } finally {
        const settled = live.current;
        if (owns(settled) && settled.pending === key)
          commit({ ...settled, pending: null });
      }
    },
    [commit, definitionId, engine, held, owns, record, reload],
  );

  const run = useCallback(
    (
      key: string,
      intent: WritePayload,
      code: string,
      command: () => Promise<unknown>,
      { again, recovery = false, guard }: RunOptions = {},
    ): Promise<boolean> => {
      // A recovery addresses the outcome that is in the way; every other
      // command waits for it to be settled, as `holdsHandle` explains.
      const blocked = () => !recovery && holdsHandle(held(key));
      if (blocked()) return Promise.resolve(false);
      // Asked again at the front of the queue: the command ahead may be the
      // one that turns this key `unknown`, or that settles the very handle a
      // recovery was queued to address.
      const start = () =>
        blocked() || (guard !== undefined && !guard())
          ? Promise.resolve(false)
          : execute(key, intent, code, command, again, recovery);
      // Created on the first command rather than per render, and never read
      // during one: it is a mutable holder, not state anything renders from.
      const commands = (queue.current ??= createCommandQueue(sameTag));
      return enqueue(commands, { engine, definitionId }, start);
    },
    [definitionId, engine, execute, held],
  );

  return {
    run,
    outcomes: own.outcomes,
    pending: own.pending,
    held,
    record,
    owns,
  };
}
