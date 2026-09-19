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
  DEFAULT_RUNTIME_LIMITS,
  audienceOf,
  isSystemScope,
  type DashboardViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  isSystemInstanceId,
  parseSystemInstanceId,
  systemInstanceId,
  toSummary,
  CODE_REVISION,
  type Issue,
  type RuntimeLimits,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
  type ViewAudience,
  type ViewInstanceSummary,
  type ViewPreferences,
  type ViewScope,
} from '../model/index.js';
import {
  builtinFieldKinds,
  issue,
  type FieldKindRegistry,
} from '../filter/index.js';
import type {
  InstancePermissions,
  ViewPermissions,
  ViewStore,
} from '../store/ViewStore.js';
import {
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
} from './environment.js';
import { RequestRunner } from './requestRunner.js';
import { analysisScope } from '../analysis/index.js';
import type { OptionSource, ViewSource } from './source.js';
import type { DataViewConfig } from './execute.js';
import {
  DataViewRuntime,
  type AnyViewRuntime,
  type ManagedViewRuntime,
  type RuntimeFor,
  type ViewRuntime,
} from './viewRuntime.js';
import {
  DashboardViewRuntime,
  type PanelResolver,
  type PanelRuntimeFactory,
} from './dashboardRuntime.js';
import {
  isUsableDefinition,
  validateDefinition,
} from './validateDefinition.js';
import {
  ViewCommandError,
  type WritePayload,
  type WriteState,
} from './write.js';
import {
  WriteLedger,
  type ConflictChoice,
  type WriteLedgerHost,
  type WriteTarget,
} from './writeLedger.js';

/** Everything is allowed when a store declares no permissions. */
const ALLOW_ALL: ViewPermissions = {
  createPersonal: true,
  createShared: true,
  reorder: true,
  setDefault: true,
  instance: () => ({ save: true, rename: true, delete: true }),
};

export interface ViewEngineOptions {
  definitions: readonly ViewDefinition[];
  store: ViewStore;
  /** Where a definition's data comes from, by `DataViewDefinition.source`. */
  resolveSource(key: string): ViewSource;
  /** Remote candidates of `reference` fields, by the same key. */
  resolveOptions?(key: string): OptionSource;
  kinds?: FieldKindRegistry;
  limits?: RuntimeLimits;
  environment?: RuntimeEnvironment;
  /** Idempotency keys; overridden in tests to keep them readable. */
  newId?(): string;
  /** Problems with no caller to reject, such as a list entry that was dropped. */
  onIssue?(issue: Issue): void;
}

export interface OpenOptions {
  /**
   * An outer condition in force from the first query, in the view's own field
   * names. It is admitted with the config rather than after it, so a host that
   * scopes a view — an order page showing one customer's shipments — never
   * lets an unscoped query leave, and never shows rows outside its scope.
   */
  scopeFilter?: FilterTree | null;
}

/** The ledger names what a write command is addressed to; the engine takes it. */
export type { ConflictChoice, WriteTarget } from './writeLedger.js';

/** Who a runtime is, apart from the config it holds. */
interface RuntimeIdentity {
  title: string;
  scope: ViewScope;
  saved: ViewInstance | null;
}

export interface CreateInput<C extends ViewConfig> {
  title: string;
  /** A user creates for an audience; only a definition declares a system view. */
  scope: ViewAudience;
  config: C;
}

/**
 * The registry and the command entry point: definitions in, runtimes and
 * writes out.
 *
 * Every write goes through one path, so the default UI and a hand-built one
 * behave the same, and every non-success outcome lands in the same three
 * recovery actions: `retryWrite`, `abandonWrite`, `resolveConflict`.
 *
 * A dashboard is opened by the same commands as a data view. What differs is
 * that it composes other instances, so the engine hands it the two things it
 * cannot reach itself: how to read a referenced instance, and how to build a
 * child runtime for it.
 */
export class ViewEngine {
  readonly store: ViewStore;
  readonly environment: RuntimeEnvironment;
  readonly definitions: ReadonlyMap<string, ViewDefinition>;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;

  private readonly options: ViewEngineOptions;
  private readonly runner: RequestRunner;
  private readonly runtimes = new Set<ManagedViewRuntime>();
  /** Every write that left, and every outcome not yet settled. */
  private readonly ledger: WriteLedger;
  private readonly preferencesCache = new Map<string, ViewPreferences>();
  /** `validateDefinition` per registered definition, computed once. */
  private readonly definitionFindings = new Map<string, Issue[]>();
  private readonly summaries = new Map<string, ViewInstanceSummary>();
  private sequence = 0;

