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

import { useCallback, useMemo, useRef } from 'react';
import {
  audienceOf,
  type ViewAudience,
  type ViewPreferences,
} from '../model/index.js';
import type {
  ConflictChoice,
  ViewEngine,
  WritePayload,
  WriteState,
} from '../runtime/index.js';
import { abilitiesOf, type ViewManagerAbilities } from './manager/abilities.js';
import { kept, PREFERENCES_KEY, projectStates } from './manager/outcomes.js';
import { UNSENT } from './writes.js';
import {
  groupIndexOf,
  planMoveTo,
  sameOrder,
  type OptimisticOrder,
} from './manager/order.js';
import {
  useCommandRunner,
  type ManagerTag,
} from './manager/useCommandRunner.js';
import type { ViewListState } from './useViewList.js';

export { PREFERENCES_KEY } from './manager/outcomes.js';
export type {
  ManagedInstanceAbilities,
  ViewManagerAbilities,
} from './manager/abilities.js';

export interface ViewManagerController {
  rename(id: string, title: string): Promise<boolean>;
  delete(id: string): Promise<boolean>;
  setDefault(id: string | null): Promise<boolean>;
  /**
   * Puts a view at one place inside its own audience group, counted over the
   * rows the list shows. The order is one list, but it is read as two —
   * personal above shared — so a move across that boundary is a write with
   * nothing to show for it, and there is no index that expresses one.
   *
   * It resolves false without writing when there is nothing to do: a row the
   * list no longer holds, or one already at that place.
   */
  moveTo(id: string, index: number): Promise<boolean>;
  /**
   * Where a row sits inside its own group, which is the index {@link moveTo}
   * is expressed in — a drag reads it off the row it was dropped on, the
   * arrow keys off the row itself. -1 when the list does not hold the row.
   *
   * **Ask it as the move is made, not while rendering.** A move that is
   * queued but has not landed is not in the list yet, and this answers for
   * the order that move submitted — which is the one the next move has to be
   * expressed in, and which no render can see.
   */
  placeOf(id: string): number;
  /**
   * Unresolved outcomes, by instance id or {@link PREFERENCES_KEY}. Empty
   * again once `definitionId` or `engine` changes: they answer for the rows
   * of the definition they were raised under, not for whichever list is on
   * screen now.
   */
  outcomes: ReadonlyMap<string, WriteState>;
  retry(key: string): Promise<boolean>;
  abandon(key: string): void;
  resolveConflict(key: string, choice: ConflictChoice): Promise<boolean>;
  /**
   * Puts the intent that conflicted to the store once more, as a new write
   * against the revision just reloaded — the second half of how design/management.md
   * settles a preference conflict: reload, keep the intent, put it to the
   * user again. It is not a replay of the old write, which would carry the
   * revision that lost, nor of the whole record, which would carry the other
   * half of the preferences as they were read before the reload.
   */
  resubmit(key: string): Promise<boolean>;
  /** Whether {@link resubmit} has an intent to put again under this key. */
  canResubmit(key: string): boolean;
  /**
   * The key of the write in flight, so one row alone shows progress. Commands
   * run one at a time, so there is never a second write for this one slot to
   * be wrong about.
   */
  pending: string | null;
  can: ViewManagerAbilities;
}

/** A queued move's orders, with the inputs it was computed under. */
type PendingOrder = OptimisticOrder & ManagerTag;

/**
 * Managing the views a list shows, rather than the one that is open: rename,
 * delete, reorder and choose a default, with the recovery actions for writes
 * that no runtime owns.
 *
 * Every command resolves rather than rejects, as `useSaveCommands` does: what
 * happened lands in `outcomes` under the row it belongs to, so a click handler
 * needs no try/catch and an unresolved write stays visible until the user
 * retries, overwrites or abandons it. A write that lands reloads the list,
 * because it is the list that changed.
 *
 * This is the composition alone. The rules the commands are decided by live
 * beside it: `react/writes.ts` is the one write-outcome vocabulary this hook
 * and `useSaveCommands` share, `manager/outcomes.ts` holds the map of slots
 * those rules are asked about,
 * `manager/queue.ts` serializes, `manager/order.ts` does the arithmetic of a
 * move, and `manager/useCommandRunner.ts` is the protocol all five commands
 * and the recovery actions run under.
 */
