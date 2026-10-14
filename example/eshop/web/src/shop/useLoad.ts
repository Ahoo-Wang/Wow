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

import { useCallback, useEffect, useState } from "react";

/** A read's state: its value once loaded, or its error. */
export type Loaded<T> =
  | { readonly status: "loading" }
  | { readonly status: "loaded"; readonly value: T }
  | { readonly status: "failed"; readonly error: unknown };

/**
 * Runs `load` when `key` changes, and again on `reload()`; a newer run wins
 * over an older one still in flight.
 */
export function useLoad<T>(
  load: (signal: AbortSignal) => Promise<T>,
  key: string,
): [Loaded<T>, () => void] {
  const [state, setState] = useState<Loaded<T> & { key: string; run: number }>(
    { status: "loading", key, run: 0 },
  );
  const [run, setRun] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).then(
      (value) => {
        if (!controller.signal.aborted)
          setState({ status: "loaded", value, key, run });
      },
      (error: unknown) => {
        if (!controller.signal.aborted)
          setState({ status: "failed", error, key, run });
      },
    );
    return () => controller.abort();
    // `load` is a new function each render; `key` names what it reads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, run]);
  const reload = useCallback(() => setRun((value) => value + 1), []);
  // A result of another key is stale: loading again.
  const current: Loaded<T> =
    state.key === key ? state : { status: "loading" };
  return [current, reload];
}

/** An amount in the shop's currency. */
export function money(amount: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(amount);
}
