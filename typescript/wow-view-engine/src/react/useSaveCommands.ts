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
  landed: boolean;
  /** The instance a recovered create, save or rename produced, if any. */
  instance: ViewInstance | null;
}

const UNRECOVERED: RecoveredWrite = { landed: false, instance: null };

/** Preferences resolve too, and carry no instance. */
function recoveredOf(
  result: ViewInstance | ViewPreferences | void,
): RecoveredWrite {
  return {
    landed: true,
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
}

export interface SaveCommands {
  save(): Promise<ViewInstance | null>;
  saveAs(input: SaveTargetInput): Promise<ViewInstance | null>;
  rename(title: string): Promise<ViewInstance | null>;
  delete(): Promise<boolean>;
  /** Replays the pending write and reports what the replay answered. */
  retry(): Promise<RecoveredWrite>;
  abandon(): void;
  /** Resolves a conflict and reports what the choice answered. */
  resolveConflict(choice: ConflictChoice): Promise<RecoveredWrite>;
  can: SaveAbilities;
  state: SaveCommandState;
}

interface CommandProgress {
  runtime: ViewRuntime | null;
  pending: boolean;
  error: Issue | null;
}

const IDLE: CommandProgress = { runtime: null, pending: false, error: null };

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
    async <T>(code: string, command: () => Promise<T>, fallback: T) => {
      setProgress({ runtime, pending: true, error: null });
      try {
        return await command();
      } catch (caught) {
        const failure = toIssue(caught, code);
        // A workbench reuses this hook across views; another view's command
        // may have taken the slot while this one was in flight.
        setProgress(current =>
          current.runtime === runtime
            ? { runtime, pending: false, error: failure }
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
    [runtime],
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
        ? run('view.save.failed', () => engine.save(runtime), null)
        : Promise.resolve(null),
    [engine, runtime, run],
  );

  const saveAs = useCallback(
    (input: SaveTargetInput) =>
      runtime
        ? run('view.save-as.failed', () => engine.saveAs(runtime, input), null)
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

  const retry = useCallback(
    () =>
      runtime
        ? run(
            'view.retry.failed',
            // A delete resolves with nothing, which still means it landed; a
            // recovered create or rename carries the instance it produced.
            () => engine.retryWrite(runtime).then(recoveredOf),
            UNRECOVERED,
          )
        : Promise.resolve(UNRECOVERED),
    [engine, runtime, run],
  );

  const abandon = useCallback(() => {
    if (!runtime) return;
    // Abandoning is the user acting now, not an old callback arriving late,
    // so its outcome takes the slot however it is held.
    try {
      engine.abandonWrite(runtime);
      setProgress({ runtime, pending: false, error: null });
    } catch (caught) {
      setProgress({
        runtime,
        pending: false,
        error: toIssue(caught, 'view.abandon.failed'),
      });
    }
  }, [engine, runtime]);

  const resolveConflict = useCallback(
    (choice: ConflictChoice) =>
      runtime
        ? run(
            'view.resolve.failed',
            // Both a reload and an overwrite resolve only when they landed.
            () => engine.resolveConflict(runtime, choice).then(recoveredOf),
            UNRECOVERED,
          )
        : Promise.resolve(UNRECOVERED),
    [engine, runtime, run],
  );

  return {
    save,
    saveAs,
    rename,
    delete: remove,
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
      createPersonal,
      createShared,
    },
    state: {
      pending: own.pending,
      error: own.error,
      write: state?.write ?? null,
      dirty: state?.dirty ?? false,
    },
  };
}
