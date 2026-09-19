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
  MAX_TIMER_DELAY_MS,
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DashboardViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type PagingMode,
  type RecordKey,
  type RecordPageTarget,
  type RecordViewConfig,
  type RuntimeLimits,
  type ViewConfig,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import {
  isFilterGroup,
  isSimpleTree,
  issue,
  mergeFilters,
  type FieldKindRegistry,
} from '../filter/index.js';
import type { RuntimeEnvironment } from './environment.js';
import {
  isRequestSuperseded,
  RequestQueueFullError,
  type RequestRunner,
} from './requestRunner.js';
import type { ProjectedView, ViewSource } from './source.js';
import {
  executeDataConfig,
  firstPageOf,
  validateDataConfig,
  type DataViewConfig,
  type KernelContext,
} from './execute.js';
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
  getSnapshot(): ViewRuntimeState<C>;
  subscribe(listener: () => void): () => void;
  /** Changes the draft only, synchronously. */
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
  /** An outer condition ANDed onto the applied filter; never touches the draft. */
  setScopeFilter(tree: FilterTree | null): Issue[];
  /**
   * The outer condition in force, as `setScopeFilter` last admitted it, or
   * `null` while none is injected.
   *
   * It is read rather than only written because the conditions the rows came
   * back under are two things and not one: the view's own, which the editor
   * addresses and may take out, and the host's, which are in force and are
   * nobody's here to remove. A summary that reads the merged tree can tell
   * neither apart — see `ViewResult.own`.
   */
  readonly scopeFilter: FilterTree | null;
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
 * Findings on a config judged with its scope, read as findings on the config.
 *
 * Two things the merge does must not leak into the issues. The `filterMode`
 * warning judges what the editor can show, which is the config's own tree;
 * the merged tree is never simple, so the warning is dropped when the draft
 * itself is simple. And a root that is not `and` rides in the merged tree
 * as its first child, so a finding at `['children', 0, …]` is a finding at
 * `[…]` of the draft, and is addressed so — every reader of `issues` reads
 * a path against the draft.
 */
export function withoutScopeModeWarning(
  issues: Issue[],
  config: ViewConfig,
  scope: FilterTree | null,
): Issue[] {
  if (!scope || !isFilterGroup(config.filter)) return issues;
  const nested = config.filter.op !== 'and';
  const simple = isSimpleTree(config.filter);
  return issues.flatMap(found => {
    if (simple && found.code === 'config.filterMode.not-simple') return [];
    if (nested && found.path[0] === 'children' && found.path[1] === 0)
      return [{ ...found, path: found.path.slice(2) }];
    return [found];
  });
}

/** An `error` blocks apply and every write; a `warning` only reports. */
export function hasError(issues: readonly Issue[]): boolean {
  return issues.some(entry => entry.severity === 'error');
}

/**
 * The runtime of a Record or an Analysis view.
 *
 * It owns no persistence: saving is a command of `ViewEngine`, which calls
 * `markSaved` once the store has confirmed it.
 */
export class DataViewRuntime<
  C extends DataViewConfig = DataViewConfig,
