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
  type DashboardViewConfig,
  type DataViewDefinition,
  type FilterTree,
  isSystemInstanceId,
  isViewStoreError,
  parseSystemInstanceId,
  systemInstanceId,
  toSummary,
  CODE_REVISION,
  type Issue,
  type RuntimeLimits,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
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
  WriteContext,
} from '../store/ViewStore.js';
import {
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
} from './environment.js';
import { RequestRunner } from './requestRunner.js';
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
  ViewCommandError,
  ViewWriteError,
  type WriteHandle,
  type WritePayload,
  type WriteState,
} from './write.js';

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

/** What a write command is addressed to: an open view, or a handle. */
export type WriteTarget = ViewRuntime | WriteHandle;

export type ConflictChoice = 'reload' | 'overwrite';

/** Who a runtime is, apart from the config it holds. */
interface RuntimeIdentity {
  title: string;
  scope: ViewScope;
  saved: ViewInstance | null;
}

export interface CreateInput<C extends ViewConfig> {
  title: string;
  scope: Exclude<ViewScope, 'system'>;
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
  private readonly writes = new Map<string, WriteState>();
  private readonly owners = new Map<string, ManagedViewRuntime>();
  private readonly preferencesCache = new Map<string, ViewPreferences>();
  private readonly summaries = new Map<string, ViewInstanceSummary>();
  private readonly newId: () => string;
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
    this.newId = options.newId ?? (() => crypto.randomUUID());
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
  async open(instanceId: string): Promise<AnyViewRuntime> {
    const instance = await this.readInstance(instanceId);
    const runtime = this.attach(instance);
    // A dashboard is judged against the instances it references, so it waits
    // for them before its first apply rather than opening into empty frames.
    if (runtime instanceof DashboardViewRuntime) await runtime.ready();
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
      return (await this.dispatch(
        { action: 'create', input, intent: 'first-save' },
        this.newRequestId(),
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
    return (await this.dispatch(
      payload,
      this.newRequestId(),
      target,
    )) as ViewInstance;
  }

  /** A copy under a new title and scope; the source runtime is untouched. */
  async saveAs(
    runtime: ViewRuntime,
    input: { title: string; scope: Exclude<ViewScope, 'system'> },
  ): Promise<ViewInstance> {
    const target = this.requireRuntime(runtime);
    const state = target.getSnapshot();
    // Judged at the scope it is going to, not the one it came from.
    this.requireValid(target.issuesAt(input.scope));
    this.requireTitle(input.title);
    this.requireCreatePermission(target.definition.id, input.scope);

    return (await this.dispatch(
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
      this.newRequestId(),
      target,
    )) as ViewInstance;
  }

  /** Renaming carries no config, so a draft with errors does not block it. */
  async rename(id: string, title: string): Promise<ViewInstance> {
    this.requireTitle(title);
    const { revision, runtime } = await this.locate(id, 'rename');
    const payload: WritePayload = { action: 'rename', id, revision, title };
    return (await this.dispatch(
      payload,
      this.newRequestId(),
      runtime,
    )) as ViewInstance;
  }

  async delete(id: string): Promise<void> {
    const { revision, runtime } = await this.locate(id, 'delete');
    // Preferences keep the id; a later reorder or default cleans it up.
    const payload: WritePayload = { action: 'delete', id, revision };
    await this.dispatch(payload, this.newRequestId(), runtime);
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
    return this.writes;
  }

  /** Replays the original intent under its original `requestId`. */
  async retryWrite(
    target: WriteTarget,
  ): Promise<ViewInstance | ViewPreferences | void> {
    const { requestId, state } = this.requireWrite(target);
    return this.dispatch(state.payload, requestId, this.owners.get(requestId));
  }

  /** Drops the outcome and keeps the draft; a later save is a new intent. */
  abandonWrite(target: WriteTarget): void {
    const { requestId } = this.requireWrite(target);
    this.settle(requestId);
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
    const { requestId, state } = this.requireWrite(target);
    if (state.kind !== 'conflict')
      throw new ViewCommandError(
        issue('view.write.not-a-conflict', [], { kind: state.kind }),
      );

    const runtime = this.owners.get(requestId);
    if (choice === 'reload') {
      this.settle(requestId);
      return this.reload(state, runtime);
    }
    this.settle(requestId);
    return this.dispatch(
      withRevision(state.payload, state.remote),
      this.newRequestId(),
      runtime,
    );
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

  private attach(instance: ViewInstance): ManagedViewRuntime {
    const definition = this.requireDefinition(instance.definitionId);
    return this.build(definition, instance.config, {
      title: instance.title,
      scope: instance.scope,
      saved: instance,
    });
  }

  private build(
    definition: ViewDefinition,
    config: ViewConfig,
    identity: RuntimeIdentity,
  ): ManagedViewRuntime {
    const runtime =
      config.kind === 'dashboard'
        ? this.buildDashboard(definition, config, identity)
        : this.buildData(definition, config, identity);
    this.runtimes.add(runtime);
    return runtime;
  }

  private buildData(
    definition: ViewDefinition,
    config: DataViewConfig,
    identity: RuntimeIdentity,
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
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      resolve: this.resolvePanel,
      createPanelRuntime: this.createPanelRuntime,
    });
  }

  /** What a panel references: the instance and the definition behind it. */
  private readonly resolvePanel: PanelResolver = async instanceId => {
    const instance = await this.readInstance(instanceId);
    return {
      instance,
      definition: this.requireDefinition(instance.definitionId),
    };
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
    return (await this.dispatch(
      payload,
      this.newRequestId(),
      undefined,
    )) as ViewPreferences;
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
   * The one place a write leaves the engine. Success clears the outcome;
   * anything else is recorded, attached to the owning runtime and raised as a
   * `ViewWriteError` carrying its own handle.
   */
  private async dispatch(
    payload: WritePayload,
    requestId: string,
    runtime: ManagedViewRuntime | undefined,
  ): Promise<ViewInstance | ViewPreferences | void> {
    const context: WriteContext = { requestId };
    if (runtime) this.owners.set(requestId, runtime);
    try {
      const result = await this.send(payload, context);
      this.applyEffect(payload, result, runtime);
      this.settle(requestId);
      return result;
    } catch (error) {
      const state = await this.toWriteState(error, requestId, payload);
      this.writes.set(requestId, state);
      runtime?.setWrite(state);
      throw new ViewWriteError(state);
    }
  }

  /**
   * What a confirmed write changes here. It hangs off the payload rather than
   * the command, so replaying an intent as a retry or an overwrite advances
   * the same baseline the first attempt would have.
   */
  private applyEffect(
    payload: WritePayload,
    result: ViewInstance | ViewPreferences | void,
    runtime: ManagedViewRuntime | undefined,
  ): void {
    switch (payload.action) {
      case 'create': {
        const instance = result as ViewInstance;
        this.summaries.set(instance.id, toSummary(instance));
        if (payload.intent === 'first-save') runtime?.markSaved(instance);
        return;
      }
      case 'save':
      case 'rename': {
        const instance = result as ViewInstance;
        this.summaries.set(instance.id, toSummary(instance));
        runtime?.markSaved(instance);
        return;
      }
      case 'delete': {
        this.summaries.delete(payload.id);
        if (runtime) this.forget(runtime);
        return;
      }
      case 'preferences':
        this.preferencesCache.set(
          payload.definitionId,
          result as ViewPreferences,
        );
    }
  }

  private send(
    payload: WritePayload,
    context: WriteContext,
  ): Promise<ViewInstance | ViewPreferences | void> {
    switch (payload.action) {
      case 'create':
        return this.store.create(payload.input, context);
      case 'save':
        return this.store.save(
          payload.id,
          payload.config,
          payload.revision,
          context,
        );
      case 'rename':
        return this.store.rename(
          payload.id,
          payload.title,
          payload.revision,
          context,
        );
      case 'delete':
        return this.store.delete(payload.id, payload.revision, context);
      case 'preferences':
        return this.store.setPreferences(
          payload.definitionId,
          payload.next,
          context,
        );
    }
  }

  private async toWriteState(
    error: unknown,
    requestId: string,
    payload: WritePayload,
  ): Promise<WriteState> {
    if (!isViewStoreError(error))
      // The request left and nothing came back: neither failure nor success.
      return { kind: 'unknown', requestId, payload };

    switch (error.code) {
      case 'UNAVAILABLE':
        return { kind: 'unknown', requestId, payload };
      case 'CONFLICT': {
        const remote = error.remote ?? (await this.fetchRemote(payload));
        return remote
          ? { kind: 'conflict', remote, requestId, payload }
          : {
              kind: 'rejected',
              requestId,
              payload,
              issue: issue('view.write.conflict-unreadable', []),
            };
      }
      default:
        return {
          kind: 'rejected',
          requestId,
          payload,
          issue: issue(`view.write.${error.code.toLowerCase()}`, [], {
            reason: error.message,
          }),
        };
    }
  }

  /** A store may report a conflict without the state it holds; ask for it. */
  private async fetchRemote(
    payload: WritePayload,
  ): Promise<ViewInstance | ViewPreferences | null> {
    try {
      if (payload.action === 'preferences')
        return await this.store.getPreferences(payload.definitionId);
      if (payload.action === 'create') return null;
      return await this.store.get(payload.id);
    } catch {
      return null;
    }
  }

  private async reload(
    state: Extract<WriteState, { kind: 'conflict' }>,
    runtime: ManagedViewRuntime | undefined,
  ): Promise<ViewInstance | ViewPreferences | void> {
    if (state.payload.action === 'preferences')
      return this.preferences(state.payload.definitionId);
    const remote = state.remote as ViewInstance;
    runtime?.adoptSaved(remote);
    this.summaries.set(remote.id, toSummary(remote));
    return remote;
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
    const runtime = [...this.runtimes].find(
      entry => entry.getSnapshot().saved?.id === id,
    );
    const known = runtime?.getSnapshot().saved ?? this.summaries.get(id);
    const summary = known ?? (await this.store.get(id));
    this.requireInstancePermission(summary, action);
    return { revision: summary.revision, runtime };
  }

  private requireWrite(target: WriteTarget): {
    requestId: string;
    state: WriteState;
  } {
    const requestId = isRuntime(target)
      ? target.getSnapshot().write?.requestId
      : target.id;
    const state = requestId ? this.writes.get(requestId) : undefined;
    if (!requestId || !state)
      throw new ViewCommandError(issue('view.write.not-pending', []));
    return { requestId, state };
  }

  private settle(requestId: string): void {
    this.writes.delete(requestId);
    this.owners.get(requestId)?.setWrite(null);
    this.owners.delete(requestId);
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

  private newRequestId(): string {
    return this.newId();
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
      scope === 'shared'
        ? permissions.createShared
        : permissions.createPersonal,
      'view.create.forbidden',
    );
  }

  private requireInstancePermission(
    instance: ViewInstanceSummary,
    action: keyof InstancePermissions,
  ): void {
    if (instance.scope === 'system')
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

function isRuntime(target: WriteTarget): target is ViewRuntime {
  return typeof (target as ViewRuntime).getSnapshot === 'function';
}

function capabilityOf(definition: ViewDefinition, config: ViewConfig): boolean {
  if (definition.kind !== 'data') return false;
  return config.kind === 'record'
    ? definition.record !== undefined
    : definition.analysis !== undefined;
}

function withRevision(
  payload: WritePayload,
  remote: ViewInstance | ViewPreferences,
): WritePayload {
  switch (payload.action) {
    case 'preferences':
      return {
        ...payload,
        next: { ...payload.next, revision: remote.revision },
      };
    case 'create':
      return payload;
    default:
      return { ...payload, revision: remote.revision };
  }
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
