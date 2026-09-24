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
  OpenOptions,
  RuntimeFor,
  ViewRuntime,
} from './viewRuntimeTypes.js';
import { DashboardViewRuntime, stopsSave } from './dashboardRuntime.js';
import { PanelViews } from './panelViews.js';
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
import { toIssue } from './issues.js';
import { PermissionGuard } from './permissions.js';
import { PreferenceCache, resolveDefault } from './preferences.js';
import { OpenRuntimes } from './openRuntimes.js';
import { SummaryCache } from './summaries.js';
import { TabMemory } from './tabMemory.js';
import { RuntimeFactory, type RuntimeIdentity } from './runtimeFactory.js';
import { readingStore } from './storedViews.js';

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

/** The ledger names what a write command is addressed to; the engine takes it. */
export type { ConflictChoice, WriteTarget } from './writeLedger.js';

/** Instances a definition declares in code, and the order a user put them in. */
export { systemInstances } from './definitions.js';

/**
 * What a definition's list is right now: every view that could be read, and
 * the reason the rest could not. The declared system views are always in
 * `items`; `failed` names the store's failure when the saved ones are not.
 */
export interface ViewListing {
  items: ViewInstanceSummary[];
  failed: Issue | null;
}
export { orderSummaries } from './preferences.js';

export interface CreateInput<C extends ViewConfig> {
  title: string;
  /** A user creates for an audience; only a definition declares a system view. */
  scope: ViewAudience;
  config: C;
  /**
   * The host's injected condition, as `open` takes it: a view made from an
   * open one — a drill-through — inherits the scope its origin ran under,
   * so it never shows a row the page around it was narrowed away from.
   */
  scopeFilter?: FilterTree | null;
}

