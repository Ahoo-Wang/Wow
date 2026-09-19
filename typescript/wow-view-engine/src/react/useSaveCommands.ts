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

import { useCallback, useRef, useState } from 'react';
import {
  audienceOf,
  isSystemScope,
  type Issue,
  type ViewAudience,
  type ViewInstance,
} from '../model/index.js';
import type {
  ConflictChoice,
  ViewEngine,
  ViewRuntime,
  WriteState,
} from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';
import { toIssue } from './issues.js';
import {
  createCommandQueue,
  enqueue,
  type CommandQueue,
} from './manager/queue.js';
import {
  blocksNewIntent,
  recovered,
  savesView,
  UNRECOVERED,
  type RecoveredWrite,
} from './writes.js';

export type { RecoveredWrite } from './writes.js';

export interface SaveTargetInput {
  title: string;
  scope: ViewAudience;
}

export interface SaveAbilities {
  save: boolean;
  /** True when a copy may be made in at least one scope. */
  saveAs: boolean;
  rename: boolean;
  delete: boolean;
  /** True when there are edits and a saved baseline to take them back to. */
  revert: boolean;
  /**
   * Which scope a copy may be made in. A dialog that offers a scope the user
   * cannot create in only lets them press Save to be refused, so it offers
   * these and defaults to the first of them.
   */
  createPersonal: boolean;
  createShared: boolean;
}

export interface SaveCommandState {
  /** A write is in flight; a UI disables its buttons on this alone. */
  pending: boolean;
  /** The last refusal or failure, cleared when the next command starts. */
  error: Issue | null;
  /** An unresolved outcome: a conflict, a refusal, or an unknown result. */
  write: WriteState | null;
  dirty: boolean;
  /**
   * Nothing may be written right now: a write is in flight, the draft would
   * be refused, or the outcome on screen stops a new intent
   * ({@link blocksNewIntent}). One flag rather than three, because every
   * button that writes disables on all of them.
   *
   * It is deliberately coarse, so a button that has to tell the settled
   * outcomes apart — a rejection is a new attempt away, a conflict is not —
   * or that must refuse a *blind* Save while a conflict is on screen, reads
   * `write` and `hasErrors` themselves rather than this.
   */
  blocked: boolean;
  /**
   * The draft itself reports something that stops every write. Unlike
   * `blocked` it says nothing about outcomes, which is what lets a caller
   * decide for itself which of those it wants to stop on.
   */
  hasErrors: boolean;
  /**
   * When the last write of this view landed, by the engine's clock. A UI
   * shows a "Saved" moment from it, so it is a timestamp and not a boolean:
   * the same save twice in a row must read as two distinct moments. It is
   * cleared when the next write starts, and is `null` until one lands.
   *
   * Only a command that saved this view marks a moment. A conflict resolved
   * by `reload` settles and reloads without writing anything, and a recovered
   * `rename` or `delete` writes something that is not the config on screen —
   * both leave this where it was rather than saying "Saved" over edits that
   * were just discarded, or were never sent at all.
   */
  lastSavedAt: number | null;
}

export interface SaveCommands {
  save(): Promise<ViewInstance | null>;
  saveAs(input: SaveTargetInput): Promise<ViewInstance | null>;
  rename(title: string): Promise<ViewInstance | null>;
  delete(): Promise<boolean>;
  /** Drops the edits and puts the saved config back in force. */
  revert(): void;
  /** Replays the pending write and reports what the replay answered. */
  retry(): Promise<RecoveredWrite>;
  /**
   * Gives up on an unsettled outcome. With no argument it is the one the
   * open view is reporting; a caller that has a `WriteState` in hand passes
   * it, because a write can outlive the runtime's report of it — the engine
   * clears a runtime's outcome as it settles whichever write landed last,
   * and a copy made out of a conflict is a write of its own.
   */
  abandon(write?: WriteState): void;
  /** Resolves a conflict and reports what the choice answered. */
  resolveConflict(choice: ConflictChoice): Promise<RecoveredWrite>;
  can: SaveAbilities;
  state: SaveCommandState;
}

interface CommandProgress {
  runtime: ViewRuntime | null;
  pending: boolean;
  error: Issue | null;
  savedAt: number | null;
}

const IDLE: CommandProgress = {
  runtime: null,
  pending: false,
  error: null,
  savedAt: null,
};

/**
 * The write commands of one open view, with the permissions that decide which
 * buttons are live.
 *
 * Every command resolves rather than rejects: the outcome lands in `state`, so
 * a click handler needs no try/catch and an unresolved write stays visible
 * until the user retries, overwrites or abandons it.
 */
