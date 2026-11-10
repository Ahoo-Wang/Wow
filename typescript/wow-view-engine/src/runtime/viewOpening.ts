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
 * Opening a view, the engine's half of it: a saved or a code-declared view
 * read (`readInstance`) and opened on its sources, its tab and its filters
 * (`open`), or a view made from nothing (`create`), each built by the
 * `RuntimeFactory` and registered with the open runtimes. `ViewEngine.open`
 * and `ViewEngine.create` hand on to it.
 */

import type {
  FilterTree,
  ViewConfig,
  ViewDefinition,
  ViewInstance,
} from '../model/index.js';
import { parseSystemInstanceId } from '../model/index.js';
import type { ViewStore } from '../store/ViewStore.js';
import type { SourceCapabilities } from './capabilities.js';
import { DashboardViewRuntime } from './dashboardRuntime.js';
import type { DefinitionRegistry } from './definitions.js';
import type { OpenRuntimes } from './openRuntimes.js';
import type { RuntimeFactory, RuntimeIdentity } from './runtimeFactory.js';
import type { TabMemory } from './tabMemory.js';
import { requireTitle } from './commandChecks.js';
import type {
  AnyViewRuntime,
  CreateInput,
  ManagedViewRuntime,
  OpenOptions,
  RuntimeFor,
} from './viewRuntimeTypes.js';

/** What `ViewOpening` opens with: the engine's own parts, as they are. */
export interface ViewOpeningParts {
  readonly store: ViewStore;
  readonly registry: DefinitionRegistry;
  readonly capabilities: SourceCapabilities;
  readonly tabs: TabMemory;
  readonly runtimes: OpenRuntimes;
  readonly factory: RuntimeFactory;
}

export class ViewOpening {
  constructor(private readonly parts: ViewOpeningParts) {}

  /** See `ViewEngine.open`. */
  async open(
    instanceId: string,
    options: OpenOptions = {},
  ): Promise<AnyViewRuntime> {
    const { signal } = options;
    const instance = this.parts.registry.ofDefinition(
      await this.readInstance(instanceId, signal),
      options.definitionId,
    );
    // What its sources admit is read before the first query, so that query
    // already goes out narrowed.
    await this.parts.capabilities.prepareFor(instance);
    signal?.throwIfAborted();
    // A dashboard opens on its tab rather than on its first and then there:
    // only the tab on screen runs, so where it starts is what is asked.
    const tab = await this.parts.tabs.opensOn(instance, options.tab);
    const runtime = this.attach(instance, options.scopeFilter ?? null);
    // A dashboard is judged against the instances it references, so it waits
    // for them before its first apply rather than opening into empty frames —
    // on the tab and under the filters it opens with, noted before that.
    if (runtime instanceof DashboardViewRuntime)
      try {
        runtime.opensOn(tab, options.filters, options.held);
        await runtime.ready();
      } catch (error) {
        // `attach` already registered it, and a dashboard may have children
        // querying by now. Nobody is handed a runtime that failed to open,
        // so nobody could close one: it is dropped here instead of leaking.
        this.parts.runtimes.forget(runtime);
        throw error;
      }
    // Asked for no more while it opened: nobody holds it, so nobody would
    // close it.
    if (signal?.aborted) {
      this.parts.runtimes.forget(runtime);
      signal.throwIfAborted();
    }
    runtime.apply();
    return runtime as AnyViewRuntime;
  }

  /** One instance, from the definition's code or from the store. */
  async readInstance(
    instanceId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstance> {
    const declared = parseSystemInstanceId(instanceId);
    return declared
      ? this.parts.registry.systemInstance(
          declared.definitionId,
          declared.viewId,
        )
      : this.parts.store.get(
          instanceId,
          signal === undefined ? {} : { signal },
        );
  }

  /** See `ViewEngine.create`. */
  create<C extends ViewConfig>(
    definitionId: string,
    input: CreateInput<C>,
  ): RuntimeFor<C> {
    const definition = this.parts.registry.require(definitionId);
    requireTitle(input.title);
    // Made at once, so it runs on the descriptor held; one not read yet is
    // read now, for the next view over the source.
    this.parts.capabilities.warm(definition);

    const runtime = this.build(
      definition,
      input.config,
      {
        title: input.title,
        scope: input.scope,
        saved: null,
      },
      input.scopeFilter ?? null,
    );
    runtime.apply();
    // The conditional type cannot be proven from a union member; what makes it
    // true is `build` rejecting a config the definition does not declare.
    return runtime as unknown as RuntimeFor<C>;
  }

  private attach(
    instance: ViewInstance,
    scopeFilter: FilterTree | null = null,
  ): ManagedViewRuntime {
    const definition = this.parts.registry.require(instance.definitionId);
    return this.build(
      definition,
      instance.config,
      {
        title: instance.title,
        scope: instance.scope,
        saved: instance,
      },
      scopeFilter,
    );
  }

  private build(
    definition: ViewDefinition,
    config: ViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null = null,
  ): ManagedViewRuntime {
    return this.parts.runtimes.add(
      this.parts.factory.build(definition, config, identity, scopeFilter),
    );
  }
}
