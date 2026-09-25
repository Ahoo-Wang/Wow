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
 * needs three things it cannot reach itself — how to read a referenced
 * instance, the definition a view it owns is of, and how to build a child
 * runtime for either — so this is also where those are built.
 */

import { queryFailureReporter } from './failures.js';
import type { PanelDefinition } from '../dashboard/index.js';
import type {
  DataViewConfig,
  DashboardViewConfig,
  FieldDefinition,
  FilterTree,
  RuntimeLimits,
  ViewConfig,
  ViewDefinition,
  ViewInstance,
  ViewScope,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import type { RuntimeEnvironment } from './environment.js';
import type { RequestRunner } from './requestRunner.js';
import type { OptionSource, ViewSource } from './source.js';
import type { DataViewRuntime } from './viewRuntime.js';
import { dataViewRuntime } from './recordRuntime.js';
import type {
  ManagedViewRuntime,
  RuntimeCapabilities,
} from './viewRuntimeTypes.js';
import {
  DashboardViewRuntime,
  type PanelResolver,
  type PanelRuntimeFactory,
} from './dashboardRuntime.js';
import type { DefinitionRegistry } from './definitions.js';
import type { SourceCapabilities } from './capabilities.js';
import { withCanonicalNames } from '../capabilities/index.js';
import { ValueCandidateSources } from './valueCandidates.js';
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
  /** Remote candidates of `reference` fields; absent when the host wired none. */
  resolveOptions?(key: string): OptionSource;
  /** One instance, from the definition's code or from the store. */
  readInstance(instanceId: string): Promise<ViewInstance>;
  /**
   * What each source admits: a definition as its descriptor narrows it and
   * the limits a runtime over it runs under (`effective`, which throws
   * where the descriptor contradicts the definition).
   */
  readonly capabilities: SourceCapabilities;
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
    if (config.kind !== 'dashboard')
      return this.buildData(definition, config, identity, scopeFilter);
    // A board takes no outer condition (D26 Q32): one asked for is refused
    // as a later one would be, and `refusedScope` says so from the start.
    const board = this.buildDashboard(definition, config, identity);
    board.setScopeFilter(scopeFilter);
    return board;
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

    const effective = this.host.capabilities.effective(definition);
    // A view saved under a field's alias is read under its path (#3519).
    const renamed = effective.definition.narrowing?.renamed ?? {};
    return dataViewRuntime({
      id: this.newRuntimeId(),
      definition: effective.definition,
      config: withCanonicalNames(config, renamed),
      title: identity.title,
      scope: identity.scope,
      saved: canonicalInstance(identity.saved, renamed),
      kinds: this.host.kinds,
      limits: effective.limits,
      environment: this.host.environment,
      source: this.host.resolveSource(definition.source),
      runner: this.host.runner,
      resolveOptions: this.host.resolveOptions,
      scopeFilter,
      capabilities: this.follow(definition),
    });
  }

  private buildDashboard(
    definition: ViewDefinition,
    config: DashboardViewConfig,
    identity: RuntimeIdentity,
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
      definitions: this.panelDefinition,
      createPanelRuntime: this.createPanelRuntime,
      resolveOptions: this.host.resolveOptions,
      candidateSources: (panelDefinition, scope) =>
        new ValueCandidateSources(
          {
            definition: panelDefinition,
            kinds: this.host.kinds,
            limits: this.host.capabilities.effective(panelDefinition).limits,
            environment: this.host.environment,
            source: this.host.resolveSource(panelDefinition.source),
            // A board filter's values, asked of a panel's source (D40).
            queryFailed: queryFailureReporter(this.host.environment, () => ({
              definitionId: panelDefinition.id,
            })),
          },
          scope,
        ),
    });
  }

  /** What a panel references: the instance and the definition behind it. */
  private readonly resolvePanel: PanelResolver = async instanceId => {
    const instance = await this.host.readInstance(instanceId);
    const declared = this.host.definitions.require(instance.definitionId);
    await this.host.capabilities.prepare(declared);
    const definition = this.admitted(declared);
    return { instance, definition, fields: panelFields(definition) };
  };

  /**
   * The definition a view a board owns is of: code, so looked up rather
   * than loaded, and `null` for one this release does not declare or one
   * that failed admission — neither can be run.
   */
  private readonly panelDefinition = (
    definitionId: string,
  ): PanelDefinition | null => {
    const registry = this.host.definitions;
    if (!registry.definitions.has(definitionId)) return null;
    try {
      const definition = this.admitted(registry.require(definitionId));
      return { definition, fields: panelFields(definition) };
    } catch {
      return null;
    }
  };

  /**
   * A panel's definition as its source admits it, so the board judges a
   * panel against the same fields its child runtime runs on.
   */
  private admitted(definition: ViewDefinition): ViewDefinition {
    return definition.kind === 'data'
      ? this.host.capabilities.effective(definition).definition
      : definition;
  }

  /**
   * One panel's child runtime. It is owned by its dashboard rather than by
   * the engine: it is not saved, renamed or deleted through a command, and it
   * runs no timer of its own, because the dashboard times every panel. A
   * view the board owns has no baseline (`saved` is `null`): it is saved
   * with the board, never on its own.
   */
  private readonly createPanelRuntime: PanelRuntimeFactory = (
    view,
    scopeFilter: FilterTree | null,
  ) => {
    const { instance, definition } = view;
    const renamed =
      (definition.kind === 'data' && definition.narrowing?.renamed) || {};
    return dataViewRuntime({
      id: this.newRuntimeId(),
      definition,
      config: withCanonicalNames(view.config, renamed),
      title: view.title,
      scope: view.scope,
      saved: canonicalInstance(instance, renamed),
      kinds: this.host.kinds,
      limits: this.host.capabilities.effective(definition).limits,
      environment: this.host.environment,
      source: this.host.resolveSource(definition.source),
      runner: this.host.runner,
      resolveOptions: this.host.resolveOptions,
      scopeFilter,
      autoRefresh: false,
      capabilities: this.follow(definition),
    });
  };

  /**
   * How a runtime over `definition` follows its source's descriptor: the
   * definition as declared (a panel may hold one already narrowed), what is
   * in force now, and the source's changes.
   */
  private follow(definition: ViewDefinition): RuntimeCapabilities | undefined {
    const declared = this.host.definitions.definitions.get(definition.id);
    if (declared?.kind !== 'data') return undefined;
    const capabilities = this.host.capabilities;
    return {
      declared,
      effective: () => capabilities.effective(declared),
      watch: listener => capabilities.watch(declared.source, listener),
      revalidate: () => capabilities.revalidate(declared.source),
      recheck: code => capabilities.recheck(declared.source, code),
    };
  }

  private newRuntimeId(): string {
    return `runtime-${(this.sequence += 1)}`;
  }
}

/** A saved instance whose data config is read under the paths it renames. */
function canonicalInstance(
  instance: ViewInstance | null,
  renamed: Readonly<Record<string, string>>,
): ViewInstance | null {
  if (!instance || instance.config.kind === 'dashboard') return instance;
  const config = withCanonicalNames(instance.config, renamed);
  return config === instance.config ? instance : { ...instance, config };
}

function capabilityOf(definition: ViewDefinition, config: ViewConfig): boolean {
  if (definition.kind !== 'data') return false;
  return config.kind === 'record'
    ? definition.record !== undefined
    : definition.analysis !== undefined;
}

/**
 * What a panel's view is judged against.
 *
 * The definition's own fields, for every kind of view: a dashboard's global
 * filter is merged into the panel's own filter, and that filter is the query
 * root. An analysis expands elements below it, but a root filter reaches an
 * element's entries only through an `elementMatch` condition on the array
 * itself — which is a root field — so an expansion adds nothing a binding
 * could point at.
 */
function panelFields(definition: ViewDefinition): readonly FieldDefinition[] {
  return definition.kind === 'data' ? definition.fields : [];
}
