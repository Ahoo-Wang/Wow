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
  type FilterTree,
  isSystemInstanceId,
  parseSystemInstanceId,
  toSummary,
  type Issue,
  type RuntimeLimits,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
  type ViewAudience,
  type ViewInstanceSummary,
  type ViewPreferences,
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
import type { OptionSource, ViewSource } from './source.js';
import type {
  AnyViewRuntime,
  ManagedViewRuntime,
  RuntimeFor,
  ViewRuntime,
} from './viewRuntime.js';
import { DashboardViewRuntime } from './dashboardRuntime.js';
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
import { ViewChanges, type ViewChangeListener } from './viewChanges.js';
import { DefinitionRegistry, systemInstances } from './definitions.js';
import { PermissionGuard } from './permissions.js';
import { PreferenceCache, resolveDefault } from './preferences.js';
import { OpenRuntimes } from './openRuntimes.js';
import { RuntimeFactory, type RuntimeIdentity } from './runtimeFactory.js';

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

/** Instances a definition declares in code, and the order a user put them in. */
export { systemInstances } from './definitions.js';
export { orderSummaries } from './preferences.js';

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
 * What the commands stand on lives beside this file, one concern each: the
 * definition registry (`definitions.ts`), the permission checks
 * (`permissions.ts`), the preference cache (`preferences.ts`), the open views
 * (`openRuntimes.ts`), how one runtime is assembled (`runtimeFactory.ts`) and
 * the write ledger (`writeLedger.ts`). What is left here is the command
 * surface itself: admission, then one dispatch.
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
  private readonly registry: DefinitionRegistry;
  private readonly guard: PermissionGuard;
  private readonly preferenceCache: PreferenceCache;
  private readonly runtimes = new OpenRuntimes();
  private readonly factory: RuntimeFactory;
  /** Every write that left, and every outcome not yet settled. */
  private readonly ledger: WriteLedger;
  /** Who is told that a definition's list has changed; see `subscribe`. */
  private readonly changes = new ViewChanges(found => this.report(found));
  private readonly summaries = new Map<string, ViewInstanceSummary>();

  constructor(options: ViewEngineOptions) {
    this.options = options;
    this.store = options.store;
    this.kinds = options.kinds ?? builtinFieldKinds;
    this.limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
    this.environment = options.environment ?? defaultRuntimeEnvironment();
    this.runner = new RequestRunner(this.limits);
    this.guard = new PermissionGuard(this.store);
    this.preferenceCache = new PreferenceCache(this.store);
    this.ledger = new WriteLedger(this.ledgerHost());
    this.registry = new DefinitionRegistry(
      options.definitions,
      this.kinds,
      this.limits,
      found => this.report(found),
    );
    this.definitions = this.registry.definitions;
    this.factory = new RuntimeFactory({
      definitions: this.registry,
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      runner: this.runner,
      resolveSource: key => this.resolveSource(key),
      readInstance: id => this.readInstance(id),
    });
  }

  /** What `validateDefinition` said about one definition, for a host to show. */
  definitionIssues(definitionId: string): Issue[] {
    return this.registry.issues(definitionId);
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
    return this.guard.of(definitionId);
  }

  /**
   * Code-declared system views first, then whatever the store holds. An id in
   * the reserved namespace is dropped and reported: only a definition may
   * declare one.
   */
  async list(definitionId: string): Promise<ViewInstanceSummary[]> {
    const definition = this.registry.require(definitionId);
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

  /**
   * Told whenever a write changes what a definition's list holds — a view
   * created, saved, renamed or deleted, the ledger's retries and overwrites
   * included. Returns the way to stop listening.
   *
   * The engine is the one place that knows when a write lands, so it says so
   * rather than leaving every caller to remember (D15).
   */
  subscribe(listener: ViewChangeListener): () => void {
    return this.changes.subscribe(listener);
  }

  async preferences(definitionId: string): Promise<ViewPreferences> {
    return this.preferenceCache.read(definitionId);
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
        this.runtimes.forget(runtime);
        throw error;
      }
    runtime.apply();
    return runtime as AnyViewRuntime;
  }

  /** One instance, from the definition's code or from the store. */
  private async readInstance(instanceId: string): Promise<ViewInstance> {
    const declared = parseSystemInstanceId(instanceId);
    return declared
      ? this.registry.systemInstance(declared.definitionId, declared.viewId)
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
    const definition = this.registry.require(definitionId);
    this.requireTitle(input.title);
    this.guard.requireCreate(definitionId, input.scope);

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
    const target = this.runtimes.require(runtime);
    const state = target.getSnapshot();
    this.requireValid(target.issuesAt(state.scope));

    if (!state.saved) {
      const input = {
        definitionId: target.definition.id,
        title: state.title,
        scope: state.scope,
        config: state.draft,
      };
      this.guard.requireCreate(target.definition.id, state.scope);
      return (await this.ledger.dispatch(
        { action: 'create', input, intent: 'first-save' },
        target,
      )) as ViewInstance;
    }

    const saved = state.saved;
    this.guard.requireInstance(saved, 'save');
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
    const target = this.runtimes.require(runtime);
    const state = target.getSnapshot();
    // Judged at the scope it is going to, not the one it came from.
    this.requireValid(target.issuesAt(input.scope));
    this.requireTitle(input.title);
    this.guard.requireCreate(target.definition.id, input.scope);

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
    const { definitionId, revision, runtime } = await this.locate(id, 'delete');
    const payload: WritePayload = {
      action: 'delete',
      id,
      definitionId,
      revision,
    };
    await this.ledger.dispatch(payload, runtime);
  }

  /** Submits the full visible order, with the revision it was read at. */
  async reorder(
    definitionId: string,
    order: string[],
  ): Promise<ViewPreferences> {
    this.guard.require(
      this.permissions(definitionId).reorder,
      'view.preferences.reorder-forbidden',
    );
    const current = await this.preferenceCache.current(definitionId);
    return this.writePreferences(definitionId, { ...current, order });
  }

  async setDefault(
    definitionId: string,
    instanceId: string | null,
  ): Promise<ViewPreferences> {
    this.guard.require(
      this.permissions(definitionId).setDefault,
      'view.preferences.default-forbidden',
    );
    const current = await this.preferenceCache.current(definitionId);
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
    return resolveDefault(summaries, preferences, explicit);
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
    return this.runtimes.all();
  }

  /**
   * Closes one open view: disposes it and drops it from the registry.
   *
   * A caller that only calls `runtime.dispose()` leaves the engine holding a
   * dead runtime, which then answers `locate` with a revision nobody can write
   * against, so this is the way to let one go.
   */
  close(runtime: ViewRuntime): void {
    this.runtimes.close(runtime);
  }

  dispose(): void {
    this.runtimes.disposeAll();
    this.runner.cancelAll();
    // Nothing more will be written through it, so nothing more is announced:
    // a host that forgot to unsubscribe leaves no listener behind here.
    this.changes.clear();
  }

  private attach(
    instance: ViewInstance,
    scopeFilter: FilterTree | null = null,
  ): ManagedViewRuntime {
    const definition = this.registry.require(instance.definitionId);
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
    return this.runtimes.add(
      this.factory.build(definition, config, identity, scopeFilter),
    );
  }

  private async writePreferences(
    definitionId: string,
    next: ViewPreferences,
  ): Promise<ViewPreferences> {
    const payload: WritePayload = { action: 'preferences', definitionId, next };
    return (await this.ledger.dispatch(payload, undefined)) as ViewPreferences;
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
        for (const holder of this.runtimes.holders(id, owner))
          this.runtimes.forget(holder);
      },
      notePreferences: (definitionId, preferences) =>
        this.preferenceCache.note(definitionId, preferences),
      readPreferences: definitionId => this.preferences(definitionId),
      holders: id => this.runtimes.holders(id),
      noteChange: change => this.changes.emit(change),
    };
  }

  /**
   * What a command needs about an instance it was given only the id of: the
   * revision to write against, the definition whose list it belongs to, and
   * the open view it came through, from an open view, the last list, or the
   * store.
   */
  private async locate(
    id: string,
    action: keyof InstancePermissions,
  ): Promise<{
    revision: string;
    definitionId: string;
    runtime: ManagedViewRuntime | undefined;
  }> {
    // A code-declared view is not in any store, and no store write can reach it.
    if (parseSystemInstanceId(id))
      throw new ViewCommandError(
        issue('view.system.read-only', [], { action }),
      );
    this.runtimes.prune();
    const [runtime] = this.runtimes.holders(id);
    const known = runtime?.getSnapshot().saved ?? this.summaries.get(id);
    const summary = known ?? (await this.store.get(id));
    this.guard.requireInstance(summary, action);
    return {
      revision: summary.revision,
      definitionId: summary.definitionId,
      runtime,
    };
  }

  private report(found: Issue): void {
    this.options.onIssue?.(found);
  }

  private requireTitle(title: string): void {
    if (title.trim().length === 0)
      throw new ViewCommandError(issue('view.title.empty', ['title']));
  }

  private requireValid(issues: readonly Issue[]): void {
    if (issues.some(entry => entry.severity === 'error'))
      throw new ViewCommandError(issue('view.config.invalid', []));
  }
}
