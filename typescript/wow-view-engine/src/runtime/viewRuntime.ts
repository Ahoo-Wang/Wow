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
  type DataViewConfig,
  overlaid,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type RecordPageTarget,
  type RuntimeLimits,
  type ViewInstance,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { periodRollover } from '../analysis/index.js';
import {
  NO_REFUSAL,
  scopeRefusal,
  withScopeFilter,
  withoutScopeModeWarning,
} from './scope.js';
import type { RuntimeEnvironment, ViewErrorKind } from './environment.js';
import {
  failureReporter,
  queryFailureReporter,
  viewPlace,
  type FailureReporter,
} from './failures.js';
import { hasError, RuntimeStore } from './runtimeStore.js';
import { AUTO_APPLY_DELAY_MS, autoApplyDue } from './autoApply.js';
import { sourceFailure } from './sourceReason.js';
import { isForbiddenQuery, queryFailureIssue } from './queryFailure.js';
import { RefreshTimer } from './refreshTimer.js';
import {
  isRequestSuperseded,
  RequestQueueFullError,
  type RequestRunner,
} from './requestRunner.js';
import type { OptionSource, ProjectedView } from './source.js';
import {
  executeDataConfig,
  validateDataConfig,
  type KernelContext,
} from './execute.js';
import {
  ValueCandidateSources,
  type ValueCandidateSource,
} from './valueCandidates.js';
import type { WriteState } from './write.js';
import type {
  DefinitionFor,
  ManagedViewRuntime,
  ViewQueryState,
  ViewRuntimeOptions,
  ViewRuntimeState,
} from './viewRuntimeTypes.js';

const IDLE: ViewQueryState = { status: 'idle' };