  constructor(options: ViewEngineOptions) {
    this.options = options;
    this.store = options.store;
    this.kinds = options.kinds ?? builtinFieldKinds;
    this.limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
    this.environment = options.environment ?? defaultRuntimeEnvironment();
    this.definitions = new Map(
      options.definitions.map(definition => [definition.id, definition]),
    );
    this.runner = new RequestRunner(this.limits);
    this.ledger = new WriteLedger(this.ledgerHost());

    // Definitions are code, so they are judged once, here, rather than on
    // every open. One that fails is kept but refused at the point of use:
    // that beats a blank registry, and beats a crash at application start.
    for (const definition of options.definitions) {
      const found = validateDefinition(definition, this.kinds, {
        limits: this.limits,
      });
      this.definitionFindings.set(definition.id, found);
      for (const entry of found) this.report(entry);
    }
  }

  /** What `validateDefinition` said about one definition, for a host to show. */
  definitionIssues(definitionId: string): Issue[] {
    return this.definitionFindings.get(definitionId) ?? [];
  }

  resolveSource(key: string): ViewSource {
    return this.options.resolveSource(key);
  }

  resolveOptions(key: string): OptionSource {
    const resolve = this.options.resolveOptions;
    if (!resolve)
      throw new ViewCommandError(
        issue('runtime.options.unresolved', [], { source: key }),
      );
    return resolve(key);
  }

  permissions(definitionId: string): ViewPermissions {
    return this.store.permissions?.(definitionId) ?? ALLOW_ALL;
  }

  /**
   * Code-declared system views first, then whatever the store holds. An id in
   * the reserved namespace is dropped and reported: only a definition may
   * declare one.
   */
  async list(definitionId: string): Promise<ViewInstanceSummary[]> {
    const definition = this.requireDefinition(definitionId);
    const declared = systemInstances(definition).map(toSummary);
    const stored = await this.store.list(definitionId);
    const accepted = stored.filter(summary => {
      if (!isSystemInstanceId(summary.id)) return true;
      this.report(issue('view.list.reserved-id', [], { id: summary.id }));
      return false;
    });
    const all = [...declared, ...accepted];
    for (const summary of all) this.summaries.set(summary.id, summary);
    return all;
  }

  async preferences(definitionId: string): Promise<ViewPreferences> {
    const preferences = await this.store.getPreferences(definitionId);
    this.preferencesCache.set(definitionId, preferences);
    return preferences;
  }

  /** Opens a saved view, or a code-declared one without touching the store. */
  async open(
    instanceId: string,
    options: OpenOptions = {},
  ): Promise<AnyViewRuntime> {
    const instance = await this.readInstance(instanceId);
    const runtime = this.attach(instance, options.scopeFilter ?? null);
    // A dashboard is judged against the instances it references, so it waits
    // for them before its first apply rather than opening into empty frames.
    if (runtime instanceof DashboardViewRuntime)
      try {
        await runtime.ready();
      } catch (error) {
        // `attach` already registered it, and a dashboard may have children
        // querying by now. Nobody is handed a runtime that failed to open,
        // so nobody could close one: it is dropped here instead of leaking.
        this.forget(runtime);
        throw error;
      }
    runtime.apply();
    return runtime as AnyViewRuntime;
  }

  /** One instance, from the definition's code or from the store. */
  private async readInstance(instanceId: string): Promise<ViewInstance> {
    const declared = parseSystemInstanceId(instanceId);
    return declared
      ? this.systemInstance(declared.definitionId, declared.viewId)
      : this.store.get(instanceId);
  }

  /**
   * An unsaved view. It carries a complete config from the start, produced by
   * `defaultRecordConfig`, `defaultAnalysisConfig` or `emptyDashboardConfig`,
   * and executes at once so the user sees data rather than an empty frame.
   */
  create<C extends ViewConfig>(
    definitionId: string,
    input: CreateInput<C>,
  ): RuntimeFor<C> {
    const definition = this.requireDefinition(definitionId);
    this.requireTitle(input.title);
    this.requireCreatePermission(definitionId, input.scope);

    const runtime = this.build(definition, input.config, {
      title: input.title,
      scope: input.scope,
      saved: null,
    });
    runtime.apply();
    // The conditional type cannot be proven from a union member; what makes it
    // true is `build` rejecting a config the definition does not declare.
    return runtime as unknown as RuntimeFor<C>;
  }

