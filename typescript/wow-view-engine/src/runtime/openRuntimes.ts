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

/**
 * The views one engine has open.
 *
 * A command is given a runtime or an instance id, and both have to come back
 * to this set: only a runtime the engine owns may be written through, and a
 * confirmed write concerns every open view of the instance it touched — an
 * instance may be open more than once.
 */

import { issue } from '../filter/index.js';
import { DataViewRuntime } from './viewRuntime.js';
import type { ManagedViewRuntime, ViewRuntime } from './viewRuntimeTypes.js';
import { DashboardViewRuntime } from './dashboardRuntime.js';
import { ViewCommandError } from './write.js';

export class OpenRuntimes {
  private readonly runtimes = new Set<ManagedViewRuntime>();

  add<R extends ManagedViewRuntime>(runtime: R): R {
    this.runtimes.add(runtime);
    return runtime;
  }

  /** Every open view, the ones disposed behind the engine's back dropped. */
  all(): ManagedViewRuntime[] {
    this.prune();
    return [...this.runtimes];
  }

  /** The runtime a write command may be addressed to: one the engine owns. */
  require(runtime: ViewRuntime): ManagedViewRuntime {
    if (!isManagedRuntime(runtime) || !this.runtimes.has(runtime))
      throw new ViewCommandError(issue('view.runtime.not-owned', []));
    return runtime;
  }

  /**
   * Open runtimes whose baseline is this instance, `first` ahead of the rest.
   * The engine allows an instance to be open more than once, and a confirmed
   * write to it concerns each of them.
   */
  holders(id: string, first?: ManagedViewRuntime): ManagedViewRuntime[] {
    const found = [...this.runtimes].filter(
      entry => entry !== first && entry.getSnapshot().saved?.id === id,
    );
    return first && this.runtimes.has(first) ? [first, ...found] : found;
  }

  /**
   * Closes one open view: disposes it and drops it from the registry.
   *
   * A caller that only calls `runtime.dispose()` leaves the engine holding a
   * dead runtime, which then answers `locate` with a revision nobody can write
   * against, so this is the way to let one go.
   */
  close(runtime: ViewRuntime): void {
    if (isManagedRuntime(runtime) && this.runtimes.has(runtime)) {
      this.forget(runtime);
      return;
    }
    runtime.dispose();
  }

  forget(runtime: ManagedViewRuntime): void {
    runtime.dispose();
    this.runtimes.delete(runtime);
  }

  disposeAll(): void {
    for (const runtime of [...this.runtimes]) runtime.dispose();
    this.runtimes.clear();
  }

  /** Drops runtimes a caller disposed directly, which the registry cannot see. */
  prune(): void {
    for (const runtime of [...this.runtimes])
      if (runtime.disposed) this.runtimes.delete(runtime);
  }
}

export function isManagedRuntime(
  runtime: ViewRuntime,
): runtime is ManagedViewRuntime {
  return (
    runtime instanceof DataViewRuntime ||
    runtime instanceof DashboardViewRuntime
  );
}