/**
 * The runtime of a data view: an Analysis view as it is, and the shared
 * half of a Record view (`RecordDataViewRuntime`).
 *
 * It owns no persistence: saving is a command of `ViewEngine`, which calls
 * `markSaved` once the store has confirmed it.
 *
 * The store half of it — the snapshot, the subscribers, the refresh timer and
 * dirty-against-saved — is `RuntimeStore`, which the dashboard runtime holds
 * one of as well; what is left here is what it means to be a data view: ask,
 * run, land. What only a Record view has — a page, a selection, an export, a
 * record read whole — is its subclass's, reached through four hooks
 * (`startOver`, `pageNow`, `settle`, `holds`) rather than kind checks here.
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

  protected readonly store: RuntimeStore<ViewRuntimeState<C>>;
  protected readonly context: KernelContext;
  private readonly runner: RequestRunner;
  private readonly resolveOptions: ((key: string) => OptionSource) | undefined;
  private readonly autoRefresh: boolean;
  /** Whether a host lets the view refresh itself (`setAutoRefresh`). */
  private refreshing = true;
  /** The one timer behind 「改了就跑」, stopped whenever nothing is due. */
  private readonly autoTimer: RefreshTimer;
  private readonly candidates: ValueCandidateSources;

  private injectedScope: FilterTree | null = null;
  /**
   * Whether `applied` merged with the scope passed admission. `apply` and
   * `setScopeFilter` only promote what did, so this is false only for the
   * config a runtime opened on, and it keeps `refresh` and `page` from running
   * what `apply` would refuse.
   */
  protected appliedAdmitted: boolean;
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
      queryFailed: queryFailureReporter(options.environment, () =>
        viewPlace(this),
      ),
    };
    this.candidates = new ValueCandidateSources(
      this.context,
      () => this.injectedScope,
    );

    const saved = options.saved ?? null;
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
      // timer for good; otherwise it is held while its one request is in
      // flight, and for whatever reason of its own the kind has (`holds`).
      holding: () =>
        !this.autoRefresh ||
        !this.refreshing ||
        this.state.query.status === 'loading' ||
        // Refused the reader: asking again on a clock would only be refused
        // again, and told to the host each time. A press of refresh, or an
        // apply, still asks.
        isForbiddenQuery(this.state.query.error) ||
        this.holds(),
      release: () => this.runner.cancel(this.id),
      // A panel inside a dashboard is asked again by the board, which reads
      // this for each panel on screen (`rolloverAt`).
      expiresAt: () => (this.autoRefresh ? this.rolloverAt() : null),
    });
  }

  /**
   * When the answer on screen stops being true of its own accord: a metric
   * card over a trend skipped the period under way when it was asked, and
   * that period has ended by then (`periodRollover`). The chart read is the
   * draft's — how the rows are looked at is the draft's to say (D20) — over
   * the config that ran them. `null` for anything else.
   */
  rolloverAt(): number | null {
    const result = this.state.result;
    if (!result || result.data.kind !== 'analysis') return null;
    const draft = this.state.draft;
    if (draft.kind !== 'analysis' || result.config.kind !== 'analysis')
      return null;
    const askedAt = result.receivedAt - result.elapsedMs;
    const left = periodRollover(
      { ...result.config, chart: draft.chart },
      result.data.view.rows,
      { timeZone: this.environment.timeZone, now: new Date(askedAt) },
    );
    return left === undefined ? null : askedAt + left;
  }

  get disposed(): boolean {
    return this.store.disposed;
  }

  /** See `ViewRuntime.refusedScope`; the store keeps it. */
  get refusedScope(): Issue[] {
    return this.store.refusedScope;
  }

  /** The snapshot the store holds; every command reads it and patches it back. */
  protected get state(): ViewRuntimeState<C> {
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

  valueCandidates(field: string): ValueCandidateSource | null {
    return this.candidates.of(field);
  }

  edit(patch: Partial<C>): void {
    if (this.disposed) return;
    // A member given as `undefined` is taken out (`overlaid`): an editor
    // that took the last entry out of an optional list left the view
    // unsaved for the rest of the session otherwise, `dirty` being an
    // equality against the saved config.
    const draft = overlaid(this.state.draft, patch);
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
    this.store.setState({ applied: this.state.draft, ...this.startOver() });
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
    // The values a condition is offered are the data's, and a refresh is
    // the data read again: offering them from before it would list values
    // the rows no longer hold, with counts they no longer have.
    this.candidates.reset();
    // A refresh reads the page the reader is on again — the timer's, the
    // button's and the one a command asks for after writing alike: sending
    // them to the first page lost their place in a list they were working
    // through. The selection keeps whatever rows survive.
    this.execute({ keepSelection: true });
  }

  setEditing(active: boolean): void {
    if (this.disposed || this.state.editing === active) return;
    this.store.setState({ editing: active });
  }

  setAutoRefresh(on: boolean): void {
    if (this.disposed || this.refreshing === on) return;
    this.refreshing = on;
    this.store.retime();
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
    this.candidates.reset();
    this.appliedAdmitted = !hasError(merged);
    // The draft is judged with the scope too, so its issues move with it.
    this.store.setState({
      issues: this.admit(this.state.draft),
      ...this.startOver(),
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
    this.candidates.reset();
    this.store.dispose();
  }

  /**
   * What a new question starts from, merged into the state it is put in
   * force with — by `apply`, and by a new injected scope. An analysis has
   * nothing to reset; a Record view goes back to its first page and lets
   * its selection go.
   */
  protected startOver(): Partial<ViewRuntimeState<C>> {
    return {};
  }

  /** The page a query asks for; an analysis has none. */
  protected pageNow(): RecordPageTarget | undefined {
    return undefined;
  }

  /**
   * What else a landed answer changes, or `null` to ask again instead — a
   * Record view whose page the result shrank out from under asks for the
   * last page there is. `keepSelection` is whether the query was a refresh.
   */
  protected settle(
    data: ProjectedView,
    keepSelection: boolean,
  ): Partial<ViewRuntimeState<C>> | null {
    void data;
    void keepSelection;
    return {};
  }

  /** A reason of the kind's own to hold the refresh timer. */
  protected holds(): boolean {
    return false;
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

  protected execute(options: {
    keepSelection: boolean;
    /** When the question was first asked, for a page asked again. */
    askedAt?: number;
  }): void {
    const askedAt = options.askedAt ?? this.environment.now().getTime();
    // Both halves travel with the request: what ran, and the view's own
    // config it was merged from. `applied` may move on before the answer
    // arrives, and a summary reading it would describe another question.
    const own = this.state.applied;
    const config = withScopeFilter(own, this.injectedScope);
    const requestId = `${this.id}:${(this.requestSeq += 1)}`;
    this.store.setState({ query: { status: 'loading', requestId } });

    this.runner
      .run(this.id, controller =>
        executeDataConfig(this.context, config, this.pageNow(), controller),
      )
      .then(
        data =>
          this.onSuccess(
            requestId,
            config,
            own,
            data,
            options.keepSelection,
            askedAt,
          ),
        error => this.onFailure(requestId, own, error),
      );
  }

  private onSuccess(
    requestId: string,
    config: C,
    own: C,
    data: ProjectedView,
    keepSelection: boolean,
    askedAt: number,
  ): void {
    if (!this.isCurrent(requestId)) return;
    const settled = this.settle(data, keepSelection);
    if (settled === null) {
      this.execute({ keepSelection, askedAt });
      return;
    }
    const receivedAt = this.environment.now().getTime();
    const elapsedMs = Math.max(0, receivedAt - askedAt);
    const result = { config, own, data, receivedAt, elapsedMs };
    this.store.setState({
      query: { status: 'success', requestId },
      result,
      ...settled,
    });
  }

  private onFailure(requestId: string, own: C, error: unknown): void {
    // A superseded request is the normal outcome of typing; it is not an error.
    if (isRequestSuperseded(error) || !this.isCurrent(requestId)) return;
    // Told once, of a failure that was current when it landed: the host
    // hears of no request that had already been replaced. The report waits
    // for the body the source answered with, so it can say which rule a Wow
    // service said the query broke (D40); the Issue reads the same body.
    void this.context.queryFailed('query', error);
    // The query stays in flight until the body is read, and a newer
    // request that started meanwhile wins.
    void this.queryIssue(own, error).then(error => {
      if (!this.isCurrent(requestId)) return;
      this.store.setState({ query: { status: 'error', error, requestId } });
    });
  }

  /** Turns a failed execution into the Issue the UI reports. */
  private async queryIssue(own: C, error: unknown): Promise<Issue> {
    if (error instanceof RequestQueueFullError)
      return issue('runtime.query.queue-full', []);
    return queryFailureIssue(
      await sourceFailure(error),
      this.context.definition,
      own.filter,
    );
  }

  /**
   * Tells the host of a failure of this view's (D40), saying which view:
   * its definition, its saved id as it is at the moment of the failure, and
   * the runtime it failed in.
   */
  protected reporter(kind: ViewErrorKind): FailureReporter {
    return failureReporter(this.environment, kind, () => viewPlace(this));
  }

  private isCurrent(requestId: string): boolean {
    return !this.disposed && this.state.query.requestId === requestId;
  }
}
