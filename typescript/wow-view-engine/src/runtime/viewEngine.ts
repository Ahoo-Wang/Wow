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
  type FilterTree,
  parseSystemInstanceId,
  toSummary,
  type Issue,
  type RuntimeLimits,
  type TextResolver,
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
  ReadOptions,
  ViewPermissions,
  ViewStore,
} from '../store/ViewStore.js';
import {
  defaultRuntimeEnvironment,
  type RuntimeEnvironment,
} from './environment.js';
import { RequestRunner } from './requestRunner.js';
import { engineLabels, type ReferenceLabels } from './referenceLabels.js';
import type {
  ViewEngineOptions,
  ViewListing,
  ViewResource,
} from './viewEngineOptions.js';
import type { OptionSource } from './source.js';
import type {
  AnyViewRuntime,
  CreateInput,
  ManagedViewRuntime,
  OpenOptions,
  RuntimeFor,
  SaveAsInput,
  ViewRuntime,
} from './viewRuntimeTypes.js';
import type { DatasetWordingEditor } from './datasetResources.js';
import {
  DashboardViewRuntime,
  type DashboardRuntime,
} from './dashboardRuntime.js';
import { InstanceWrites } from './instanceWrites.js';
import { PanelViews } from './panelViews.js';
import type { WritePayload, WriteState } from './write.js';
import {
  WriteLedger,
  type ConflictChoice,
  type WriteLedgerHost,
  type WriteTarget,
} from './writeLedger.js';
import { ViewChanges, type ViewChangeListener } from './viewChanges.js';
import { type DefinitionRegistry, systemInstances } from './definitions.js';
import { PermissionGuard } from './permissions.js';
import { PreferenceCache, resolveDefault } from './preferences.js';
import { OpenRuntimes } from './openRuntimes.js';
import { storedSummaries, SummaryCache } from './summaries.js';
import { TabMemory } from './tabMemory.js';
import { RuntimeFactory, type RuntimeIdentity } from './runtimeFactory.js';
import { readingStore } from './storedViews.js';
import { SourceCapabilities } from './capabilities.js';
import { Resources } from './resources.js';
import { reportingStore } from './failure/failures.js';
import { requireSavable, requireTitle, storable } from './commandChecks.js';
import { publishCopy, readReferences } from './publish.js';

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
 *
 * What the host registered — the definitions, their sources, the words
 * their keys are said in — is held beside it (`resources.ts`), and the
 * members of the same names here hand on to it.
 *
 * Construct it; do not extend it.
 *
 * @sealed
 */
export class ViewEngine {
  /**
   * The host's store as the engine reads it: every view it hands back is
   * read into the form this engine writes on its way in (`readingStore`,
   * AGENTS.md's stored-data exception), and nothing past it migrates.
   */
  readonly store: ViewStore;
  readonly environment: RuntimeEnvironment;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;

  private readonly options: ViewEngineOptions;
  private readonly runner: RequestRunner;
  /** What each source admits; see `capabilities.ts`. */
  private readonly capabilities: SourceCapabilities;
  /** What the host registered; see `resources.ts`. */
  private readonly resources: Resources;
  private readonly registry: DefinitionRegistry;
  private readonly guard: PermissionGuard;
  private readonly preferenceCache: PreferenceCache;
  private readonly runtimes = new OpenRuntimes();
  private readonly factory: RuntimeFactory;
  /** Every write that left, and every outcome not yet settled. */
  private readonly ledger: WriteLedger;
  /** Saving a dashboard panel's view as a view of its own; see `panelViews.ts`. */
  private readonly panelViews: PanelViews;
  /** The writes addressed by id; see `instanceWrites.ts`. */
  private readonly writes: InstanceWrites;
  /** Who is told that a definition's list has changed; see `subscribe`. */
  private readonly changes = new ViewChanges(found =>
    this.resources.report(found),
  );
  /** The last summary seen of each instance; see `summaries.ts`. */
  private readonly summaries = new SummaryCache();
  /** Reference ids read as names, one cache for every view. */
  private readonly labels: ReferenceLabels;
  /** Where each reader last read each board; see `tabMemory.ts`. */
  private readonly tabs = new TabMemory({
    current: definitionId => this.preferenceCache.current(definitionId),
    write: (definitionId, next) => this.writePreferences(definitionId, next),
    abandon: handle => this.ledger.abandonWrite(handle),
  });

