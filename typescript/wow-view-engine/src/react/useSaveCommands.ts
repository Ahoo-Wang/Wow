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
import type { Issue, ViewInstance, ViewScope } from '../model/index.js';
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
  scope: Exclude<ViewScope, 'system'>;
}

export interface SaveAbilities {
  save: boolean;
  saveAs: boolean;
  rename: boolean;
  delete: boolean;
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
  retry(): Promise<void>;
  abandon(): void;
  resolveConflict(choice: ConflictChoice): Promise<void>;
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
        setProgress({ runtime, pending: false, error: toIssue(caught, code) });
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
    state?.scope === 'shared'
      ? permissions?.createShared
      : permissions?.createPersonal;
  const system = state?.scope === 'system';

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
            () => engine.retryWrite(runtime).then(() => undefined),
            undefined,
          )
        : Promise.resolve(undefined),
    [engine, runtime, run],
  );

  const abandon = useCallback(() => {
    if (!runtime) return;
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
            () => engine.resolveConflict(runtime, choice).then(() => undefined),
            undefined,
          )
        : Promise.resolve(undefined),
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
      saveAs:
        state !== null &&
        (permissions?.createPersonal || permissions?.createShared) === true,
      rename: !system && instance?.rename === true,
      delete: !system && instance?.delete === true,
    },
    state: {
      pending: own.pending,
      error: own.error,
      write: state?.write ?? null,
      dirty: state?.dirty ?? false,
    },
  };
}