> implements ManagedViewRuntime<C> {
  readonly id: string;
  readonly kind: C['kind'];
  readonly definition: DefinitionFor<C>;
  readonly kinds: FieldKindRegistry;

  private readonly listeners = new Set<() => void>();
  private readonly context: KernelContext;
  private readonly runner: RequestRunner;
  private readonly environment: RuntimeEnvironment;
  private readonly unwatchVisibility: () => void;
  private readonly autoRefresh: boolean;

  private state: ViewRuntimeState<C>;
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
  private timer: unknown;
  private timerDelay: number | null = null;
  private stopped = false;

  constructor(options: ViewRuntimeOptions<C>) {
    this.id = options.id;
    this.kind = options.config.kind;
    // `C extends DataViewConfig` makes `DefinitionFor<C>` a data definition,
    // which the compiler cannot prove while `C` is still a parameter.
    this.definition = options.definition as DefinitionFor<C>;
    this.kinds = options.kinds;
    this.runner = options.runner;
    this.environment = options.environment;
    this.autoRefresh = options.autoRefresh ?? true;
    this.injectedScope = options.scopeFilter ?? null;
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
    // out unscoped, and an inadmissible condition would never be reported.
    const issues = this.admit(options.config);
    this.appliedAdmitted = !hasError(issues);
    this.state = {
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
    };
    this.unwatchVisibility = options.environment.visibility.subscribe(() =>
      this.syncTimer(),
    );
  }

  get disposed(): boolean {
    return this.stopped;
  }

  get fields(): readonly FieldDefinition[] {
    return this.context.definition.fields;
  }

  /** The injected condition in force; see `ViewRuntime.scopeFilter`. */
  get scopeFilter(): FilterTree | null {
    return this.injectedScope;
  }

  getSnapshot(): ViewRuntimeState<C> {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  edit(patch: Partial<C>): void {
    if (this.stopped) return;
    const draft = { ...this.state.draft, ...patch };
    this.setState({
      draft,
      issues: this.admit(draft),
      dirty: this.isDirty(draft, this.state.saved),
    });
  }

  apply(): void {
    if (this.stopped || hasError(this.state.issues)) return;
    this.appliedAdmitted = true;
    this.pageTarget = firstPageOf(this.context.definition);
    this.setState({ applied: this.state.draft, selection: [] });
    this.execute({ keepSelection: false });
  }

  /**
   * Discards the edits and re-runs what was saved.
   *
   * It re-applies rather than only restoring the draft, because the results
   * on screen may already answer a question the user has just taken back —
   * leaving them there would show the reverted config's rows under the saved
   * config's name. A draft the store's own config cannot pass admission for
   * is restored all the same and left for the user to fix, since refusing
   * would strand them on edits they asked to be rid of.
   */
  revert(): void {
    const saved = this.state.saved;
    if (this.stopped || saved === null) return;
    const draft = saved.config as C;
    const issues = this.admit(draft);
    const ran = this.state.applied;
    this.setState({ draft, issues, dirty: this.isDirty(draft, saved) });
    if (!dequal(ran, draft) && !hasError(issues)) this.apply();
  }

  /**
   * Re-runs what was applied, which was admitted before it ran. An invalid
   * draft therefore does not block it: the editor may be mid-edit and wrong,
   * while the results on screen answer a question that was legal when asked.
   * Auto-refresh still pauses on an invalid draft — see `refreshDelay` — but a
   * user pressing Refresh has asked for exactly this.
   */
  refresh(): void {
    if (this.stopped || !this.appliedAdmitted) return;
    // A refresh returns to the first page; the selection keeps whatever rows survive.
    this.pageTarget = firstPageOf(this.context.definition);
    this.execute({ keepSelection: true });
  }

  page(target: RecordPageTarget): void {
    if (this.stopped || !this.appliedAdmitted) return;
    this.pageTarget = target;
    this.setState({ selection: [] });
    this.execute({ keepSelection: false });
  }

  select(keys: RecordKey[]): void {
    if (this.stopped) return;
    const available = this.resultKeys();
    const selection = available
      ? keys.filter(key => available.has(key))
      : [...keys];
    this.setState({ selection });
  }

  setEditing(active: boolean): void {
    if (this.stopped || this.state.editing === active) return;
    this.setState({ editing: active });
  }

  setScopeFilter(tree: FilterTree | null): Issue[] {
    if (this.stopped) return [];
    // Re-injecting the same condition changes nothing, and a dashboard does
    // exactly that whenever a layout edit is applied.
    if (dequal(tree ?? null, this.injectedScope)) return [];
    const issues = this.admit(this.state.applied, tree);
    // An injected condition is admitted exactly like a user's own.
    if (hasError(issues)) return issues;

    this.injectedScope = tree ?? null;
    this.appliedAdmitted = true;
    this.pageTarget = firstPageOf(this.context.definition);
    // The draft is judged with the scope too, so its issues move with it.
    this.setState({ issues: this.admit(this.state.draft), selection: [] });
    this.execute({ keepSelection: false });
    return issues;
  }

  /** A record or an analysis config means the same thing in every scope. */
  issuesAt(): Issue[] {
    return this.state.issues;
  }

  /** Called by `ViewEngine` once a write has been confirmed by the store. */
  markSaved(instance: ViewInstance): void {
    if (this.stopped) return;
    this.moveBaseline(instance);
    this.setState({ write: null });
  }

  moveBaseline(instance: ViewInstance): void {
    if (this.stopped) return;
    this.setState({
      saved: instance,
      title: instance.title,
      scope: instance.scope,
      dirty: this.isDirty(this.state.draft, instance),
    });
  }

  /** Replaces the draft with the store's state, used by "reload" on a conflict. */
  adoptSaved(instance: ViewInstance): void {
    if (this.stopped) return;
    const draft = instance.config as C;
    this.setState({
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
    if (this.stopped) return;
    this.setState({ write });
  }

  dispose(): void {
    if (this.stopped) return;
    this.stopped = true;
    this.stopTimer();
    this.unwatchVisibility();
    this.runner.cancel(this.id);
    // The last notification: a subscriber that reads `disposed` sees it now
    // rather than on some later render it happens to get.
    for (const listener of [...this.listeners]) listener();
    this.listeners.clear();
  }

  private isDirty(draft: C, saved: ViewInstance | null): boolean {
    // A view that was never saved has nothing to compare against, and closing
    // it would lose everything, so it counts as dirty from the start.
    return saved === null || !dequal(draft, saved.config);
  }

  private resultKeys(): Set<RecordKey> | null {
    const data = this.state.result?.data;
    if (!data || data.kind !== 'record') return null;
    return new Set(data.view.rows.map(row => row.key));
  }

  /** A config as it would run: the scope filter ANDed after its own. */
  private withScope(config: C, scope: FilterTree | null): C {
    // A root that is not a group is admission's to report as it stands;
    // merging would turn it into a condition, or lose it.
    if (!scope || !isFilterGroup(config.filter)) return config;
    return { ...config, filter: mergeFilters(config.filter, scope) };
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
      validateDataConfig(this.context, this.withScope(config, scope)),
      config,
      scope,
    );
  }

  private execute(options: { keepSelection: boolean }): void {
    // Both halves travel with the request: what ran, and the view's own
    // config it was merged from. `applied` may move on before the answer
    // arrives, and a summary reading it would describe another question.
    const own = this.state.applied;
    const config = this.withScope(own, this.injectedScope);
    const requestId = `${this.id}:${(this.requestSeq += 1)}`;
    this.setState({ query: { status: 'loading', requestId } });

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
    const receivedAt = this.environment.now().getTime();
    const result = { config, own, data, receivedAt };
    const selection = keepSelection
      ? this.retainSelection(data)
      : this.state.selection;
    this.setState({
      query: { status: 'success', requestId },
      result,
      ...(selection === this.state.selection ? {} : { selection }),
    });
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
    this.setState({
      query: { status: 'error', error: queryIssue(error), requestId },
    });
  }

  private isCurrent(requestId: string): boolean {
    return !this.stopped && this.state.query.requestId === requestId;
  }

  private setState(patch: Partial<ViewRuntimeState<C>>): void {
    this.state = { ...this.state, ...patch };
    this.syncTimer();
    // Commit first, notify second: a listener always reads the new snapshot.
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * Auto-refresh. One timer per runtime, however many components watch it, and
   * four reasons to hold it: an invalid draft, an editor with focus, a hidden
   * page, and a request already in flight.
   */
  private syncTimer(): void {
    const delay = this.refreshDelay();
    if (delay === null) {
      this.stopTimer();
      return;
    }
    if (this.timer !== undefined && this.timerDelay === delay) return;
    this.stopTimer();
    this.timerDelay = delay;
    this.timer = this.environment.setTimeout(() => {
      this.timer = undefined;
      this.timerDelay = null;
      this.refresh();
    }, delay);
  }

  private refreshDelay(): number | null {
    const interval = refreshIntervalOf(this.state.applied);
    if (
      this.stopped ||
      !this.autoRefresh ||
      interval === null ||
      this.state.editing ||
      this.state.query.status === 'loading' ||
      hasError(this.state.issues) ||
      !this.environment.visibility.isVisible()
    )
      return null;
    return Math.min(interval * 1000, MAX_TIMER_DELAY_MS);
  }

  private stopTimer(): void {
    if (this.timer === undefined) return;
    this.environment.clearTimeout(this.timer);
    this.timer = undefined;
    this.timerDelay = null;
  }
}

/**
 * The interval a config asks for, read as the untrusted thing it is. A
 * stored config with no `refresh` is admission's to report, and it is
 * reported; every state change still passes through here on the way to the
 * timer, and must not throw before the user can fix it.
 */
export function refreshIntervalOf(config: ViewConfig): number | null {
  const interval = (config.refresh as { interval?: unknown } | undefined)
    ?.interval;
  return typeof interval === 'number' && Number.isFinite(interval)
    ? interval
    : null;
}

/** Turns a failed execution into the Issue the UI reports. */
function queryIssue(error: unknown): Issue {
  if (error instanceof RequestQueueFullError)
    return issue('runtime.query.queue-full', []);
  return issue('runtime.query.failed', [], {
    reason: error instanceof Error ? error.message : String(error),
  });
}
