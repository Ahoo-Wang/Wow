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

import { useCallback, useMemo, useRef, useState } from 'react';
import {
  audienceOf,
  isSystemScope,
  SYSTEM_INSTANCE_ID_PREFIX,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  type Issue,
  type ViewAudience,
  type ViewPreferences,
} from '../model/index.js';
import {
  isViewWriteError,
  type ConflictChoice,
  type ViewEngine,
  type WriteHandle,
  type WritePayload,
  type WriteState,
} from '../runtime/index.js';
import { toIssue } from './issues.js';
import type { ViewListState } from './useViewList.js';

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

export type MoveDirection = 'up' | 'down';

/** What may be done to one row. `save` is not among them: nothing here edits a config. */
export interface ManagedInstanceAbilities {
  rename: boolean;
  delete: boolean;
}

export interface ViewManagerAbilities {
  reorder: boolean;
  setDefault: boolean;
  instance(id: string): ManagedInstanceAbilities;
  /**
   * Whether anything at all can be managed: the order, the default, or the
   * title or existence of any row on the list. False means the manager would
   * open on a dialog of read-only rows, so the way in is not offered — a
   * button whose only lesson is that it leads nowhere.
   */
  anything: boolean;
}

export interface ViewManagerController {
  rename(id: string, title: string): Promise<boolean>;
  delete(id: string): Promise<boolean>;
  setDefault(id: string | null): Promise<boolean>;
  /**
   * Swaps a view with its neighbour in the same audience. The order is one
   * list, but it is read as two — personal above shared — so a swap across
   * that boundary is a write with nothing to show for it.
   */
  move(id: string, direction: MoveDirection): Promise<boolean>;
  /**
   * Whether {@link move} would move anything: false at either end of the
   * row's own audience, where the arrow is disabled rather than pressed for
   * a write that changes nothing the user can see.
   */
  canMove(id: string, direction: MoveDirection): boolean;
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
   * against the revision just reloaded — the second half of how design §7.3
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

/**
 * One recorded outcome. The handle is the engine's, kept here rather than
 * handed out: a caller acts on the row it can see, and `retry`, `abandon` and
 * `resolveConflict` address the write for it.
 */
interface Outcome {
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

/**
 * The revision and the request id of a write that never left. A refusal is
 * recorded so the row can say why it did not happen, and its payload is the
 * intent rather than anything sent, so it quotes neither.
 */
const UNSENT = '';

const NO_OUTCOMES: ReadonlyMap<string, Outcome> = new Map();

/**
 * What the commands have produced, tagged with the inputs they were raised
 * under.
 *
 * The tag is the derivation `useViewList` makes — "which request does this
 * answer belong to", asked of a command rather than of a load. A workbench
 * swaps `definitionId` or `engine` while a write is in flight, and without
 * the tag the rows of the definition just left keep their outcomes and their
 * progress against a list that no longer holds them, and a completion from
 * the old inputs repopulates the new one.
 */
interface ManagerState {
  /** Null only before the first command; nothing is tagged with it. */
  engine: ViewEngine | null;
  definitionId: string;
  outcomes: ReadonlyMap<string, Outcome>;
  /** The key of the one write in flight; see `pending`. */
  pending: string | null;
}

/** State belonging to no inputs, which is also how other inputs' state reads. */
const NOTHING_MANAGED: ManagerState = {
  engine: null,
  definitionId: '',
  outcomes: NO_OUTCOMES,
  pending: null,
};

const NO_INSTANCE_WRITES: ManagedInstanceAbilities = {
  rename: false,
  delete: false,
};

/**
 * The serialization queue, tagged with the inputs whose commands are on it.
 *
 * Chaining is only right among commands that answer for the same list: a
 * workbench that swaps `definitionId` or `engine` while a write hangs would
 * otherwise queue the new definition's first command behind it, and the row
 * the user just clicked would sit there showing nothing.
 */
interface CommandQueue {
  engine: ViewEngine;
  definitionId: string;
  chain: Promise<boolean>;
}

/**
 * The order a queued move computed, while the list it was computed from is
 * still a reload behind it.
 *
 * `base` is the full rendered order it started from: once the list is no
 * longer that, the reload has landed and the rendered order is the truth
 * again. The two orders after the swap are both kept, because they answer
 * different questions — `order` is what was submitted, and `visible` is what
 * the next move looks for a neighbour in.
 */
interface OptimisticOrder {
  engine: ViewEngine;
  definitionId: string;
  /** The full rendered order, unfiltered, before this move. */
  base: readonly string[];
  /** The full order this move submitted. */
  order: readonly string[];
  /** The narrowed order as this move left it. */
  visible: readonly string[];
}

/**
 * What a command tells the queue about itself, beyond what it writes.
 *
 * Both halves arrived with a different question — one keeps an intent alive
 * across a reload, the other decides whether an unsettled key accepts this
 * command at all — and they are independent: `resubmit` carries an intent and
 * is still an ordinary write, so the `unknown` guard applies to it.
 */
interface RunOptions {
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

function sameOrder(one: readonly string[], other: readonly string[]): boolean {
  return one.length === other.length && one.every((id, at) => id === other[at]);
}

/**
 * A refusal, in the shape the row already renders. `ViewCommandError` means
 * nothing was sent, which is exactly a rejection with no outcome to recover.
 */
function refused(payload: WritePayload, issue: Issue): WriteState {
  return { requestId: UNSENT, payload, kind: 'rejected', issue };
}

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
 */
export function useViewManager(
  engine: ViewEngine,
  definitionId: string,
  list: ViewListState,
): ViewManagerController {
  // Kept here rather than read back from `engine.pendingWrites()`: that map is
  // keyed by request id and says nothing about which row raised a write, and
  // the error each command rejects with already carries both halves.
  const [state, setState] = useState<ManagerState>(NOTHING_MANAGED);
  // The same state, readable between renders. A queued command decides
  // whether it may run at all from what the command before it recorded, and
  // no render has necessarily happened in between.
  const live = useRef<ManagerState>(NOTHING_MANAGED);
  // The write in flight, so the next one waits for it rather than racing it.
  const queue = useRef<CommandQueue | null>(null);
  // What the moves already queued have submitted; see `move`.
  const optimistic = useRef<OptimisticOrder | null>(null);
  const { all, items, permissions, preferences, reload } = list;

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

  // State raised under other inputs is about rows this render does not list,
  // so it reads as nothing rather than being shown against these ones.
  const own =
    state.engine === engine && state.definitionId === definitionId
      ? state
      : NOTHING_MANAGED;
  const outcomes = own.outcomes;

  const commit = useCallback((next: ManagerState): void => {
    live.current = next;
    setState(next);
  }, []);

  const record = useCallback(
    (key: string, outcome: Outcome | null): void => {
      const current = live.current;
      // A completion from inputs the hook has moved on from answers for a
      // row the list no longer holds; it must not land on the new one.
      if (current.engine !== engine || current.definitionId !== definitionId)
        return;
      if (outcome === null && !current.outcomes.has(key)) return;
      const next = new Map(current.outcomes);
      if (outcome) next.set(key, outcome);
      else next.delete(key);
      commit({ ...current, outcomes: next });
    },
    [commit, definitionId, engine],
  );

  /**
   * The outcome a command would be landing on, or null when the state on
   * hand was raised under other inputs and says nothing about this key.
   */
  const held = useCallback(
    (key: string): Outcome | null => {
      const current = live.current;
      if (current.engine !== engine || current.definitionId !== definitionId)
        return null;
      return current.outcomes.get(key) ?? null;
    },
    [definitionId, engine],
  );

  const execute = useCallback(
    async (
      key: string,
      intent: WritePayload,
      code: string,
      command: () => Promise<unknown>,
      /**
       * Kept with the outcome so the same intent can be put again later. Only
       * the preference commands pass one: everything else recovers through
       * its handle, which addresses the write the store already has.
       */
      again?: () => Promise<unknown>,
      /** True for a replay or a conflict choice; see `RunOptions.recovery`. */
      recovery = false,
    ): Promise<boolean> => {
      // A `rejected` outcome that still holds a handle is the one unsettled
      // outcome a new command is allowed past (design §7.4: correct it and
      // save again), and this command is about to take its slot. Settling it
      // first is what keeps the write it addresses from being left in
      // `engine.pendingWrites()` with nothing on screen able to reach it. A
      // recovery is not a new intent — it is that very handle being used.
      const stale = recovery ? null : held(key);
      if (stale?.handle && stale.state.kind === 'rejected') {
        try {
          engine.abandonWrite(stale.handle);
        } catch {
          // Already settled, which is the state this wanted it in.
        }
      }
      // Claiming the slot also tags it: a command under new inputs starts
      // from nothing rather than inheriting the outcomes of the old ones.
      const current = live.current;
      commit(
        current.engine === engine && current.definitionId === definitionId
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
        if (isViewWriteError(caught)) {
          record(key, { state: caught.state, handle: caught.handle, again });
        } else if (!held(key)?.handle) {
          // A refusal never left, so it has nothing to replay. Letting it
          // take the place of an outcome that still holds a handle would
          // drop the only way to retry or abandon that write — which is
          // exactly what the engine refusing a second command against an
          // `unknown` outcome would otherwise do to it. The intent still
          // rides along: a refusal is the user's command all the same, and
          // `resubmit` is how a preference write is put again.
          record(key, {
            state: refused(intent, toIssue(caught, code)),
            handle: null,
            again,
          });
        }
        return false;
      } finally {
        const settled = live.current;
        if (
          settled.engine === engine &&
          settled.definitionId === definitionId &&
          settled.pending === key
        )
          commit({ ...settled, pending: null });
      }
    },
    [commit, definitionId, engine, held, record, reload],
  );

  /**
   * One write at a time, in the order the clicks came.
   *
   * Two rows deleted in quick succession are two writes against one list and
   * one `pending` slot: run together, the second one's completion clears the
   * slot while the first is still going, and the first row stops showing
   * progress it is still making. Chaining also keeps the reload each landing
   * triggers from reading a list the other write is halfway through. An idle
   * queue starts now rather than a microtask later, so the row the user just
   * clicked shows progress in that same event.
   */
  const run = useCallback(
    (
      key: string,
      intent: WritePayload,
      code: string,
      command: () => Promise<unknown>,
      { again, recovery = false, guard }: RunOptions = {},
    ): Promise<boolean> => {
      // A row holds one outcome, so a new command for a key whose outcome is
      // still the engine's to answer for has nowhere to put its own:
      // recording it would drop the handle, and the write it addresses would
      // be left in `engine.pendingWrites()` with nothing on screen able to
      // retry, overwrite or abandon it. The engine refuses a second command
      // against an `unknown` outright; a `conflict` it would dispatch over,
      // which is the same problem one step later. A `rejected` outcome is a
      // definite answer with nothing outstanding, so §7.4's "correct it and
      // save again" goes through as the new intent it is.
      const blocked = () => {
        const outcome = !recovery ? held(key) : null;
        return (
          outcome?.handle != null &&
          (outcome.state.kind === 'unknown' ||
            outcome.state.kind === 'conflict')
        );
      };
      if (blocked()) return Promise.resolve(false);
      // Checked again at the front of the queue: the command ahead may be
      // the one that turns this key `unknown`.
      const start = () =>
        blocked() || (guard !== undefined && !guard())
          ? Promise.resolve(false)
          : execute(key, intent, code, command, again, recovery);
      const ahead = queue.current;
      // Only the queue these inputs put there. One belonging to a definition
      // or an engine the hook has moved on from settles on its own, and this
      // command starts now rather than behind a write nobody is watching.
      const mine =
        ahead && ahead.engine === engine && ahead.definitionId === definitionId
          ? ahead.chain
          : null;
      // `execute` resolves whatever happened, so the rejection arm is only
      // there to keep one broken link from stalling the queue for good.
      const landed = mine === null ? start() : mine.then(start, start);
      const queued: CommandQueue = { engine, definitionId, chain: landed };
      queue.current = queued;
      const release = () => {
        if (queue.current === queued) queue.current = null;
      };
      void landed.then(release, release);
      return landed;
    },
    [definitionId, engine, execute, held],
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
        { action: 'delete', id, revision: UNSENT },
        'view.delete.failed',
        () => engine.delete(id),
      ),
    [engine, run],
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

  // The arrow is disabled from what the user is looking at, so it reads the
  // rendered order alone: a queued move's order is a ref, and reading it here
  // would make this answer depend on something no render can see.
  const canMove = useCallback(
    (id: string, direction: MoveDirection) =>
      neighbourOf(rendered, audiences, id, direction) >= 0,
    [audiences, rendered],
  );

  const move = useCallback(
    (id: string, direction: MoveDirection) => {
      const ahead = optimistic.current;
      // Moves are serialized and the list only catches up on the reload a
      // landing triggers, so two quick moves of one row both read the same
      // rendered order and submit the same result twice. The order the queued
      // move computed stands in for the list until the list is no longer the
      // one it was computed from — after which the reload has landed and the
      // rendered order is the truth again.
      const carried =
        ahead &&
        ahead.engine === engine &&
        ahead.definitionId === definitionId &&
        sameOrder(ahead.base, full)
          ? ahead
          : null;
      const visible = carried ? carried.visible : rendered;
      // The pair to swap is found among the rows the user can see, and in
      // the same group: the two lists render personal views above shared
      // ones, so a swap across that boundary would store a new order and
      // move nothing on screen.
      const from = visible.indexOf(id);
      const to = neighbourOf(visible, audiences, id, direction);
      // A row at either end of its own audience has nowhere to go, and a row
      // the list no longer holds cannot be placed. Submitting the order
      // unchanged would still cost a revision and still be able to conflict.
      if (from < 0 || to < 0) return Promise.resolve(false);
      const other = visible[to];
      // The swap itself happens in the *full* order. Submitting the visible
      // one would store a list with every other kind's id missing, and the
      // store keeps one order for the whole definition: a record workbench
      // reordering its own views would silently drop the analyses.
      const order = [...(carried ? carried.order : full)];
      const at = order.indexOf(id);
      const otherAt = order.indexOf(other);
      if (at < 0 || otherAt < 0) return Promise.resolve(false);
      [order[at], order[otherAt]] = [order[otherAt], order[at]];
      const moved = [...visible];
      [moved[from], moved[to]] = [moved[to], moved[from]];
      const submitted: OptimisticOrder = {
        engine,
        definitionId,
        base: full,
        order,
        visible: moved,
      };
      optimistic.current = submitted;
      // The whole order goes, not the one pair that moved: the server holds
      // a list, not a diff.
      const put = () => engine.reorder(definitionId, order);
      const landed = run(
        PREFERENCES_KEY,
        preferencesIntent({ order }),
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
    [audiences, definitionId, engine, full, preferencesIntent, rendered, run],
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
      // Reloading a preference conflict is not the end of it (design §7.3):
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

  const can = useMemo<ViewManagerAbilities>(() => {
    const instance = (id: string): ManagedInstanceAbilities => {
      // A system view ships with the definition, so no store write reaches
      // it whatever the permissions answer for its id.
      const summary = items.find(item => item.id === id);
      if (summary && isSystemScope(summary.scope)) return NO_INSTANCE_WRITES;
      const granted = permissions.instance(id);
      return { rename: granted.rename, delete: granted.delete };
    };
    return {
      reorder: permissions.reorder,
      setDefault: permissions.setDefault,
      instance,
      // Asked of the rows on screen rather than of the permissions alone: a
      // list whose every row is a system view answers "nothing", however
      // freely the store hands out `rename` and `delete`.
      anything:
        permissions.reorder ||
        permissions.setDefault ||
        items.some(item => {
          const granted = instance(item.id);
          return granted.rename || granted.delete;
        }),
    };
  }, [items, permissions]);

  const states = useMemo<ReadonlyMap<string, WriteState>>(() => {
    const projected = new Map<string, WriteState>();
    for (const [key, outcome] of outcomes) projected.set(key, outcome.state);
    return projected;
  }, [outcomes]);

  return {
    rename,
    delete: remove,
    setDefault,
    move,
    canMove,
    outcomes: states,
    retry,
    abandon,
    resolveConflict,
    resubmit,
    canResubmit,
    pending: own.pending,
    can,
  };
}

/**
 * Whether an outcome is a conflict the engine has already settled and whose
 * intent is still the user's to put again: after a reload there is no handle
 * to recover through, and the button offers the write once more rather than
 * a recovery that would answer `false`.
 */
function kept(outcome: Outcome | undefined): boolean {
  return (
    outcome !== undefined &&
    outcome.handle === null &&
    outcome.again !== undefined &&
    outcome.state.kind === 'conflict'
  );
}

/**
 * The index the row would swap with: the nearest one in that direction that
 * the sidebar shows in the same group, or -1 when there is none.
 *
 * Audience is the reason this is not `index ± 1`. Both lists render personal
 * views above shared ones whatever order is stored, so the row above a
 * shared view on screen may be a personal one, and swapping the two would
 * store a new order, spend a revision and move nothing anybody can see.
 *
 * It walks an order of ids rather than the summaries themselves, because the
 * order a queued move computed is the one the next move has to reason over
 * while a row's audience is the same wherever that order puts it. Both
 * indices a swap needs then come from the same list.
 */
function neighbourOf(
  order: readonly string[],
  audiences: ReadonlyMap<string, ViewAudience>,
  id: string,
  direction: MoveDirection,
): number {
  const from = order.indexOf(id);
  const audience = from < 0 ? undefined : audiences.get(id);
  if (audience === undefined) return -1;
  const step = direction === 'up' ? -1 : 1;
  for (let at = from + step; at >= 0 && at < order.length; at += step)
    if (audiences.get(order[at]) === audience) return at;
  return -1;
}
