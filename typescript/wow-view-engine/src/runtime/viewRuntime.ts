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

import { dequal } from 'dequal';
import {
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DashboardViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type PagingMode,
  type RecordData,
  type RecordKey,
  type RecordPageTarget,
  type RecordViewConfig,
  type RuntimeLimits,
  type ViewConfig,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import {
  NO_REFUSAL,
  scopeRefusal,
  withScopeFilter,
  withoutScopeModeWarning,
} from './scope.js';
import type { RuntimeEnvironment } from './environment.js';
import { hasError, RuntimeStore } from './runtimeStore.js';
import { AUTO_APPLY_DELAY_MS, autoApplyDue } from './autoApply.js';
import { sourceReason } from './sourceReason.js';
import { RefreshTimer } from './refreshTimer.js';
import {
  isRequestSuperseded,
  RequestQueueFullError,
  type RequestRunner,
} from './requestRunner.js';
import type { OptionSource, ProjectedView, ViewSource } from './source.js';
import {
  executeDataConfig,
  firstPageOf,
  validateDataConfig,
  type DataViewConfig,
  type KernelContext,
} from './execute.js';
import {
  fetchExportRows,
  type ExportRowsOptions,
  type ExportedRows,
} from './exportRows.js';
import { fetchRecord } from './fetchRecord.js';
import { pageAfterShrink } from '../record/index.js';
import type { WriteState } from './write.js';
import type { DashboardRuntime } from './dashboardRuntime.js';

/**
 * One open view. A small store with `subscribe` and `getSnapshot`, so React
 * binds to it with `useSyncExternalStore` and nothing else is needed.
 *
 * The three states it keeps apart are the whole design: `draft` is what the
 * editor shows, `applied` is what was last executed, and `result` is what came
 * back, tagged with the config that produced it.
 */
export interface ViewRuntime<C extends ViewConfig = ViewConfig> {
  /** Runtime identity, distinct from the instance id: an unsaved view has one too. */
  readonly id: string;
  readonly kind: C['kind'];
  readonly definition: DefinitionFor<C>;
  /**
   * Fields the filter editor edits against: a data view's own, a dashboard's
   * declared global ones. A dashboard's set follows its draft, so read this
   * on every render rather than once per runtime.
   */
  readonly fields: readonly FieldDefinition[];
  /** The registry admission used, which an editor must edit against. */
  readonly kinds: FieldKindRegistry;
  /**
   * The budgets this view was admitted under. An editor offers within them
   * rather than offering a choice the kernel will then refuse: a page size
   * above `maxPageSize` is an error the user made by picking from a list the
   * UI drew, which is the UI's fault and not theirs.
   */
  readonly limits: RuntimeLimits;
  /**
   * The remote candidates behind a `reference` field's `remote` key, or
   * `null` when the host wired no `resolveOptions`. The editor asks here
   * rather than reaching for the engine: a runtime is what a workbench
   * holds, and a value editor two levels down has no engine to reach.
   */
  optionSource(remote: string): OptionSource | null;
  getSnapshot(): ViewRuntimeState<C>;
  subscribe(listener: () => void): () => void;
  /**
   * Changes the draft only, synchronously.
   *
   * A member given as `undefined` is **removed** rather than set to it. A
   * config is JSON, where a member that is not there and one that is
   * `undefined` are the same config but not the same object — and `dirty`
   * is an equality against the saved one, so setting it left a view
   * permanently unsaved and the leave guard asking about an edit the user
   * had already undone. An editor that takes the last entry out of an
   * optional list therefore says `undefined` and gets the config back as it
   * was.
   */
  edit(patch: Partial<C>): void;
  /** Promotes a valid draft to `applied` and executes it. */
  apply(): void;
  /**
   * Takes the draft back to the saved baseline and puts it in force again. A
   * view that was never saved has no baseline to return to, so it is a no-op
   * there; what is on screen is all there is.
   */
  revert(): void;
  /**
   * Re-runs `applied` from the first page. A no-op while `applied` was never
   * admitted: a view opened on a config the definition refuses waits for a
   * fix, and no command runs it as it stands.
   */
  refresh(): void;
  /** Called when an editor takes or loses focus; pauses auto-refresh. */
  setEditing(active: boolean): void;
  /**
   * Whether the draft runs on its own a moment after its question changes
   * (`autoApply.ts`); which members are the question is declared per kind
   * by the model (`autoRunMembers`), and the range still waits for `apply`.
   * Off by default: the workbench switches it on from the user's preference.
   */
  setAutoApply(on: boolean): void;
  /**
   * An outer condition ANDed onto the applied filter; never touches the draft.
   *
   * Returns what the condition was refused for, which is empty when it is in
   * force. A refusal changes nothing: the scope in force stays in force, the
   * result on screen stays on screen, and the host is told — the one thing
   * this view must never do is narrow less than the page asked without
   * saying so.
   */
  setScopeFilter(tree: FilterTree | null): Issue[];
  /**
   * What the scope last asked for was refused for, or empty while what was
   * asked for is in force.
   *
   * A refusal is the host's condition and not the view's defect, so it is not
   * among `state.issues` and does not stop the view (D17-5): a view opened
   * under a scope its definition cannot take runs un-narrowed and says this.
   * Read rather than only returned by `setScopeFilter`, because a scope goes
   * in at construction as well, and a host that opened one through
   * `ViewEngine.open` has no return value to read it from.
   */
  readonly refusedScope: Issue[];
  /**
   * The outer condition in force, as it was last admitted, or `null` while
   * none is — including a scope that was asked for and refused.
   *
   * It is read rather than only written because the conditions the rows came
   * back under are two things and not one: the view's own, which the editor
   * addresses and may take out, and the host's, which are in force and are
   * nobody's here to remove. A summary that reads the merged tree can tell
   * neither apart — see `ViewResult.own`.
   */
  readonly scopeFilter: FilterTree | null;
  /**
   * The host this view runs against: its clock, its timers, its visibility.
   *
   * Read rather than only written because `nextRefreshAt` is a reading of
   * *this* clock and means nothing against another one. A countdown that
   * asked the system clock would drift away from the timer it claims to be
   * counting to the moment a test, a demo or a server-rendered page injected
   * a clock of its own — which is the whole reason the environment exists.
   * `ViewEngine` already hands the same object out for its time zone.
   */
  readonly environment: RuntimeEnvironment;
  dispose(): void;
  /** True once disposed: every command is a no-op from then on. */
  readonly disposed: boolean;
}

/**
 * The definition a config belongs to. A dashboard owns no data, so its
 * definition is a catalogue entry with no fields and no capabilities.
 */
export type DefinitionFor<C extends ViewConfig> = C extends DashboardViewConfig
  ? DashboardDefinition
  : DataViewDefinition;

/** Paging and selection belong to Record alone. */
export interface RecordViewRuntime<
  P extends PagingMode = PagingMode,
> extends ViewRuntime<RecordViewConfig> {
  page(target: RecordPageTarget<P>): void;
  select(keys: RecordKey[]): void;
  /**
   * Every row the **applied** conditions match, paged out behind the screen
   * for an export — the same filter and sort, without the page on screen.
   *
   * It runs beside the view rather than through it: no scheduler slot, no
   * `apply`, and `state.result` is untouched, so the rows the user is reading
   * stay exactly as they are while a long export runs. Stopped by
   * `options.signal`, capped at `limits.exportMax`.
   */
  exportRows(options?: ExportRowsOptions): Promise<ExportedRows>;
  /**
   * One record, whole, by its row key — for a detail panel (`fetchRecord`):
   * every field, within the injected scope and not the page's conditions.
   * `null` when it is no longer there.
   */
  fetchRecord(key: RecordKey, signal?: AbortSignal): Promise<RecordData | null>;
}

/** What opening an instance returns; narrow it by `runtime.kind`. */
export type AnyViewRuntime =
  RecordViewRuntime | ViewRuntime<AnalysisViewConfig> | DashboardRuntime;

/**
 * What `ViewEngine` needs beyond the public contract: admission at the scope
 * a write is headed for, and the three ways an outcome reaches an open view.
 * Both runtime classes implement it, which is how the engine stays
 * indifferent to the kind.
 */
export interface ManagedViewRuntime<
  C extends ViewConfig = ViewConfig,
> extends ViewRuntime<C> {
  /**
   * Admission of the draft as it would stand at a target scope, which is what
   * "save as shared" has to ask: a dashboard may reference views the people it
   * would be shared with cannot read.
   */
  issuesAt(scope: ViewScope): Issue[];
  /** Advances the saved baseline once the store has confirmed this view's write. */
  markSaved(instance: ViewInstance): void;
  /**
   * Advances the baseline because a write elsewhere moved it: the same
   * instance open in another view, or renamed from the list. Whatever write
   * of this view's own is still unsettled stays so, for its recovery actions.
   */
  moveBaseline(instance: ViewInstance): void;
  /** Replaces the draft with the store's state, for "reload" on a conflict. */
  adoptSaved(instance: ViewInstance): void;
  setWrite(write: WriteState | null): void;
}

/** Keeps the narrow type through `create`, which knows its config statically. */
export type RuntimeFor<C extends ViewConfig> = C extends RecordViewConfig
  ? RecordViewRuntime
  : ViewRuntime<C>;

export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';

export interface ViewQueryState {
  status: QueryStatus;
  error?: Issue;
  requestId?: string;
}

export interface ViewResult<C> {
  /** The config that produced this data, scope filter included. */
  config: C;
  /**
   * The view's own half of it: the `applied` config as it was promoted,
   * before the scope filter was merged in.
   *
   * A summary of the conditions in force addresses the draft through this one.
   * `mergeFilters` appends the scope as a trailing group, and wraps an `or` or
   * `nor` draft as the first child of an `and`, so a path into `config.filter`
   * addresses neither the draft's tree nor anything the editor may remove —
   * and the host's own condition would sit in the bar looking removable.
   * Equal to `config` when nothing is injected, and for a dashboard, which
   * runs no query of its own.
   */
  own: C;
  data: ProjectedView;
  receivedAt: number;
}

export interface ViewRuntimeState<C> {
  /** The saved baseline; `null` while the view has never been saved. */
  saved: ViewInstance | null;
  title: string;
  scope: ViewScope;
  draft: C;
  applied: C;
  /**
   * Admission of the draft as it would run: with the injected scope filter
   * ANDed in, because that is the config `apply` executes. An `error` blocks
   * `apply` and every write. The scope is appended after the draft's own
   * conditions, so a path into the draft's tree is unchanged by it.
   */
  issues: Issue[];
  dirty: boolean;
  query: ViewQueryState;
  result: ViewResult<C> | null;
  /** Row keys of the current result only; cleared when the result changes. */
  selection: RecordKey[];
  write: WriteState | null;
  editing: boolean;
  /** See `ViewRuntime.setAutoApply`. */
  autoApply: boolean;
  /**
   * When the next automatic refresh is due, on the environment's clock, or
   * `null` while no timer is armed — no interval in force, or one of the four
   * reasons the runtime holds it.
   *
   * It is the timer's own due time rather than a second opinion about it: set
   * where the timer is armed, cleared where it is stopped. A control counting
   * down to the next refresh reads this against `environment.now()`, so what
   * it says and what will happen cannot come apart; a countdown run off a
   * clock of its own would.
   */
  nextRefreshAt: number | null;
}

/**
 * Whether this view ever got a result, even one that is now out of date.
 *
 * Not `rows.length > 0` and not `status === 'success'`: a failed refresh
 * keeps the rows it could not replace and turns to `error`, and a successful
 * result matching zero rows has no rows at all. The table, the applied bar
 * and the result block all ask the same question of the same member, so they
 * ask it here — a spelling of `state.result != null` at every call site is
 * one place each for it to start meaning something else.
 *
 * Structural in its argument, because it is asked of whatever holds a
 * snapshot: a runtime's state, a controller's, or nothing yet.
 */
export function hasResult(
  state: { result: unknown } | null | undefined,
): boolean {
  return state?.result != null;
}

export interface ViewRuntimeOptions<C extends DataViewConfig> {
  id: string;
  definition: DataViewDefinition;
  config: C;
  title: string;
  scope: ViewScope;
  saved?: ViewInstance | null;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  source: ViewSource;
  runner: RequestRunner;
  /** See `ViewRuntime.optionSource`. */
  resolveOptions?(key: string): OptionSource;
  /** An outer condition in force from the first execution on. */
  scopeFilter?: FilterTree | null;
  /**
   * False inside a dashboard, which times the refresh of every panel itself
   * rather than letting each one run a timer of its own.
   */
  autoRefresh?: boolean;
}

const IDLE: ViewQueryState = { status: 'idle' };

/**
 * The draft with `patch` over it, where a member given as `undefined` is
 * removed rather than set to it.
 *
 * A config is JSON. A member that is not there and a member that is
 * `undefined` are the same config, but not the same object, and `dirty` is
 * an equality against the saved one — so an editor that took the last entry
 * out of an optional list left the view unsaved for the rest of the session,
 * with the leave guard asking about an edit that had already been undone.
 */
function patched<C extends object>(draft: C, patch: Partial<C>): C {
  const next: Record<string, unknown> = { ...draft, ...patch };
  for (const [key, value] of Object.entries(patch))
    if (value === undefined) delete next[key];
  return next as C;
}

/**
 * The runtime of a Record or an Analysis view.
 *
 * It owns no persistence: saving is a command of `ViewEngine`, which calls
 * `markSaved` once the store has confirmed it.
 *
 * The store half of it — the snapshot, the subscribers, the refresh timer and
 * dirty-against-saved — is `RuntimeStore`, which the dashboard runtime holds
 * one of as well; what is left here is what it means to be a data view.
 */
export class DataViewRuntime<
  C extends DataViewConfig = DataViewConfig,
> implements ManagedViewRuntime<C> {
  readonly id: string;
  readonly kind: C['kind'];
  readonly definition: DefinitionFor<C>;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;

  private readonly store: RuntimeStore<ViewRuntimeState<C>>;
  private readonly context: KernelContext;
  private readonly runner: RequestRunner;
  private readonly resolveOptions: ((key: string) => OptionSource) | undefined;
  private readonly autoRefresh: boolean;
  /** The one timer behind 「改了就跑」, stopped whenever nothing is due. */
  private readonly autoTimer: RefreshTimer;

  private injectedScope: FilterTree | null = null;
  /**
   * Whether `applied` merged with the scope passed admission. `apply` and
   * `setScopeFilter` only promote what did, so this is false only for the
   * config a runtime opened on, and it keeps `refresh` and `page` from running
   * what `apply` would refuse.
   */
  private appliedAdmitted: boolean;
  private pageTarget: RecordPageTarget | undefined;
  private requestSeq = 0;

  constructor(options: ViewRuntimeOptions<C>) {
    this.id = options.id;
    this.kind = options.config.kind;
    // `C extends DataViewConfig` makes `DefinitionFor<C>` a data definition,
    // which the compiler cannot prove while `C` is still a parameter.
    this.definition = options.definition as DefinitionFor<C>;
    this.kinds = options.kinds;
    this.limits = options.limits;
    this.resolveOptions = options.resolveOptions;
    this.runner = options.runner;
    this.environment = options.environment;
    this.autoRefresh = options.autoRefresh ?? true;
    this.autoTimer = new RefreshTimer(options.environment, () => this.apply());
    this.context = {
      definition: options.definition,
      kinds: options.kinds,
      limits: options.limits,
      environment: options.environment,
      source: options.source,
    };

    const saved = options.saved ?? null;
    this.pageTarget = firstPageOf(options.definition);
    // An injected condition is in force from the first query, so it is
    // admitted with the config rather than after it. Without this, a host
    // that scopes a view to one customer would have its opening query go
    // out unscoped. What the definition refuses, though, is the host's
    // condition and not this view's defect, so it is left out rather than
    // written into the view's issues (D17-5): the view runs un-narrowed and
    // `refusedScope` says which condition did not take — the same answer a
    // scope refused later gets, said in the same words.
    const wanted = options.scopeFilter ?? null;
    const own = this.admit(options.config, null);
    const merged = wanted === null ? own : this.admit(options.config, wanted);
    const refusedScope = scopeRefusal(own, merged);
    const refused = refusedScope.length > 0;
    this.injectedScope = refused ? null : wanted;
    const issues = refused ? own : merged;
    this.appliedAdmitted = !hasError(issues);
    this.store = new RuntimeStore<ViewRuntimeState<C>>({
      state: {
        saved,
        title: options.title,
        scope: options.scope,
        draft: options.config,
        applied: options.config,
        issues,
        dirty: saved === null,
        query: IDLE,
        result: null,
        selection: [],
        write: null,
        editing: false,
        autoApply: false,
        nextRefreshAt: null,
      },
      environment: options.environment,
      refusedScope,
      admit: draft => this.admit(draft),
      apply: () => this.apply(),
      refresh: () => this.refresh(),
      // A panel inside a dashboard is timed by the board, so it holds its own
      // timer for good; otherwise it is held while its one request is in flight.
      // A selection holds it too: a refresh can move or drop the very rows
      // someone has picked to act on.
      holding: () =>
        !this.autoRefresh ||
        this.state.query.status === 'loading' ||
        this.state.selection.length > 0,
      release: () => this.runner.cancel(this.id),
    });
  }

  get disposed(): boolean {
    return this.store.disposed;
  }

  /** See `ViewRuntime.refusedScope`; the store keeps it. */
  get refusedScope(): Issue[] {
    return this.store.refusedScope;
  }

  /** The snapshot the store holds; every command reads it and patches it back. */
  private get state(): ViewRuntimeState<C> {
    return this.store.state;
  }

  get fields(): readonly FieldDefinition[] {
    return this.context.definition.fields;
  }

  /** The injected condition in force; see `ViewRuntime.scopeFilter`. */
  get scopeFilter(): FilterTree | null {
    return this.injectedScope;
  }

  getSnapshot(): ViewRuntimeState<C> {
    return this.store.getSnapshot();
  }

  subscribe(listener: () => void): () => void {
    return this.store.subscribe(listener);
  }

  optionSource(remote: string): OptionSource | null {
    return this.resolveOptions ? this.resolveOptions(remote) : null;
  }

  edit(patch: Partial<C>): void {
    if (this.disposed) return;
    const draft = patched(this.state.draft, patch);
    this.store.setState({
      draft,
      issues: this.admit(draft),
      dirty: this.store.isDirty(draft, this.state.saved),
    });
    this.syncAutoApply();
  }

  apply(): void {
    if (this.disposed || hasError(this.state.issues)) return;
    this.appliedAdmitted = true;
    this.pageTarget = firstPageOf(this.context.definition);
    this.store.setState({ applied: this.state.draft, selection: [] });
    this.autoTimer.stop();
    this.execute({ keepSelection: false });
  }

  /** Discards the edits and re-runs what was saved; see `RuntimeStore.revert`. */
  revert(): void {
    this.store.revert();
    this.syncAutoApply();
  }

  setAutoApply(on: boolean): void {
    if (this.disposed || this.state.autoApply === on) return;
    this.store.setState({ autoApply: on });
    this.syncAutoApply();
  }

  /**
   * Arms the auto-apply timer while the draft is due to run on its own and
   * stops it otherwise. Re-arming on every edit is what merges a burst of
   * edits into one query: the timer restarts from the last one.
   */
  private syncAutoApply(): void {
    if (this.disposed || !autoApplyDue(this.state)) {
      this.autoTimer.stop();
      return;
    }
    this.autoTimer.stop();
    this.autoTimer.sync(AUTO_APPLY_DELAY_MS);
  }

  /**
   * Re-runs what was applied, which was admitted before it ran. An invalid
   * draft therefore does not block it: the editor may be mid-edit and wrong,
   * while the results on screen answer a question that was legal when asked.
   * Auto-refresh still pauses on an invalid draft — it is one of the reasons
   * `syncTimer` below holds the timer — but a user pressing Refresh has asked
   * for exactly this.
   */
  refresh(): void {
    if (this.disposed || !this.appliedAdmitted) return;
    // A refresh reads the page the reader is on again — the timer's, the
    // button's and the one a command asks for after writing alike: sending
    // them to the first page lost their place in a list they were working
    // through. The selection keeps whatever rows survive.
    this.execute({ keepSelection: true });
  }

  page(target: RecordPageTarget): void {
    if (this.disposed || !this.appliedAdmitted) return;
    this.pageTarget = target;
    this.store.setState({ selection: [] });
    this.execute({ keepSelection: false });
  }

  /**
   * The applied config's rows, all of them, for an export.
   *
   * It reads `applied` merged with the scope — the very config the result on
   * screen came from — and goes straight to the source: the request runner is
   * the view's own lane and an export must neither queue behind the view nor
   * push it aside. Declared on `RecordViewRuntime` alone, and an analysis
   * runtime, which shares this class, answers that it has no rows to export.
   */
  exportRows(options: ExportRowsOptions = {}): Promise<ExportedRows> {
    const applied: DataViewConfig = this.state.applied;
    if (this.disposed || applied.kind !== 'record')
      return Promise.reject(
        new Error(`View ${this.id} has no record rows to export`),
      );
    return fetchExportRows(
      this.context,
      withScopeFilter(applied as C, this.injectedScope) as RecordViewConfig,
      options,
    );
  }

  fetchRecord(
    key: RecordKey,
    signal?: AbortSignal,
  ): Promise<RecordData | null> {
    const applied: DataViewConfig = this.state.applied;
    if (this.disposed || applied.kind !== 'record')
      return Promise.reject(
        new Error(`View ${this.id} has no records to open`),
      );
    return fetchRecord(this.context, applied, this.injectedScope, key, signal);
  }

  select(keys: RecordKey[]): void {
    if (this.disposed) return;
    const available = this.resultKeys();
    const selection = available
      ? keys.filter(key => available.has(key))
      : [...keys];
    this.store.setState({ selection });
  }

  setEditing(active: boolean): void {
    if (this.disposed || this.state.editing === active) return;
    this.store.setState({ editing: active });
  }

  setScopeFilter(tree: FilterTree | null): Issue[] {
    if (this.disposed) return this.refusedScope;
    // Re-injecting the same condition changes nothing, and a dashboard does
    // exactly that whenever a layout edit is applied. What is asked for is
    // what is in force, so nothing stands refused either.
    if (dequal(tree ?? null, this.injectedScope))
      return this.store.refuse(NO_REFUSAL);
    const own = this.admit(this.state.applied, null);
    const merged = this.admit(this.state.applied, tree);
    // An injected condition is admitted exactly like a user's own — but only
    // what it alone breaks keeps it out. A view already waiting to be fixed
    // is not fixed by refusing the host's condition too.
    if (this.store.refuse(scopeRefusal(own, merged)).length > 0)
      return this.refusedScope;

    this.injectedScope = tree ?? null;
    this.appliedAdmitted = !hasError(merged);
    this.pageTarget = firstPageOf(this.context.definition);
    // The draft is judged with the scope too, so its issues move with it.
    this.store.setState({
      issues: this.admit(this.state.draft),
      selection: [],
    });
    if (this.appliedAdmitted) this.execute({ keepSelection: false });
    return this.refusedScope;
  }

  /** A record or an analysis config means the same thing in every scope. */
  issuesAt(): Issue[] {
    return this.state.issues;
  }

  /** Called by `ViewEngine` once a write has been confirmed by the store. */
  markSaved(instance: ViewInstance): void {
    if (this.disposed) return;
    this.moveBaseline(instance);
    this.store.setState({ write: null });
  }

  moveBaseline(instance: ViewInstance): void {
    if (this.disposed) return;
    this.store.setState({
      saved: instance,
      title: instance.title,
      scope: instance.scope,
      dirty: this.store.isDirty(this.state.draft, instance),
    });
  }

  /** Replaces the draft with the store's state, used by "reload" on a conflict. */
  adoptSaved(instance: ViewInstance): void {
    if (this.disposed) return;
    const draft = instance.config as C;
    this.store.setState({
      saved: instance,
      title: instance.title,
      scope: instance.scope,
      draft,
      issues: this.admit(draft),
      dirty: false,
      write: null,
    });
  }

  /** Called by `ViewEngine` with the outcome of a write it dispatched. */
  setWrite(write: WriteState | null): void {
    if (this.disposed) return;
    this.store.setState({ write });
  }

  dispose(): void {
    this.autoTimer.stop();
    this.store.dispose();
  }

  private resultKeys(): Set<RecordKey> | null {
    const data = this.state.result?.data;
    if (!data || data.kind !== 'record') return null;
    return new Set(data.view.rows.map(row => row.key));
  }

  /**
   * Admission of a config together with the scope it would run under. Every
   * judgement in this class goes through here, so the draft, the applied
   * config and an injected condition are all held to one rule.
   */
  private admit(
    config: C,
    scope: FilterTree | null = this.injectedScope,
  ): Issue[] {
    return withoutScopeModeWarning(
      validateDataConfig(this.context, withScopeFilter(config, scope)),
      config,
      scope,
    );
  }

  private execute(options: { keepSelection: boolean }): void {
    // Both halves travel with the request: what ran, and the view's own
    // config it was merged from. `applied` may move on before the answer
    // arrives, and a summary reading it would describe another question.
    const own = this.state.applied;
    const config = withScopeFilter(own, this.injectedScope);
    const requestId = `${this.id}:${(this.requestSeq += 1)}`;
    this.store.setState({ query: { status: 'loading', requestId } });

    this.runner
      .run(this.id, controller =>
        executeDataConfig(this.context, config, this.pageTarget, controller),
      )
      .then(
        data =>
          this.onSuccess(requestId, config, own, data, options.keepSelection),
        error => this.onFailure(requestId, error),
      );
  }

  private onSuccess(
    requestId: string,
    config: C,
    own: C,
    data: ProjectedView,
    keepSelection: boolean,
  ): void {
    if (!this.isCurrent(requestId)) return;
    // A page the result shrank out from under lands on the last page there
    // is, rather than on an empty page with rows before it.
    const back = this.pageAfterShrink(data);
    if (back !== null) {
      this.pageTarget = { index: back };
      this.execute({ keepSelection });
      return;
    }
    const receivedAt = this.environment.now().getTime();
    const result = { config, own, data, receivedAt };
    const selection = keepSelection
      ? this.retainSelection(data)
      : this.state.selection;
    this.store.setState({
      query: { status: 'success', requestId },
      result,
      ...(selection === this.state.selection ? {} : { selection }),
    });
  }

  private pageAfterShrink(data: ProjectedView): number | null {
    const target = this.pageTarget;
    if (data.kind !== 'record' || !target || !('index' in target)) return null;
    return pageAfterShrink(
      data.view.paging,
      target.index,
      data.view.rows.length,
    );
  }

  private retainSelection(data: ProjectedView): RecordKey[] {
    if (this.state.selection.length === 0 || data.kind !== 'record')
      return this.state.selection;
    const keys = new Set(data.view.rows.map(row => row.key));
    const retained = this.state.selection.filter(key => keys.has(key));
    return retained.length === this.state.selection.length
      ? this.state.selection
      : retained;
  }

  private onFailure(requestId: string, error: unknown): void {
    // A superseded request is the normal outcome of typing; it is not an error.
    if (isRequestSuperseded(error) || !this.isCurrent(requestId)) return;
    // The source's own reason may take reading the body it answered with
    // (`sourceReason`); the query stays in flight until it is read, and a
    // newer request that started meanwhile wins.
    void queryIssue(error).then(error => {
      if (!this.isCurrent(requestId)) return;
      this.store.setState({ query: { status: 'error', error, requestId } });
    });
  }

  private isCurrent(requestId: string): boolean {
    return !this.disposed && this.state.query.requestId === requestId;
  }
}

/** Turns a failed execution into the Issue the UI reports. */
async function queryIssue(error: unknown): Promise<Issue> {
  if (error instanceof RequestQueueFullError)
    return issue('runtime.query.queue-full', []);
  return issue('runtime.query.failed', [], {
    reason: await sourceReason(error),
  });
}