  constructor(options: ViewEngineOptions) {
    this.options = options;
    this.environment = options.environment ?? defaultRuntimeEnvironment();
    // Read in first, then watched: a store failure is told to the host's
    // `onError` once, whichever command or read it failed under (D40).
    this.store = reportingStore(readingStore(options.store), this.environment);
    this.kinds = options.kinds ?? builtinFieldKinds;
    this.capabilities = new SourceCapabilities({
      kinds: this.kinds,
      limits: options.limits,
      environment: this.environment,
      resolveSource: key => this.resources.resolveSource(key),
      declared: id => this.registry.definitions.get(id),
      report: found => this.resources.report(found),
    });
    // A source with a descriptor runs under its own (`effective`).
    this.limits = this.capabilities.fallback;
    this.runner = new RequestRunner(this.limits);
    this.resources = new Resources(options, this.kinds, this.limits);
    this.registry = this.resources.registry;
    this.labels = engineLabels(this);
    this.guard = new PermissionGuard(this.store);
    this.preferenceCache = new PreferenceCache(this.store);
    this.ledger = new WriteLedger(this.ledgerHost());
    this.writes = new InstanceWrites({
      store: this.store,
      guard: this.guard,
      definition: id => this.registry.require(id),
      prune: () => this.runtimes.prune(),
      holders: id => this.runtimes.holders(id),
      listed: id => this.summaries.get(id),
      maxDashboardPanels: this.limits.maxDashboardPanels,
      ledger: this.ledger,
    });
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
      resolveSource: key => this.resources.resolveSource(key),
      labels: this.labels,
      readInstance: id => this.readInstance(id),
      capabilities: this.capabilities,
      report: found => this.resources.report(found),
    });
  }

  /**
   * Every registered definition, by id, as it was declared: its keys
   * (`text(key)`) are said where they are shown (`useSay`, `say`).
   */
  get definitions(): ReadonlyMap<string, ViewDefinition> {
    return this.resources.definitions;
  }

  /**
   * Adds a resource at run time, or replaces the one of its definition's
   * id (host-integration.md 3.2): a dataset published after the engine was
   * built, its definition derived from its descriptor (`defineDataset`).
   * It is judged and its findings told as the resources given at
   * construction are; views opened after this run on it, those already
   * open keep the definition they opened on until reopened. Every open
   * board is asked again for the views it could not read for want of a
   * definition: a board over datasets opens before their directory is
   * read, and its panels come back once each dataset registers.
   */
  register(resource: ViewResource): void {
    this.resources.register(resource);
    for (const runtime of this.runtimes.all())
      if (runtime instanceof DashboardViewRuntime) runtime.retryReferences();
  }

  /**
   * Removes the resource of `definitionId`: a dataset retired, or one the
   * directory no longer lists. Views already open keep running on it;
   * opening another reports the definition as not found, as a saved view
   * of a resource never registered does. `false` when there was none.
   */
  unregister(definitionId: string): boolean {
    return this.resources.unregister(definitionId);
  }

  /**
   * Checks the words a Provider says the definitions' keys in: its
   * `messages`, in its `language` (host-integration.md 3.1). The Provider
   * calls this as they change; nothing is redrawn or rebuilt by it — the
   * keys are said where they are shown.
   *
   * The words set are checked on their own: a key they lack reads as
   * itself (`definition.text.unknown`), or in the words the engine started
   * with (`definition.text.fallback`) — a Chinese page showing an English
   * label is a gap too. A fallback is told only of words that say any of
   * the definitions' keys at all: words that say none (a Provider with no
   * catalogue of the definitions') leave them to the starting words, as
   * the host meant. Each key is told once per language, and again once
   * that language had it and lacks it again.
   */
  setText(text: TextResolver | undefined, language?: string): void {
    this.resources.setText(text, language);
  }

  /**
   * The words the engine was built with for `key` (`ViewEngineOptions.text`),
   * or `undefined`: what a surface says a key in where its Provider's words
   * lack it.
   */
  startingWord(key: string): string | undefined {
    return this.resources.startingWord(key);
  }

  /**
   * Whether a binding the host made (`bind`, host-integration.md 4) names a
   * registered definition. One that names none binds nothing — the routes,
   * the reading and the actions it carries would be lost without a word, a
   * misspelt id looking like a design — so it is told through `onIssue` as
   * `binding.definition.unknown`, once per id. A warning, not an error: a
   * binding may be meant for an engine nested further down (bindings pass
   * to inner hosts), or for a resource a feature flag left out, and a
   * binding that matches nothing breaks nothing. `ViewHost` asks this of
   * every binding it is given.
   */
  checkBinding(definitionId: string): boolean {
    return this.resources.checkBinding(definitionId);
  }

  /**
   * The editor of a dataset's wording, where its resource was registered
   * with one: what the view manager's wording tab reads and saves.
   */
  wordingOf(definitionId: string): DatasetWordingEditor | undefined {
    return this.resources.wordingOf(definitionId);
  }

  /** The remote candidates of `reference` fields under `key` (`ViewEngineOptions.resolveOptions`). */
  resolveOptions(key: string): OptionSource {
    return this.resources.resolveOptions(key);
  }

  /** What `validateDefinition` said about one definition, for a host to show. */
  definitionIssues(definitionId: string): Issue[] {
    return this.registry.issues(definitionId);
  }

  permissions(definitionId: string): ViewPermissions {
    return this.guard.of(definitionId);
  }

  /**
   * Code-declared system views first, then whatever the store holds. An id in
   * the reserved namespace is dropped and reported: only a definition may
   * declare one.
   */
  async list(
    definitionId: string,
    options: ReadOptions = {},
  ): Promise<ViewListing> {
    const definition = this.registry.require(definitionId);
    const declared = systemInstances(definition).map(toSummary);
    // The stored half may fail on its own: a store that is down takes the
    // saved views with it and nothing else. The declared views travel with
    // the definition, so they are listed regardless, and the failure is
    // said beside them rather than thrown over them — a list that threw
    // used to take the system views down too, and with them the one view
    // every definition promises to have (management.md).
    // The failure is the host's to hear of through `onError`, which the
    // store's own door already told (`reportingStore`); `onIssue` is for
    // findings with nothing thrown behind them (D40).
    const { stored, ...told } = await storedSummaries(
      this.store,
      definitionId,
      id =>
        this.resources.report(
          issue('view.list.reserved-id', [], { id }),
          definitionId,
        ),
      {
        limit: this.limits.listedViews,
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
    );
    const items = [...declared, ...stored];
    this.summaries.noteAll(items);
    // Titles as they are, keys and all: whatever shows a list says them.
    // The store's failure, and where it cut the list (`cut`), as it said.
    return { items, ...told };
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

  async preferences(
    definitionId: string,
    options: ReadOptions = {},
  ): Promise<ViewPreferences> {
    return this.preferenceCache.read(definitionId, options);
  }

  /** Opens a saved view, or a code-declared one without touching the store. */
  async open(
    instanceId: string,
    options: OpenOptions = {},
  ): Promise<AnyViewRuntime> {
    const { signal } = options;
    const instance = this.registry.ofDefinition(
      await this.readInstance(instanceId, signal),
      options.definitionId,
    );
    // What its sources admit is read before the first query, so that query
    // already goes out narrowed.
    await this.capabilities.prepareFor(instance);
    signal?.throwIfAborted();
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
    // Asked for no more while it opened: nobody holds it, so nobody would
    // close it.
    if (signal?.aborted) {
      this.runtimes.forget(runtime);
      signal.throwIfAborted();
    }
    runtime.apply();
    return runtime as AnyViewRuntime;
  }

  /** One instance, from the definition's code or from the store. */
  private async readInstance(
    instanceId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstance> {
    const declared = parseSystemInstanceId(instanceId);
    return declared
      ? this.registry.systemInstance(declared.definitionId, declared.viewId)
      : this.store.get(instanceId, signal === undefined ? {} : { signal });
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
    requireTitle(input.title);
    // Made at once, so it runs on the descriptor held; one not read yet is
    // read now, for the next view over the source.
    this.capabilities.warm(definition);

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
    requireSavable(target, target.issuesAt(state.scope));

    if (!state.saved) {
      const input = {
        definitionId: target.definition.id,
        title: requireTitle(state.title),
        scope: state.scope,
        config: storable(target, state.draft),
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
      config: storable(target, state.draft),
    };
    return (await this.ledger.dispatch(payload, target)) as ViewInstance;
  }

  /**
   * A copy under a new title and scope; the source runtime is untouched.
   * `scope: 'system'` makes it a stored system view, which needs
   * `editSystem` (D81).
   */
  async saveAs(
    runtime: ViewRuntime,
    input: SaveAsInput,
  ): Promise<ViewInstance> {
    const target = this.runtimes.require(runtime);
    const state = target.getSnapshot();
    // Judged at the scope it is going to, not the one it came from.
    requireSavable(target, target.issuesAt(input.scope));
    const title = requireTitle(input.title);
    this.guard.requireCreate(target.definition.id, input.scope);

    return (await this.ledger.dispatch(
      {
        action: 'create',
        input: {
          definitionId: target.definition.id,
          title,
          scope: input.scope,
          config: storable(target, state.draft),
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
    dashboard: DashboardRuntime,
    panelId: string,
    input: SaveAsInput,
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
    dashboard: DashboardRuntime,
    panelId: string,
    input: SaveAsInput,
  ): Promise<ViewInstance> {
    return this.panelViews.save(
      this.runtimes.require(dashboard),
      panelId,
      'saved',
      input,
    );
  }

  /**
   * 「发布为系统视图」 (D81): a copy of the saved view `id`, as it is saved —
   * not the draft of an open one — under its own title, created as a stored
   * system view. The view it was made from is left as it is; the copy is
   * the one everyone sees, and the one an admin edits from then on.
   *
   * Asked before anything is sent: `editSystem` (`view.create.forbidden`),
   * and the config's size, as for any create. The manager offers it on
   * personal and shared rows only; a system view is published already.
   */
  publishAsSystem(id: string): Promise<ViewInstance> {
    const read = async () => this.runtimes.saved(id) ?? this.store.get(id);
    return publishCopy(this.guard, this.ledger, id, read);
  }

  /** Renaming carries no config, so a draft with errors does not block it. */
  rename(id: string, title: string): Promise<ViewInstance> {
    return this.writes.rename(id, title);
  }

  /**
   * 设为共享／设为个人 (D18 item 10): the saved view moves to `audience` in
   * place, id kept, through the store's optional `changeAudience` — a store
   * without it is refused here (`view.changeAudience.unsupported`), and the
   * manager offers no such button. Like a rename it carries no config, so a
   * draft with errors does not block it, and every open view of the
   * instance moves to the new baseline.
   *
   * Asked before anything is sent: the instance permission and the create
   * permission of the audience it goes to (`instanceAbilities`). Asked for
   * the audience it already has, it answers the view as it is and writes
   * nothing.
   *
   * A board shared over a personal view is not refused, as a shared board's
   * save is not (`validateDashboard`, D22 B): the panel is blank for other
   * readers and says so (`dashboard.panel.scope-too-narrow`, a warning the
   * open board takes up as it moves). The one refusal between the two is the
   * store's: a view a shared board shows is not made personal (`INVALID`).
   */
  changeAudience(id: string, audience: ViewAudience): Promise<ViewInstance> {
    return this.writes.changeAudience(id, audience);
  }

  /**
   * 放进仪表盘 (R2-98): the saved view `instanceId` as one more panel on the
   * saved board `boardId`, at the first free place of its first tab and at
   * the size such a view starts at (`addPanel`), written as a save of the
   * board in place — its permission, its revision, its outcome.
   *
   * Refused before anything is sent: a board this reader may not save, an
   * id that is no board, a board as full as admission lets it be
   * (`view.addToDashboard.full`), and a board open on this page with edits
   * not yet saved (`view.addToDashboard.unsaved`) — a save under it would
   * either take those edits or leave them over a baseline they were not
   * made on. A board open and unedited takes the new board as it lands.
   */
  addToDashboard(boardId: string, instanceId: string): Promise<ViewInstance> {
    return this.writes.addToDashboard(boardId, instanceId);
  }

  delete(id: string): Promise<void> {
    return this.writes.delete(id);
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

  /**
   * Forgets the names reference ids were read as: of one remote, or
   * of all; of some ids, or every one. The views on screen read the ids
   * again as they draw, so a host that renamed a customer calls this and
   * every view says the new name.
   */
  invalidateLabels(remote?: string, ids?: Iterable<string | number>): void {
    this.labels.invalidate(remote, ids);
  }

  dispose(): void {
    this.runtimes.disposeAll();
    this.runner.cancelAll();
    this.capabilities.dispose();
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
      referenceOf: readReferences(this.summaries, this.store),
      noteChange: change => this.changes.emit(change),
    };
  }
}