/**
 * The registry and the command entry point: definitions in, runtimes and
 * writes out.
 *
 * What the commands stand on lives beside this file, one concern each: the
 * definition registry (`definitions.ts`), the permission checks
 * (`permissions.ts`), the preference cache (`preferences.ts`), the summary
 * cache (`summaries.ts`), the open views (`openRuntimes.ts`), how one runtime
 * is assembled (`runtimeFactory.ts`) and the write ledger
 * (`writeLedger.ts`). What is left here is the command surface itself:
 * admission, then one dispatch.
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
  /**
   * The host's store as the engine reads it: every view it hands back is
   * read into the form this engine writes on its way in (`readingStore`,
   * AGENTS.md's stored-data exception), and nothing past it migrates.
   */
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
  /** Saving a dashboard panel's view as a view of its own; see `panelViews.ts`. */
  private readonly panelViews: PanelViews;
  /** Who is told that a definition's list has changed; see `subscribe`. */
  private readonly changes = new ViewChanges(found => this.report(found));
  /** The last summary seen of each instance; see `summaries.ts`. */
  private readonly summaries = new SummaryCache();
  /** Where each reader last read each board; see `tabMemory.ts`. */
  private readonly tabs = new TabMemory({
    current: definitionId => this.preferenceCache.current(definitionId),
    write: (definitionId, next) => this.writePreferences(definitionId, next),
    abandon: handle => this.ledger.abandonWrite(handle),
  });

  constructor(options: ViewEngineOptions) {
    this.options = options;
    this.store = readingStore(options.store);
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
    this.panelViews = new PanelViews({
      registry: this.registry,
      kinds: this.kinds,
      limits: this.limits,
      guard: this.guard,
      ledger: this.ledger,
    });
    this.factory = new RuntimeFactory({
      definitions: this.registry,
      kinds: this.kinds,
      limits: this.limits,
      environment: this.environment,
      runner: this.runner,
      resolveSource: key => this.resolveSource(key),
      // Left out rather than a thrower: a runtime asks whether a source
      // exists, and an editor with no source falls back to typed entry.
      ...(this.options.resolveOptions
        ? { resolveOptions: (key: string) => this.resolveOptions(key) }
        : {}),
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
  async list(definitionId: string): Promise<ViewListing> {
    const definition = this.registry.require(definitionId);
    const declared = systemInstances(definition).map(toSummary);
    // The stored half may fail on its own: a store that is down takes the
    // saved views with it and nothing else. The declared views travel with
    // the definition, so they are listed regardless, and the failure is
    // said beside them rather than thrown over them — a list that threw
    // used to take the system views down too, and with them the one view
    // every definition promises to have (management.md).
    let stored: ViewInstanceSummary[] = [];
    let failed: Issue | null = null;
    try {
      stored = await this.store.list(definitionId);
    } catch (error) {
      failed = toIssue(error, 'view.list.failed');
      this.report(failed);
    }
    const accepted = stored.filter(summary => {
      if (!isSystemInstanceId(summary.id)) return true;
      this.report(issue('view.list.reserved-id', [], { id: summary.id }));
      return false;
    });
    const items = [...declared, ...accepted];
    this.summaries.noteAll(items);
    return { items, failed };
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
    // A dashboard opens on its tab rather than on its first and then there:
    // only the tab on screen runs, so where it starts is what is asked.
    const tab = await this.tabs.opensOn(instance, options.tab);
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
   *
   * No permission is asked here: nothing is written until the first save,
   * and `save` asks then. A reader who may create nothing still drills from
   * a chart into the records behind a bar — a view that is only looked at
   * costs the store nothing (H1).
   */
  create<C extends ViewConfig>(
    definitionId: string,
    input: CreateInput<C>,
  ): RuntimeFor<C> {
    const definition = this.registry.require(definitionId);
    this.requireTitle(input.title);

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

  /** First save creates, later saves overwrite. Both need a clean draft. */
  async save(runtime: ViewRuntime): Promise<ViewInstance> {
    const target = this.runtimes.require(runtime);
    const state = target.getSnapshot();
    this.requireSavable(target, target.issuesAt(state.scope));

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
    this.requireSavable(target, target.issuesAt(input.scope));
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

  /**
   * Saves a view a dashboard owns as a view of its own (「另存为视图」, D22 C)
   * and points the panel at it (`PanelViews`). A view the board does not
   * own, or a panel it lacks, is refused (`dashboard.panel.not-owned`).
   */
  saveOwnedView(
    dashboard: ViewRuntime,
    panelId: string,
    input: { title: string; scope: ViewAudience },
  ): Promise<ViewInstance> {
    return this.panelViews.save(
      this.runtimes.require(dashboard),
      panelId,
      'owned',
      input,
    );
  }

  /**
   * Copies the saved view a dashboard panel shows into another audience and
   * points the panel at the copy (「复制为共享视图并替换」, D22 B,
   * `PanelViews`): a shared board standing on someone's personal view is
   * blank for every other reader, and the copy is what they can open. A
   * panel showing no saved view this reader has open is refused
   * (`dashboard.panel.not-referenced`).
   */
  copyPanelView(
    dashboard: ViewRuntime,
    panelId: string,
    input: { title: string; scope: ViewAudience },
  ): Promise<ViewInstance> {
    return this.panelViews.save(
      this.runtimes.require(dashboard),
      panelId,
      'saved',
      input,
    );
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
   * Whether this user's analyses of the definition run again on their own as
   * they are edited (D20 改了就跑). A personal preference like the order and
   * the default, kept beside them; no permission gates it, because it
   * changes nothing anyone else sees.
   */
  async setAutoRun(
    definitionId: string,
    autoRun: boolean,
  ): Promise<ViewPreferences> {
    const current = await this.preferenceCache.current(definitionId);
    return this.writePreferences(definitionId, { ...current, autoRun });
  }

  /**
   * Remembers the tab this reader last read a dashboard on (D22 E), kept
   * with their other preferences for the board's definition and read back
   * by `open`. A convenience: it never rejects and never leaves an outcome
   * behind (`TabMemory`).
   */
  rememberTab(
    definitionId: string,
    instanceId: string,
    tabId: string,
  ): Promise<void> {
    return this.tabs.remember(definitionId, instanceId, tabId);
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
      noteInstance: instance => this.summaries.note(instance),
      dropInstance: (id, owner) => {
        this.summaries.drop(id);
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

  /** What stops a save of this runtime's kind (`stopsSave`). */
  private requireSavable(
    runtime: ManagedViewRuntime,
    issues: readonly Issue[],
  ): void {
    if (stopsSave(runtime.kind, issues))
      throw new ViewCommandError(issue('view.config.invalid', []));
  }
}