  /** First save creates, later saves overwrite. Both need a clean draft. */
  async save(runtime: ViewRuntime): Promise<ViewInstance> {
    const target = this.requireRuntime(runtime);
    const state = target.getSnapshot();
    this.requireValid(target.issuesAt(state.scope));

    if (!state.saved) {
      const input = {
        definitionId: target.definition.id,
        title: state.title,
        scope: state.scope,
        config: state.draft,
      };
      this.requireCreatePermission(target.definition.id, state.scope);
      return (await this.ledger.dispatch(
        { action: 'create', input, intent: 'first-save' },
        target,
      )) as ViewInstance;
    }

    const saved = state.saved;
    this.requireInstancePermission(saved, 'save');
    const payload: WritePayload = {
      action: 'save',
      id: saved.id,
      revision: saved.revision,
      config: state.draft,
    };
    return (await this.ledger.dispatch(payload, target)) as ViewInstance;
  }

  /** A copy under a new title and scope; the source runtime is untouched. */
  async saveAs(
    runtime: ViewRuntime,
    input: { title: string; scope: ViewAudience },
  ): Promise<ViewInstance> {
    const target = this.requireRuntime(runtime);
    const state = target.getSnapshot();
    // Judged at the scope it is going to, not the one it came from.
    this.requireValid(target.issuesAt(input.scope));
    this.requireTitle(input.title);
    this.requireCreatePermission(target.definition.id, input.scope);

    return (await this.ledger.dispatch(
      {
        action: 'create',
        input: {
          definitionId: target.definition.id,
          title: input.title,
          scope: input.scope,
          config: state.draft,
        },
        intent: 'save-as',
      },
      target,
    )) as ViewInstance;
  }

  /** Renaming carries no config, so a draft with errors does not block it. */
  async rename(id: string, title: string): Promise<ViewInstance> {
    this.requireTitle(title);
    const { revision, runtime } = await this.locate(id, 'rename');
    const payload: WritePayload = { action: 'rename', id, revision, title };
    return (await this.ledger.dispatch(payload, runtime)) as ViewInstance;
  }

  async delete(id: string): Promise<void> {
    const { revision, runtime } = await this.locate(id, 'delete');
    const payload: WritePayload = { action: 'delete', id, revision };
    await this.ledger.dispatch(payload, runtime);
  }

  /** Submits the full visible order, with the revision it was read at. */
  async reorder(
    definitionId: string,
    order: string[],
  ): Promise<ViewPreferences> {
    this.requirePermission(
      this.permissions(definitionId).reorder,
      'view.preferences.reorder-forbidden',
    );
    const current = await this.currentPreferences(definitionId);
    return this.writePreferences(definitionId, { ...current, order });
  }

  async setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences> {
    this.requirePermission(
      this.permissions(definitionId).setDefault,
      'view.preferences.default-forbidden',
    );
    const current = await this.currentPreferences(definitionId);
    return this.writePreferences(definitionId, {
      ...current,
      defaultInstanceId: instanceId,
    });
  }

  /**
   * The effective default: an explicit id, else the stored one when it still
   * exists, else the first of the ordered list, which is usually the first
   * system view.
   */
  resolveDefault(
    summaries: readonly ViewInstanceSummary[],
    preferences: ViewPreferences,
    explicit?: string,
  ): string | null {
    const ids = new Set(summaries.map(summary => summary.id));
    if (explicit && ids.has(explicit)) return explicit;
    if (preferences.defaultInstanceId && ids.has(preferences.defaultInstanceId))
      return preferences.defaultInstanceId;
    return orderSummaries(summaries, preferences)[0]?.id ?? null;
  }

  /** Writes still waiting for a decision, by handle id. */
  pendingWrites(): ReadonlyMap<string, WriteState> {
    return this.ledger.pendingWrites();
  }

  /** Replays the original intent under its original `requestId`. */
  async retryWrite(
    target: WriteTarget,
  ): Promise<ViewInstance | ViewPreferences | void> {
    return this.ledger.retryWrite(target);
  }

  /** Drops the outcome and keeps the draft; a later save is a new intent. */
  abandonWrite(target: WriteTarget): void {
    this.ledger.abandonWrite(target);
  }

