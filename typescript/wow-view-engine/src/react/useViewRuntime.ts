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

import { useCallback, useSyncExternalStore } from 'react';

const NO_OP = () => {};

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
  // The server reads the same snapshot: a runtime's state is plain data,
  // the same wherever it is read, and a runtime opened in an effect is null
  // on the server and on the first client frame alike.
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
