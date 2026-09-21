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
 * How one runtime is assembled: the step between "which view" and "an open
 * view".
 *
 * Both `open` and `create` end here, with the same two checks — the config's
 * kind must be one the definition declares, and a dashboard config belongs to
 * a dashboard definition — and the same set of injected parts. A dashboard
 * needs two things it cannot reach itself, how to read a referenced instance
 * and how to build a child runtime for it, so this is also where those two
 * are built.
 */

import type {
  DashboardViewConfig,
  DataViewDefinition,
  FieldDefinition,
  FilterTree,
  RuntimeLimits,
  ViewConfig,
  ViewDefinition,
  ViewInstance,
  ViewScope,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { analysisScope } from '../analysis/index.js';
import type { RuntimeEnvironment } from './environment.js';
import type { RequestRunner } from './requestRunner.js';
import type { ViewSource } from './source.js';
import type { DataViewConfig } from './execute.js';
import { DataViewRuntime, type ManagedViewRuntime } from './viewRuntime.js';
import {
  DashboardViewRuntime,
  type PanelResolver,
  type PanelRuntimeFactory,
} from './dashboardRuntime.js';
import type { DefinitionRegistry } from './definitions.js';
import { ViewCommandError } from './write.js';

/** Who a runtime is, apart from the config it holds. */
export interface RuntimeIdentity {
  title: string;
  scope: ViewScope;
  saved: ViewInstance | null;
}

/** What the factory needs from the engine that owns it. */
export interface RuntimeFactoryHost {
  readonly definitions: DefinitionRegistry;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;
  readonly runner: RequestRunner;
  /** Where a definition's data comes from, by `DataViewDefinition.source`. */
  resolveSource(key: string): ViewSource;
  /** One instance, from the definition's code or from the store. */
  readInstance(instanceId: string): Promise<ViewInstance>;
}

export class RuntimeFactory {
  private readonly host: RuntimeFactoryHost;
  private sequence = 0;

  constructor(host: RuntimeFactoryHost) {
    this.host = host;
  }

  build(
    definition: ViewDefinition,
    config: ViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null = null,
  ): ManagedViewRuntime {
    return config.kind === 'dashboard'
      ? this.buildDashboard(definition, config, identity, scopeFilter)
      : this.buildData(definition, config, identity, scopeFilter);
  }

  private buildData(
    definition: ViewDefinition,
    config: DataViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null,
  ): DataViewRuntime {
    if (definition.kind !== 'data' || !capabilityOf(definition, config))
      throw new ViewCommandError(
        issue('runtime.kind.not-declared', [], {
          definition: definition.id,
          kind: config.kind,
        }),
      );

    return new DataViewRuntime<DataViewConfig>({
      id: this.newRuntimeId(),
      definition,
      config,
      title: identity.title,
      scope: identity.scope,
      saved: identity.saved,
      kinds: this.host.kinds,
      limits: this.host.limits,
      environment: this.host.environment,
      source: this.host.resolveSource(definition.source),
      runner: this.host.runner,
      scopeFilter,
    });
  }

  private buildDashboard(
    definition: ViewDefinition,
    config: DashboardViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null,
  ): DashboardViewRuntime {
    // A dashboard config belongs to a dashboard definition: the catalogue
    // entry it is listed under, which declares no fields of its own.
    if (definition.kind !== 'dashboard')
      throw new ViewCommandError(
        issue('runtime.kind.not-declared', [], {
          definition: definition.id,
          kind: config.kind,
        }),
      );

    return new DashboardViewRuntime({
      id: this.newRuntimeId(),
      definition,
      config,
      title: identity.title,
      scope: identity.scope,
      saved: identity.saved,
      kinds: this.host.kinds,
      limits: this.host.limits,
      environment: this.host.environment,
      resolve: this.resolvePanel,
      createPanelRuntime: this.createPanelRuntime,
      scopeFilter,
    });
  }

  /** What a panel references: the instance and the definition behind it. */
  private readonly resolvePanel: PanelResolver = async instanceId => {
    const instance = await this.host.readInstance(instanceId);
    const definition = this.host.definitions.require(instance.definitionId);
    return { instance, definition, fields: panelFields(instance, definition) };
  };

  /**
   * One panel's child runtime. It is owned by its dashboard rather than by
   * the engine: it is not saved, renamed or deleted through a command, and it
   * runs no timer of its own, because the dashboard times every panel.
   */
  private readonly createPanelRuntime: PanelRuntimeFactory = (
    reference,
    scopeFilter: FilterTree | null,
  ) => {
    const { instance, definition } = reference;
    return new DataViewRuntime<DataViewConfig>({
      id: this.newRuntimeId(),
      // `validateDashboard` admitted this panel, so the reference is a data
      // view of a data definition by the time a runtime is built for it.
      definition: definition as DataViewDefinition,
      config: instance.config as DataViewConfig,
      title: instance.title,
      scope: instance.scope,
      saved: instance,
      kinds: this.host.kinds,
      limits: this.host.limits,
      environment: this.host.environment,
      source: this.host.resolveSource(
        (definition as DataViewDefinition).source,
      ),
      runner: this.host.runner,
      scopeFilter,
      autoRefresh: false,
    });
  };

  private newRuntimeId(): string {
    return `runtime-${(this.sequence += 1)}`;
  }
}

function capabilityOf(definition: ViewDefinition, config: ViewConfig): boolean {
  if (definition.kind !== 'data') return false;
  return config.kind === 'record'
    ? definition.record !== undefined
    : definition.analysis !== undefined;
}

/**
 * What a panel's view is judged against. An analysis reaches the element
 * fields its config expands, and its filter may already stand on one; the
 * dashboard kernel cannot ask the analysis kernel, so the answer travels
 * with the reference.
 */
function panelFields(
  instance: ViewInstance,
  definition: ViewDefinition,
): readonly FieldDefinition[] {
  if (definition.kind !== 'data') return [];
  const { config } = instance;
  if (config.kind === 'analysis' && definition.analysis)
    return [
      ...analysisScope(definition, definition.analysis, config).fields.values(),
    ];
  return definition.fields;
}