export function useViewManager(
  engine: ViewEngine,
  definitionId: string,
  list: ViewListState,
): ViewManagerController {
  const { all, items, permissions, preferences, reload } = list;
  const { run, outcomes, pending, held, record, owns } = useCommandRunner(
    engine,
    definitionId,
    reload,
  );
  // What the moves already queued have submitted; see `move`.
  const optimistic = useRef<PendingOrder | null>(null);

  // The order on screen, and the group each row sits in. A move reasons over
  // ids alone — the order a queued one computed has nothing but ids — so the
  // audience that decides which neighbour it may swap with is looked up here
  // rather than carried along.
  const rendered = useMemo(() => items.map(item => item.id), [items]);
  // The whole definition's order, kinds this list does not draw included. It
  // is what a reorder submits: the store keeps one order per definition, and
  // an order assembled from the narrowed list would drop every id the caller
  // filtered out. The rows the user moves are still only the visible ones.
  const full = useMemo(() => all.map(item => item.id), [all]);
  const audiences = useMemo(
    () =>
      new Map<string, ViewAudience>(
        all.map(item => [item.id, audienceOf(item.scope)]),
      ),
    [all],
  );

  /**
   * The preferences this command means to store. It is only read when the
   * engine refuses before sending, which is why it may quote no revision.
   */
  const preferencesIntent = useCallback(
    (change: Partial<ViewPreferences>): WritePayload => ({
      action: 'preferences',
      definitionId,
      next: {
        order: preferences?.order ?? [],
        defaultInstanceId: preferences?.defaultInstanceId ?? null,
        revision: preferences?.revision ?? UNSENT,
        ...change,
      },
    }),
    [definitionId, preferences],
  );

  const rename = useCallback(
    (id: string, title: string) =>
      run(
        id,
        { action: 'rename', id, revision: UNSENT, title },
        'view.rename.failed',
        () => engine.rename(id, title),
      ),
    [engine, run],
  );

  const remove = useCallback(
    (id: string) =>
      run(
        id,
        { action: 'delete', id, definitionId, revision: UNSENT },
        'view.delete.failed',
        () => engine.delete(id),
      ),
    [definitionId, engine, run],
  );

  const setDefault = useCallback(
    (id: string | null) => {
      // The same thunk twice over: once as the command, once as the intent
      // kept for a `resubmit` after a reload settled a conflict.
      const put = () => engine.setDefault(definitionId, id);
      return run(
        PREFERENCES_KEY,
        preferencesIntent({ defaultInstanceId: id }),
        'view.preferences.failed',
        put,
        { again: put },
      );
    },
    [definitionId, engine, preferencesIntent, run],
  );

  /**
   * The two orders a move reasons over, as they stand this instant.
   *
   * Moves are serialized and the list only catches up on the reload a landing
   * triggers, so two quick moves of one row would both read the same rendered
   * order and the second would ask for a place the first already put it in.
   * The order a queued move computed stands in for the list until the list is
   * no longer the one it was computed from — after which the reload has
   * landed and the rendered order is the truth again.
   *
   * It reads a ref, so it answers when it is called and not before: this is
   * for the moment a move is made, never for a render.
   */
  const current = useCallback(() => {
    const ahead = optimistic.current;
    const carried =
      ahead && owns(ahead) && sameOrder(ahead.base, full) ? ahead : null;
    return carried
      ? { visible: carried.visible, order: carried.order }
      : { visible: rendered, order: full };
  }, [full, owns, rendered]);

  const placeOf = useCallback(
    (id: string) => groupIndexOf(current().visible, audiences, id),
    [audiences, current],
  );

  const moveTo = useCallback(
    (id: string, index: number) => {
      const { visible, order: from } = current();
      const planned = planMoveTo(visible, from, audiences, id, index);
      if (planned === null) return Promise.resolve(false);
      const submitted: PendingOrder = {
        engine,
        definitionId,
        base: full,
        ...planned,
      };
      optimistic.current = submitted;
      // The whole order goes, not the one pair that moved: the server holds
      // a list, not a diff.
      const put = () => engine.reorder(definitionId, planned.order);
      const landed = run(
        PREFERENCES_KEY,
        preferencesIntent({ order: planned.order }),
        'view.preferences.failed',
        put,
        { again: put },
      );
      void landed.then(ok => {
        // A move that never landed leaves the list where it was, so the
        // order it computed must go with it rather than stand in for a list
        // that was never rearranged.
        if (!ok && optimistic.current === submitted) optimistic.current = null;
      });
      return landed;
    },
    [audiences, current, definitionId, engine, full, preferencesIntent, run],
  );

  const retry = useCallback(
    (key: string) => {
      const outcome = outcomes.get(key);
      // Nothing to replay: a refusal never left, and a settled conflict is no
      // longer the engine's to answer for.
      if (!outcome?.handle) return Promise.resolve(false);
      const { handle, state } = outcome;
      return run(
        key,
        state.payload,
        'view.retry.failed',
        () => engine.retryWrite(handle),
        // Read again at the front of the queue: the command ahead may have
        // settled this very outcome, and replaying a handle the engine no
        // longer holds would answer the click with an error about a write
        // that is already over.
        { recovery: true, guard: () => held(key)?.handle === handle },
      );
    },
    [engine, held, outcomes, run],
  );

  const abandon = useCallback(
    (key: string): void => {
      const outcome = outcomes.get(key);
      if (!outcome) return;
      if (outcome.handle) {
        try {
          engine.abandonWrite(outcome.handle);
        } catch {
          // Already settled. Dropping it is the whole intent, and a row left
          // showing a message nothing can clear is the only wrong answer.
        }
      }
      record(key, null);
    },
    [engine, outcomes, record],
  );

  const resolveConflict = useCallback(
    async (key: string, choice: ConflictChoice): Promise<boolean> => {
      const outcome = outcomes.get(key);
      if (!outcome?.handle || outcome.state.kind !== 'conflict') return false;
      const { handle, state } = outcome;
      const landed = await run(
        key,
        state.payload,
        'view.resolve.failed',
        () => engine.resolveConflict(handle, choice),
        // As in `retry`: the conflict may have been settled by the command
        // ahead of this one in the queue.
        { recovery: true, guard: () => held(key)?.handle === handle },
      );
      // Reloading a preference conflict is not the end of it (design/management.md):
      // the stored order and default are read again, and the user's own
      // intent is kept and put to them once more rather than replayed at the
      // new revision behind their back. The list has reloaded, the row goes
      // on saying what happened, and the handle goes — the engine settled it,
      // so pressing the same button again is a new write.
      if (landed && key === PREFERENCES_KEY && choice === 'reload')
        record(key, { state, handle: null, again: outcome.again });
      return landed;
    },
    [engine, held, outcomes, record, run],
  );

  const resubmit = useCallback(
    (key: string): Promise<boolean> => {
      const outcome = outcomes.get(key);
      // Only an intent that was kept is put again: a conflict the engine
      // still holds is answered through `resolveConflict`, and a refusal
      // never left, so there is nothing a second identical write would do.
      if (!outcome || !kept(outcome)) return Promise.resolve(false);
      const { again, state } = outcome;
      if (!again) return Promise.resolve(false);
      // Not a recovery: the engine settled the conflict on the reload, so
      // this is an ordinary new write and the `unknown` guard applies to it
      // exactly as it does to a first attempt.
      return run(key, state.payload, 'view.preferences.failed', again, {
        again,
      });
    },
    [outcomes, run],
  );

  const canResubmit = useCallback(
    (key: string) => kept(outcomes.get(key)),
    [outcomes],
  );

  const can = useMemo<ViewManagerAbilities>(
    () => abilitiesOf(items, permissions),
    [items, permissions],
  );

  const states = useMemo(() => projectStates(outcomes), [outcomes]);

  return {
    rename,
    delete: remove,
    setDefault,
    moveTo,
    placeOf,
    outcomes: states,
    retry,
    abandon,
    resolveConflict,
    resubmit,
    canResubmit,
    pending,
    can,
  };
}
