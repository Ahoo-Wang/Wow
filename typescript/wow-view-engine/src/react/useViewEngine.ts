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
  useState,
  useSyncExternalStore,
} from 'react';
import type { Issue, ViewConfig } from '../model/index.js';
import {
  ViewEngine,
  type AnyViewRuntime,
  type ViewEngineOptions,
  type ViewRuntime,
  type ViewRuntimeState,
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
 */
export function useViewRuntime<C extends ViewConfig>(
  runtime: ViewRuntime<C> | null,
): ViewRuntimeState<C> | null {
  const subscribe = useCallback(
    (listener: () => void) => runtime?.subscribe(listener) ?? NO_OP,
    [runtime],
  );
  const snapshot = useCallback(() => runtime?.getSnapshot() ?? null, [runtime]);
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}

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

/**
 * Opens an instance and owns the runtime it produced: changing the id or
 * unmounting disposes the previous one, and a response that arrives after that
 * is dropped rather than applied to a view nobody is looking at.
 *
 * Loading is derived rather than stored. As long as the answer on hand belongs
 * to a different request, this one is still in flight, so the effect sets
 * state only when a response arrives.
 */
export function useOpenView(
  engine: ViewEngine,
  instanceId: string | null,
): OpenViewState {
  const [opened, setOpened] = useState<OpenedView>(NOT_OPENED);

  useEffect(() => {
    // Only `null` means "nothing to open". Any string, empty included, is an
    // id the engine answers for, with a not-found the caller can show.
    if (instanceId === null) return;

    let runtime: AnyViewRuntime | null = null;
    let cancelled = false;

    void engine.open(instanceId).then(
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
  }, [engine, instanceId]);

  const answered =
    opened.instanceId === instanceId &&
    (opened.engine === engine || instanceId === null);

  return useMemo(
    () =>
      answered
        ? { runtime: opened.runtime, loading: false, error: opened.error }
        : { runtime: null, loading: instanceId !== null, error: null },
    [answered, opened.runtime, opened.error, instanceId],
  );
}
