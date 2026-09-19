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
import {
  audienceOf,
  isSystemScope,
  type Issue,
  type ViewAudience,
  type ViewInstance,
  type ViewPreferences,
} from '../model/index.js';
import type {
  ConflictChoice,
  ViewEngine,
  ViewRuntime,
  WriteAction,
  WriteState,
} from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';
import { toIssue } from './issues.js';

export interface SaveTargetInput {
  title: string;
  scope: ViewAudience;
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

const UNRECOVERED: RecoveredWrite = {
  landed: false,
  written: false,
  instance: null,
};

/** Preferences resolve too, and carry no instance. */
function recoveredOf(
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
   * be refused, or the last one came back `unknown` and a second attempt
   * might be a second write. One flag rather than three, because every button
   * that writes disables on all of them.
   *
   * A conflict or a refusal is not among them. Both are a definite answer,
   * and what resolves them is often a new intent — "Save my copy" after
   * somebody else moved the baseline — which this flag would disable. It is
   * deliberately coarse, so a button that has to tell the settled outcomes
   * apart — a rejection is a new attempt away, a conflict is not — or that
   * must refuse a *blind* Save while a conflict is on screen, reads `write`
   * and `hasErrors` themselves rather than this.
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

  const run = useCallback(
    async <T>(
      code: string,
      command: () => Promise<T>,
      fallback: T,
      // Only the caller knows whether its outcome means the store took the
      // write: a delete resolves with `false` on failure, a retry with a
      // replay that may have answered "not landed".
      landed?: (outcome: T) => boolean,
    ) => {
      setProgress({ runtime, pending: true, error: null, savedAt: null });
      try {
        const outcome = await command();
        if (landed?.(outcome) === true) {
          const at = engine.environment.now().getTime();
          setProgress(current =>
            current.runtime === runtime ? { ...current, savedAt: at } : current,
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
        // A command that outlived its view leaves the next view's state alone.
        setProgress(current =>
          current.runtime === runtime && current.pending
            ? { ...current, pending: false }
            : current,
        );
      }
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
        engine
          .retryWrite(runtime)
          .then(it => recoveredOf(it, savesView(action))),
      UNRECOVERED,
      wroteStore,
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
              recoveredOf(it, choice === 'overwrite' && savesView(action)),
            ),
        UNRECOVERED,
        wroteStore,
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
        state?.write?.kind === 'unknown' ||
        issues.some(found => found.severity === 'error'),
      hasErrors: issues.some(found => found.severity === 'error'),
      lastSavedAt: own.savedAt,
    },
  };
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
function wroteStore(recovered: RecoveredWrite): boolean {
  return recovered.written;
}

/**
 * Whether recovering this write saves the view itself. A `rename` and a
 * `delete` reach the store as much as a `save` does, but neither is the
 * config on screen: "View saved" after a retried rename says the edits are
 * safe when nothing of them has been written. A write the runtime no longer
 * holds — the outcome settled between the render and the click — answers no,
 * which is the quiet way to be wrong.
 */
function savesView(action: WriteAction | undefined): boolean {
  return action === 'create' || action === 'save';
}