  /**
   * `reload` takes the server's state, losing the draft; `overwrite` replays
   * the original intent against the revision the conflict reported, which is a
   * new logical write and so takes a new `requestId`.
   */
  async resolveConflict(
    target: WriteTarget,
    choice: ConflictChoice,
  ): Promise<ViewInstance | ViewPreferences | void> {
    return this.ledger.resolveConflict(target, choice);
  }

  /** Open runtimes, for a workbench that tracks its own tabs. */
  openRuntimes(): readonly ViewRuntime[] {
    this.prune();
    return [...this.runtimes];
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

  dispose(): void {
    for (const runtime of [...this.runtimes]) runtime.dispose();
    this.runtimes.clear();
    this.runner.cancelAll();
  }

  private attach(
    instance: ViewInstance,
    scopeFilter: FilterTree | null = null,
  ): ManagedViewRuntime {
    const definition = this.requireDefinition(instance.definitionId);
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
    const runtime =
      config.kind === 'dashboard'
        ? this.buildDashboard(definition, config, identity, scopeFilter)
        : this.buildData(definition, config, identity, scopeFilter);
    this.runtimes.add(runtime);
    return runtime;
  }

  private buildData(
    definition: ViewDefinition,
    config: DataViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null = null,
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
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      source: this.resolveSource(definition.source),
      runner: this.runner,
      scopeFilter,
    });
  }

  private buildDashboard(
    definition: ViewDefinition,
    config: DashboardViewConfig,
    identity: RuntimeIdentity,
    scopeFilter: FilterTree | null = null,
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
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      resolve: this.resolvePanel,
      createPanelRuntime: this.createPanelRuntime,
      scopeFilter,
    });
  }

  /** What a panel references: the instance and the definition behind it. */
  private readonly resolvePanel: PanelResolver = async instanceId => {
    const instance = await this.readInstance(instanceId);
    const definition = this.requireDefinition(instance.definitionId);
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
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      source: this.resolveSource((definition as DataViewDefinition).source),
      runner: this.runner,
      scopeFilter,
      autoRefresh: false,
    });
  };

  private newRuntimeId(): string {
    return `runtime-${(this.sequence += 1)}`;
  }

  private systemInstance(definitionId: string, viewId: string): ViewInstance {
    const definition = this.requireDefinition(definitionId);
    const view = definition.views?.find(entry => entry.id === viewId);
    if (!view)
      throw new ViewCommandError(
        issue('view.open.not-found', [], { id: viewId }),
      );
    return {
      id: systemInstanceId(definitionId, viewId),
      definitionId,
      title: view.title,
      scope: 'system',
      revision: CODE_REVISION,
      config: view.config,
    };
  }

  private async writePreferences(
    definitionId: string,
    next: ViewPreferences,
  ): Promise<ViewPreferences> {
    const payload: WritePayload = { action: 'preferences', definitionId, next };
    return (await this.ledger.dispatch(payload, undefined)) as ViewPreferences;
  }

  private async currentPreferences(
    definitionId: string,
  ): Promise<ViewPreferences> {
    return (
      this.preferencesCache.get(definitionId) ??
      (await this.preferences(definitionId))
    );
  }

  /**
   * What the ledger reaches back for. A confirmed write settles in the ledger,
   * but it also moves what only the engine holds — the summary and preference
   * caches, and the open runtimes of the instance it touched — so the engine
   * lends those out as closures rather than as members of its own surface.
   */
  private ledgerHost(): WriteLedgerHost {
    return {
      store: this.store,
      newId: this.options.newId,
      noteInstance: instance =>
        this.summaries.set(instance.id, toSummary(instance)),
      dropInstance: (id, owner) => {
        this.summaries.delete(id);
        for (const holder of this.holders(id, owner)) this.forget(holder);
      },
      notePreferences: (definitionId, preferences) =>
        this.preferencesCache.set(definitionId, preferences),
      readPreferences: definitionId => this.preferences(definitionId),
      holders: id => this.holders(id),
    };
  }

  /** The revision a command needs, from an open view, the last list, or the store. */
  private async locate(
    id: string,
    action: keyof InstancePermissions,
  ): Promise<{ revision: string; runtime: ManagedViewRuntime | undefined }> {
    // A code-declared view is not in any store, and no store write can reach it.
    if (parseSystemInstanceId(id))
      throw new ViewCommandError(
        issue('view.system.read-only', [], { action }),
      );
    this.prune();
    const [runtime] = this.holders(id);
    const known = runtime?.getSnapshot().saved ?? this.summaries.get(id);
    const summary = known ?? (await this.store.get(id));
    this.requireInstancePermission(summary, action);
    return { revision: summary.revision, runtime };
  }

  /**
   * Open runtimes whose baseline is this instance, `first` ahead of the rest.
   * The engine allows an instance to be open more than once, and a confirmed
   * write to it concerns each of them.
   */
  private holders(
    id: string,
    first?: ManagedViewRuntime,
  ): ManagedViewRuntime[] {
    const found = [...this.runtimes].filter(
      entry => entry !== first && entry.getSnapshot().saved?.id === id,
    );
    return first && this.runtimes.has(first) ? [first, ...found] : found;
  }

  private forget(runtime: ManagedViewRuntime): void {
    runtime.dispose();
    this.runtimes.delete(runtime);
  }

  /** Drops runtimes a caller disposed directly, which the registry cannot see. */
  private prune(): void {
    for (const runtime of [...this.runtimes])
      if (runtime.disposed) this.runtimes.delete(runtime);
  }

  private report(found: Issue): void {
    this.options.onIssue?.(found);
  }

  private requireDefinition(id: string): ViewDefinition {
    const definition = this.definitions.get(id);
    if (!definition)
      throw new ViewCommandError(
        issue('view.definition.not-found', [], { id }),
      );
    // A definition that failed admission cannot produce a usable view: its
    // defaults, its system views or its compiled queries would throw instead.
    const found = this.definitionFindings.get(id) ?? [];
    if (!isUsableDefinition(found))
      throw new ViewCommandError(
        issue('view.definition.invalid', [], {
          id,
          issues: found.filter(entry => entry.severity === 'error').length,
        }),
      );
    return definition;
  }

  private requireRuntime(runtime: ViewRuntime): ManagedViewRuntime {
    if (!isManagedRuntime(runtime) || !this.runtimes.has(runtime))
      throw new ViewCommandError(issue('view.runtime.not-owned', []));
    return runtime;
  }

  private requireTitle(title: string): void {
    if (title.trim().length === 0)
      throw new ViewCommandError(issue('view.title.empty', ['title']));
  }

  private requireValid(issues: readonly Issue[]): void {
    if (issues.some(entry => entry.severity === 'error'))
      throw new ViewCommandError(issue('view.config.invalid', []));
  }

  private requireCreatePermission(
    definitionId: string,
    scope: ViewScope,
  ): void {
    const permissions = this.permissions(definitionId);
    this.requirePermission(
      audienceOf(scope) === 'shared'
        ? permissions.createShared
        : permissions.createPersonal,
      'view.create.forbidden',
    );
  }

  /** Asks for identity and scope alone, so an instance answers as well as a summary. */
  private requireInstancePermission(
    instance: Pick<ViewInstanceSummary, 'id' | 'definitionId' | 'scope'>,
    action: keyof InstancePermissions,
  ): void {
    if (isSystemScope(instance.scope))
      throw new ViewCommandError(
        issue('view.system.read-only', [], { action }),
      );
    this.requirePermission(
      this.permissions(instance.definitionId).instance(instance.id)[action],
      `view.${action}.forbidden`,
    );
  }

  private requirePermission(allowed: boolean, code: string): void {
    if (!allowed) throw new ViewCommandError(issue(code, []));
  }
}

function isManagedRuntime(runtime: ViewRuntime): runtime is ManagedViewRuntime {
  return (
    runtime instanceof DataViewRuntime ||
    runtime instanceof DashboardViewRuntime
  );
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

/** Instances a definition declares in code, in declaration order. */
export function systemInstances(definition: ViewDefinition): ViewInstance[] {
  return (definition.views ?? []).map(view => ({
    id: systemInstanceId(definition.id, view.id),
    definitionId: definition.id,
    title: view.title,
    scope: 'system' as const,
    revision: CODE_REVISION,
    config: view.config,
  }));
}

/**
 * Preferred order first, then whatever the server returned. An id that no
 * longer exists is ignored rather than removed: the next write cleans it up.
 */
export function orderSummaries(
  summaries: readonly ViewInstanceSummary[],
  preferences: ViewPreferences,
): ViewInstanceSummary[] {
  const byId = new Map(summaries.map(summary => [summary.id, summary]));
  const ordered = preferences.order.flatMap(id => {
    const summary = byId.get(id);
    if (summary) byId.delete(id);
    return summary ? [summary] : [];
  });
  return [...ordered, ...byId.values()];
}