export function useSaveCommands(
  engine: ViewEngine,
  runtime: ViewRuntime | null,
): SaveCommands {
  const state = useViewRuntime(runtime);
  // Command state belongs to one view. A workbench reuses this hook across
  // views, so progress and the last failure are tagged with the runtime they
  // came from and read as empty for any other.
  const [progress, setProgress] = useState<CommandProgress>(IDLE);
  // One write in flight per runtime, on the same queue the manager's rows run
  // on: a second command waits for the first rather than racing it into the
  // engine's `view.write.in-flight` refusal, which the row would then report
  // as the failure of a click that was only impatient. The tag is the runtime,
  // so a workbench that switches views starts a fresh queue instead of holding
  // the new view's first command behind the one it left. Created on the first
  // command rather than per render, and never read during one.
  const queue = useRef<CommandQueue<ViewRuntime | null> | null>(null);

  const run = useCallback(
    <T>(
      code: string,
      command: () => Promise<T>,
      fallback: T,
      // Only the caller knows whether its outcome means the store took the
      // write: a delete resolves with `false` on failure, a retry with a
      // replay that may have answered "not landed".
      landed?: (outcome: T) => boolean,
      // A replay or a conflict choice: it addresses the outcome that is in
      // the way rather than being stopped by it. See {@link guarded}.
      recovery = false,
    ): Promise<T> => {
      const commands = (queue.current ??=
        createCommandQueue<ViewRuntime | null>());
      return enqueue(commands, runtime, async () => {
        // Asked at the front of the queue rather than at the click: the
        // command ahead may be the one that turned this view `unknown`, or
        // the last thing a runtime did before it was released. Sending anyway
        // earns a `view.write.unknown-pending` refusal, and the header would
        // show it as the failure of a click that was only second — over an
        // outcome the user still has to retry or abandon.
        if (!guarded(runtime, recovery)) return fallback;
        setProgress({ runtime, pending: true, error: null, savedAt: null });
        try {
          const outcome = await command();
          if (landed?.(outcome) === true) {
            const at = engine.environment.now().getTime();
            setProgress(current =>
              current.runtime === runtime
                ? { ...current, savedAt: at }
                : current,
            );
          }
          return outcome;
        } catch (caught) {
          const failure = toIssue(caught, code);
          // A workbench reuses this hook across views; another view's command
          // may have taken the slot while this one was in flight.
          setProgress(current =>
            current.runtime === runtime
              ? { runtime, pending: false, error: failure, savedAt: null }
              : current,
          );
          return fallback;
        } finally {
          // A command that outlived its view leaves the next view's state
          // alone.
          setProgress(current =>
            current.runtime === runtime && current.pending
              ? { ...current, pending: false }
              : current,
          );
        }
      });
    },
    [engine, runtime],
  );

  const own = progress.runtime === runtime ? progress : IDLE;

  const instanceId = state?.saved?.id ?? null;
  // Asked for only when a view is open: the store owns the answer, and an
  // unknown definition is not a question worth putting to it.
  const permissions = runtime
    ? engine.permissions(runtime.definition.id)
    : null;
  const instance =
    permissions && instanceId ? permissions.instance(instanceId) : null;
  const creating =
    state && audienceOf(state.scope) === 'shared'
      ? permissions?.createShared
      : permissions?.createPersonal;
  const system = state !== null && isSystemScope(state.scope);
  // A copy is a create, so it needs the create permission of the scope it is
  // headed for — not of the scope the open view happens to sit in.
  const createPersonal = state !== null && permissions?.createPersonal === true;
  const createShared = state !== null && permissions?.createShared === true;

  const save = useCallback(
    () =>
      runtime
        ? run('view.save.failed', () => engine.save(runtime), null, landedSave)
        : Promise.resolve(null),
    [engine, runtime, run],
  );

  const saveAs = useCallback(
    (input: SaveTargetInput) =>
      runtime
        ? run(
            'view.save-as.failed',
            () => engine.saveAs(runtime, input),
            null,
            landedSave,
          )
        : Promise.resolve(null),
    [engine, runtime, run],
  );

  const rename = useCallback(
    (title: string) =>
      instanceId
        ? run(
            'view.rename.failed',
            () => engine.rename(instanceId, title),
            null,
          )
        : Promise.resolve(null),
    [engine, instanceId, run],
  );

  const remove = useCallback(
    () =>
      instanceId
        ? run(
            'view.delete.failed',
            () => engine.delete(instanceId).then(() => true),
            false,
          )
        : Promise.resolve(false),
    [engine, instanceId, run],
  );

  const retry = useCallback(() => {
    if (!runtime) return Promise.resolve(UNRECOVERED);
    // What the write was for, read before the replay settles it and clears
    // it: a replay does write, but only a create or a save writes the config
    // on screen, and only that is a "View saved" moment.
    const action = runtime.getSnapshot().write?.payload.action;
    return run(
      'view.retry.failed',
      // A delete resolves with nothing, which still means it landed; a
      // recovered create, save or rename carries the instance it produced.
      () =>
        engine.retryWrite(runtime).then(it => recovered(it, savesView(action))),
      UNRECOVERED,
      wroteStore,
      true,
    );
  }, [engine, runtime, run]);

  const abandon = useCallback(
    (write?: WriteState) => {
      if (!runtime) return;
      // Abandoning is the user acting now, not an old callback arriving late,
      // so its outcome takes the slot however it is held.
      try {
        // By its own handle when the caller named one: the runtime may have
        // stopped reporting it, and the engine would then answer that nothing
        // is pending while the write is still in its map.
        engine.abandonWrite(write ? { id: write.requestId } : runtime);
        // Giving up on an outcome is not a write, so it leaves the moment an
        // earlier one landed alone.
        setProgress(current => ({
          runtime,
          pending: false,
          error: null,
          savedAt: current.runtime === runtime ? current.savedAt : null,
        }));
      } catch (caught) {
        setProgress({
          runtime,
          pending: false,
          error: toIssue(caught, 'view.abandon.failed'),
          savedAt: null,
        });
      }
    },
    [engine, runtime],
  );

  const resolveConflict = useCallback(
    (choice: ConflictChoice) => {
      if (!runtime) return Promise.resolve(UNRECOVERED);
      const action = runtime.getSnapshot().write?.payload.action;
      return run(
        'view.resolve.failed',
        // Both a reload and an overwrite resolve only when they landed, but
        // only an overwrite of a create or a save saves this view: a reload
        // takes the stored state and discards the draft, and an overwritten
        // rename moves a title rather than the config — timing either as a
        // save would put "View saved" on screen over edits still unsaved.
        () =>
          engine
            .resolveConflict(runtime, choice)
            .then(it =>
              recovered(it, choice === 'overwrite' && savesView(action)),
            ),
        UNRECOVERED,
        wroteStore,
        true,
      );
    },
    [engine, runtime, run],
  );

  const revert = useCallback(() => runtime?.revert(), [runtime]);

  const issues = state?.issues ?? [];

  return {
    save,
    saveAs,
    rename,
    delete: remove,
    revert,
    retry,
    abandon,
    resolveConflict,
    can: {
      // An unsaved view needs the create permission for its own scope; a saved
      // one needs the permission that belongs to the instance.
      save: !system && (state?.saved ? instance?.save : creating) === true,
      saveAs: createPersonal || createShared,
      rename: !system && instance?.rename === true,
      delete: !system && instance?.delete === true,
      // Reverting writes nothing and needs no permission — it only puts back
      // what the store already holds.
      revert: state?.dirty === true && state.saved !== null,
      createPersonal,
      createShared,
    },
    state: {
      pending: own.pending,
      error: own.error,
      write: state?.write ?? null,
      dirty: state?.dirty ?? false,
      blocked:
        own.pending ||
        blocksNewIntent(state?.write) ||
        issues.some(found => found.severity === 'error'),
      hasErrors: issues.some(found => found.severity === 'error'),
      lastSavedAt: own.savedAt,
    },
  };
}

/**
 * Whether a command that has reached the front of the queue may still go.
 *
 * A recovery always may: it is the very outcome in the way being answered.
 * Anything else is a new intent, and what stops it here is exactly what
 * {@link blocksNewIntent} stopped the click by — read again, because the
 * command ahead of this one may have turned the view `unknown` since. A
 * runtime released while this waited has nothing left to write to, and every
 * command on it is a no-op, so it is not worth an error either. Skipping
 * resolves the command's own fallback, which is what "nothing happened"
 * already reads as.
 */
function guarded(runtime: ViewRuntime | null, recovery: boolean): boolean {
  if (runtime === null || runtime.disposed) return false;
  return recovery || !blocksNewIntent(runtime.getSnapshot().write);
}

/** A save or a copy that produced an instance is one the store took. */
function landedSave(instance: ViewInstance | null): boolean {
  return instance !== null;
}

/**
 * A replay or a conflict choice marks a moment only when it saved this view.
 * Landing is not enough: `reload` settles the conflict by taking the stored
 * state, and nothing of the user's was saved.
 */
function wroteStore(write: RecoveredWrite): boolean {
  return write.written;
}
