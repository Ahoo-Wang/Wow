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

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import type { FilterTree, Issue } from '../model/index.js';
import {
  ViewEngine,
  type AnyViewRuntime,
  type ViewEngineOptions,
} from '../runtime/index.js';
import { browserRuntimeEnvironment } from './environment.js';
import { toIssue } from './issues.js';

const NO_OP = () => {};

/**
 * Creates one engine for the lifetime of the component and disposes it on
 * unmount. An application that wants a longer life builds the engine itself
 * and passes it to the other hooks; they take an engine, never options.
 *
 * Options are read once. Changing them later has no effect, because a
 * definition set and a store are not render-time values.
 */
export function useViewEngine(options: ViewEngineOptions): ViewEngine {
  const [engine] = useState(
    () =>
      new ViewEngine({
        ...options,
        // After the spread, so an explicit `environment: undefined` from a
        // caller's options object cannot drop page-visibility awareness.
        environment: options.environment ?? browserRuntimeEnvironment(),
      }),
  );
  useEffect(() => () => engine.dispose(), [engine]);
  return engine;
}

/**
 * Subscribes to a runtime. The runtime commits state before it notifies and
 * returns the same object while nothing changes, which is exactly what
 * `useSyncExternalStore` asks of a store.
 *
 * The parameter is the store shape rather than `ViewRuntime<C>`, so the
 * discriminated union `open` returns is accepted as it is and the snapshot
 * type follows from the runtime that was passed.
 */
export function useViewRuntime<R extends ViewRuntimeStore<unknown>>(
  runtime: R | null,
): SnapshotOf<R> | null {
  const subscribe = useCallback(
    (listener: () => void) => runtime?.subscribe(listener) ?? NO_OP,
    [runtime],
  );
  const snapshot = useCallback(
    // The conditional type cannot be proven inside the generic; what makes it
    // true is that the value comes from this runtime's own `getSnapshot`.
    () => (runtime?.getSnapshot() ?? null) as SnapshotOf<R> | null,
    [runtime],
  );
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

/** What `useViewRuntime` needs: any runtime satisfies it. */
export interface ViewRuntimeStore<S> {
  subscribe(listener: () => void): () => void;
  getSnapshot(): S;
}

/**
 * The snapshot a runtime hands out. It distributes, so passing the union
 * `open` returns gives back the union of their states rather than one member.
 */
export type SnapshotOf<R> = R extends { getSnapshot(): infer S } ? S : never;

export interface OpenViewState {
  runtime: AnyViewRuntime | null;
  loading: boolean;
  error: Issue | null;
}

/** What the last completed open produced, and which request it answered. */
interface OpenedView extends OpenViewState {
  engine: ViewEngine | null;
  instanceId: string | null;
}

const NOT_OPENED: OpenedView = {
  engine: null,
  instanceId: null,
  runtime: null,
  loading: false,
  error: null,
};

/** How many times a disposal forced a reopen, and the last runtime that did. */
interface Reopen {
  after: AnyViewRuntime | null;
  attempt: number;
}

const NO_REOPEN: Reopen = { after: null, attempt: 0 };

/**
 * Opens an instance and owns the runtime it produced: changing the id or
 * unmounting disposes the previous one, and a response that arrives after that
 * is dropped rather than applied to a view nobody is looking at.
 *
 * A runtime disposed under it — the engine lets one go when its instance is
 * deleted — is not handed out either. The id is opened again, and the caller
 * gets a live runtime for what is still there, or the not-found the engine
 * answers with, in place of a dead runtime that ignores every command.
 *
 * Loading is derived rather than stored. As long as the answer on hand belongs
 * to a different request, this one is still in flight, so the effect sets
 * state only when a response arrives.
 *
 * A `scopeFilter` is in force from the opening query: the engine admits it
 * with the config, so a host that scopes a view never lets an unscoped query
 * leave. It is read when the view opens and then followed, so a caller may
 * pass a fresh object every render without reopening anything.
 */
export function useOpenView(
  engine: ViewEngine,
  instanceId: string | null,
  scopeFilter: FilterTree | null = null,
): OpenViewState {
  const [opened, setOpened] = useState<OpenedView>(NOT_OPENED);
  // Advanced once per runtime disposed under the hook, so the opening effect
  // runs again for an id that did not change. The dead runtime is remembered
  // so the same one advances it only once.
  const [reopen, setReopen] = useState<Reopen>(NO_REOPEN);
  // Read at open time, so a new object identity does not reopen the view.
  // Kept fresh by the effect below rather than during render, and declared
  // before the opening effect so a reopen sees the current condition.
  const latestScope = useRef(scopeFilter);
  useEffect(() => {
    latestScope.current = scopeFilter;
  }, [scopeFilter]);

  useEffect(() => {
    // Only `null` means "nothing to open". Any string, empty included, is an
    // id the engine answers for, with a not-found the caller can show.
    if (instanceId === null) return;

    let runtime: AnyViewRuntime | null = null;
    let cancelled = false;

    void engine.open(instanceId, { scopeFilter: latestScope.current }).then(
      result => {
        if (cancelled) {
          engine.close(result);
          return;
        }
        runtime = result;
        setOpened({
          engine,
          instanceId,
          runtime: result,
          loading: false,
          error: null,
        });
      },
      (error: unknown) => {
        if (cancelled) return;
        setOpened({
          engine,
          instanceId,
          runtime: null,
          loading: false,
          error: toIssue(error, 'view.open.failed'),
        });
      },
    );

    return () => {
      cancelled = true;
      if (runtime) engine.close(runtime);
    };
  }, [engine, instanceId, reopen.attempt]);

  // Later changes are injected; `setScopeFilter` ignores an identical tree,
  // so this is quiet until the host actually narrows or widens the view.
  const runtime = opened.instanceId === instanceId ? opened.runtime : null;
  useEffect(() => {
    runtime?.setScopeFilter(scopeFilter);
  }, [runtime, scopeFilter]);

  // A runtime is disposed without a notification — `dispose` drops its
  // listeners — so the subscription alone would never fire. The snapshot is
  // read on every render as well, which is where the disposal is seen.
  const disposed = useSyncExternalStore(
    useCallback(
      (listener: () => void) => runtime?.subscribe(listener) ?? NO_OP,
      [runtime],
    ),
    useCallback(() => runtime?.disposed === true, [runtime]),
  );
  // Adjusted during render, as state derived from a value that changed: React
  // re-renders at once with the new attempt and the effect above reopens.
  if (disposed && runtime !== null && reopen.after !== runtime)
    setReopen({ after: runtime, attempt: reopen.attempt + 1 });

  const answered =
    opened.instanceId === instanceId &&
    (opened.engine === engine || instanceId === null) &&
    !disposed;

  return useMemo(
    () =>
      answered
        ? { runtime: opened.runtime, loading: false, error: opened.error }
        : { runtime: null, loading: instanceId !== null, error: null },
    [answered, opened.runtime, opened.error, instanceId],
  );
}
