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
  type DashboardDefinition,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type RuntimeLimits,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import {
  isFilterGroup,
  isPlainObject,
  issue,
  mergeFilters,
  type FieldKindRegistry,
} from '../filter/index.js';
import {
  isViewPanel,
  mapGlobalFilter,
  validateDashboard,
} from '../dashboard/index.js';
import type { RuntimeEnvironment } from './environment.js';
import {
  PanelChildren,
  type PanelRuntimeFactory,
} from './dashboard/children.js';
import {
  panelIssues,
  panelOf,
  panelsOf,
  samePanels,
  type DashboardPanelState,
} from './dashboard/panels.js';
import { PanelReferences, type PanelResolver } from './dashboard/references.js';
import type { WriteState } from './write.js';
import {
  hasError,
  refreshIntervalOf,
  withoutScopeModeWarning,
  type DataViewRuntime,
  type ManagedViewRuntime,
  type ViewQueryState,
  type ViewRuntime,
  type ViewRuntimeState,
} from './viewRuntime.js';

export type { PanelResolver } from './dashboard/references.js';
export type { PanelRuntimeFactory } from './dashboard/children.js';
export type { DashboardPanelState } from './dashboard/panels.js';

export interface DashboardRuntimeState extends ViewRuntimeState<DashboardViewConfig> {
  /**
   * The applied panels. Loading, errors and data are each panel's own: a
   * dashboard has no single query state to report.
   */
  panels: DashboardPanelState[];
  /** True while a panel reference is still being loaded. */
  resolving: boolean;
}

/**
 * The public face of a dashboard runtime: a view runtime whose snapshot also
 * carries the panels. `open` narrows to it by `kind`, so a caller reaches the
 * panels without knowing the class behind them.
 */
export interface DashboardRuntime extends ViewRuntime<DashboardViewConfig> {
  getSnapshot(): DashboardRuntimeState;
  /** Resolves once every panel reference has been loaded or refused. */
  ready(): Promise<void>;
  /** The child runtime of one panel, for a host that drives a panel itself. */
  panelRuntime(panelId: string): DataViewRuntime | null;
}

export interface DashboardRuntimeOptions {
  id: string;
  definition: DashboardDefinition;
  config: DashboardViewConfig;
  title: string;
  scope: ViewScope;
  saved?: ViewInstance | null;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  resolve: PanelResolver;
  createPanelRuntime: PanelRuntimeFactory;
  /** An outer condition in force from the first execution, as for a data view. */
  scopeFilter?: FilterTree | null;
}

const IDLE: ViewQueryState = { status: 'idle' };

/**
 * The runtime of a dashboard: N child runtimes and one global filter.
 *
 * What it adds over a data view is composition, and its rules follow from
 * that. The global filter reaches a panel as an injected scope, so the
 * referenced view never becomes dirty and a dashboard's condition is never
 * saved back into it. Every panel keeps its own loading, error and result,
 * because one slow or broken panel must not decide what the others show. And
 * the timer lives here rather than in the children: a referenced view's own
 * refresh interval is ignored inside a dashboard, so there is one clock.
 */
export class DashboardViewRuntime implements ManagedViewRuntime<DashboardViewConfig> {
  readonly id: string;
  readonly kind = 'dashboard' as const;
  readonly definition: DashboardDefinition;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;

  private readonly listeners = new Set<() => void>();
  private readonly options: DashboardRuntimeOptions;
  private readonly unwatchVisibility: () => void;
  /** What the panels point at, as far as it is known; see `PanelReferences`. */
  private readonly references: PanelReferences;
  /** The child runtime of each data panel that runs; see `PanelChildren`. */
  private readonly children: PanelChildren;

  private state: DashboardRuntimeState;
  private injectedScope: FilterTree | null;
  private timer: unknown;
  private timerDelay: number | null = null;
  private stopped = false;

  constructor(options: DashboardRuntimeOptions) {
    this.options = options;
    this.id = options.id;
    this.definition = options.definition;
    this.kinds = options.kinds;
    this.limits = options.limits;
    this.environment = options.environment;
    this.injectedScope = options.scopeFilter ?? null;
    // Both talk back only through the runtime's own re-sync: a reference
    // settling re-judges the draft, and a child notifying re-times the board
    // and rebuilds its panel's issues.
    this.references = new PanelReferences(options.resolve, () =>
      this.settled(),
    );
    this.children = new PanelChildren(options.createPanelRuntime, panelId => {
      this.retime();
      this.refreshPanelIssues(panelId);
    });

    const saved = options.saved ?? null;
    this.state = {
      saved,
      title: options.title,
      scope: options.scope,
      draft: options.config,
      applied: options.config,
      // The injected condition is judged with the config from the start, as
      // a data view does, so a scope the panels cannot carry is reported
      // rather than pushed onto them.
      issues: this.admit(options.config, options.scope),
      dirty: saved === null,
      query: IDLE,
      result: null,
      selection: [],
      write: null,
      editing: false,
      nextRefreshAt: null,
      panels: [],
      resolving: false,
    };
    this.unwatchVisibility = options.environment.visibility.subscribe(() =>
      this.retime(),
    );
    this.load(options.config, true);
  }

  get disposed(): boolean {
    return this.stopped;
  }

  /** A dashboard declares its own filter fields; there is no definition to ask. */
  /**
   * Only the well-formed entries: a stored `fields` may hold something that
   * is no field, which admission reports, and an editor mapping fields by
   * name must not be the second place to find out.
   */
  get fields(): readonly FieldDefinition[] {
    const fields: unknown = this.state.draft.fields;
    return Array.isArray(fields)
      ? fields.filter(
          (field): field is FieldDefinition =>
            isPlainObject(field) && typeof field.name === 'string',
        )
      : [];
  }

  /** The injected condition in force; see `ViewRuntime.scopeFilter`. */
  get scopeFilter(): FilterTree | null {
    return this.injectedScope;
  }

  getSnapshot(): DashboardRuntimeState {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Resolves once every reference the current config needs has been loaded or
   * found unreadable, so `open` can hand back a dashboard that is ready to
   * run rather than one that fills in a moment later.
   */
  async ready(): Promise<void> {
    await this.references.ready();
  }

  edit(patch: Partial<DashboardViewConfig>): void {
    if (this.stopped) return;
    const draft = { ...this.state.draft, ...patch };
    this.setState({
      draft,
      issues: this.admit(draft, this.state.scope),
      dirty: this.isDirty(draft, this.state.saved),
    });
    // New panels need their references before the draft can be judged fully.
    this.load(draft);
  }

  /** Promotes the draft and brings the panels in line with it. */
  apply(): void {
    if (this.stopped || hasError(this.state.issues)) return;
    // Promotion and the panels that follow from it commit together, so a
    // subscriber is notified once and never sees the two disagree.
    this.sync({ applied: this.state.draft });
  }

  /**
   * Discards the edits and re-runs what was saved; see `ViewRuntime.revert`.
   * The restored draft may name panels this opening has not resolved yet, so
   * it goes through `load` exactly as an edit does.
   */
  revert(): void {
    const saved = this.state.saved;
    if (this.stopped || saved === null) return;
    const draft = saved.config as DashboardViewConfig;
    const issues = this.admit(draft, this.state.scope);
    const ran = this.state.applied;
    this.setState({ draft, issues, dirty: this.isDirty(draft, saved) });
    this.load(draft);
    if (!dequal(ran, draft) && !hasError(issues)) this.apply();
  }

  /**
   * One clock for every panel; a referenced view's own interval is ignored.
   *
   * Like a data view's, this re-runs what was applied, so an invalid draft
   * does not block it: the children that exist are the ones `sync` admitted.
   */
  refresh(): void {
    if (this.stopped) return;
    this.children.refresh();
  }

  setEditing(active: boolean): void {
    if (this.stopped || this.state.editing === active) return;
    this.setState({ editing: active });
  }

  /**
   * An outer condition, in the dashboard's own field names. It is admitted
   * exactly like a user's own: the merged global filter must still map onto
   * every panel, so an embedding host cannot quietly break one.
   */
  setScopeFilter(tree: FilterTree | null): Issue[] {
    if (this.stopped) return [];
    if (dequal(tree ?? null, this.injectedScope)) return [];
    const issues = this.admit(this.state.applied, this.state.scope, tree);
    if (hasError(issues)) return issues;

    this.injectedScope = tree ?? null;
    // The draft is judged with the scope too, so its issues move with it.
    this.sync({ issues: this.admit(this.state.draft, this.state.scope) });
    return issues;
  }

  /**
   * What the draft would be judged as at another scope. Sharing a dashboard
   * widens who sees it, and a panel on a personal view would be blank for
   * them, so the target scope decides rather than the current one.
   */
  issuesAt(scope: ViewScope): Issue[] {
    return scope === this.state.scope
      ? this.state.issues
      : this.admit(this.state.draft, scope);
  }

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

  adoptSaved(instance: ViewInstance): void {
    if (this.stopped) return;
    const draft = instance.config as DashboardViewConfig;
    this.setState({
      saved: instance,
      title: instance.title,
      scope: instance.scope,
      draft,
      issues: this.admit(draft, instance.scope),
      dirty: false,
      write: null,
    });
    this.load(draft);
  }

  setWrite(write: WriteState | null): void {
    if (this.stopped) return;
    this.setState({ write });
  }

  dispose(): void {
    if (this.stopped) return;
    this.stopped = true;
    this.stopTimer();
    this.unwatchVisibility();
    this.children.disposeAll();
    // The last notification, so a subscriber reading `disposed` sees it now.
    for (const listener of [...this.listeners]) listener();
    this.listeners.clear();
  }

  /** The child runtime of one panel, for a host that drives a panel itself. */
  panelRuntime(panelId: string): DataViewRuntime | null {
    return this.children.runtimeOf(panelId);
  }

  /**
   * Admission of a config together with the scope filter it would run under,
   * which is what every panel receives. One rule for the draft, the applied
   * config and an injected condition, as in a data view.
   */
  private admit(
    config: DashboardViewConfig,
    scope: ViewScope,
    scopeFilter: FilterTree | null = this.injectedScope,
  ): Issue[] {
    // A root that is not a group is admission's to report as it stands.
    const merged =
      scopeFilter && isFilterGroup(config.filter)
        ? { ...config, filter: mergeFilters(config.filter, scopeFilter) }
        : config;
    return withoutScopeModeWarning(
      validateDashboard(merged, scope, this.references.known, this.kinds, {
        limits: this.options.limits,
      }),
      config,
      scopeFilter,
    );
  }

  private isDirty(
    draft: DashboardViewConfig,
    saved: ViewInstance | null,
  ): boolean {
    return saved === null || !dequal(draft, saved.config);
  }

  /**
   * Starts loading the references a config needs; each one re-judges the
   * draft as it settles (`settled`). `awaited` says whether anyone waits on
   * the outcome — see `PanelReferences.load`.
   */
  private load(config: DashboardViewConfig, awaited = false): void {
    if (this.references.load(config, awaited))
      this.setState({ resolving: true });
  }

  /** A reference settled — loaded, unreadable or failed — so the draft is re-judged. */
  private settled(): void {
    if (this.stopped) return;
    this.sync({
      issues: this.admit(this.state.draft, this.state.scope),
      resolving: this.references.resolving,
    });
  }

  /**
   * Brings the child runtimes in line with the applied panels: one per data
   * panel that can run, none for the rest, and the current scope in each.
   */
  private sync(patch: Partial<DashboardRuntimeState> = {}): void {
    if (this.stopped) return;
    const applied = patch.applied ?? this.state.applied;
    const issues = this.admit(applied, this.state.scope);
    const panels: DashboardPanelState[] = [];
    const live = new Set<string>();
    // A problem with the dashboard itself stops every panel, which is the
    // same rule `apply` follows; a panel's own problem stops only that one.
    // The check belongs here because a reference arriving also gets us here,
    // and a view waiting to be fixed must not start querying behind that.
    // "Too many panels" sits at `['panels']` and belongs to no one panel, so
    // it counts against the whole rather than slipping between the two.
    const blocked = hasError(issues.filter(found => panelOf(found) === null));

    panelsOf(applied).forEach((panel, index) => {
      // Admission reports an entry that is no panel at its index; there is
      // no id to build a state under, and nothing to run.
      if (!isPlainObject(panel)) return;
      const own = issues.filter(found => panelOf(found) === index);
      const { runtime, issues: reported } =
        isViewPanel(panel) && !blocked
          ? this.syncPanel(panel, index, applied, own)
          : { runtime: null, issues: own };
      if (runtime) live.add(panel.id);
      panels.push({ id: panel.id, panel, runtime, issues: reported });
    });

    this.children.keepOnly(live);

    // A re-sync that changes nothing keeps the previous array, so a grid
    // bound with `useSyncExternalStore` does not re-render on every apply.
    this.setState({
      ...patch,
      panels: samePanels(this.state.panels, panels)
        ? this.state.panels
        : panels,
    });
  }

  /**
   * One panel's child, and what the panel reports. A reference that could
   * not be put to work, or a panel with an error of its own, runs nothing;
   * otherwise the child is brought in line with the panel and the scope
   * (`PanelChildren.sync`), and a refusal lands in the panel's issues where
   * the dashboard's own would.
   */
  private syncPanel(
    panel: DashboardViewPanel,
    index: number,
    applied: DashboardViewConfig,
    own: Issue[],
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const failure = this.references.failure(panel.instanceId);
    if (failure !== undefined)
      return {
        runtime: null,
        issues: [
          ...own,
          issue('dashboard.panel.failed', ['panels', index, 'instanceId'], {
            instance: panel.instanceId,
            reason: failure,
          }),
        ],
      };

    const reference = this.references.get(panel.instanceId);
    // A panel with a problem of its own does not query; the others still do.
    if (!reference || hasError(own)) return { runtime: null, issues: own };

    const scope = mapGlobalFilter(
      mergeFilters(applied.filter, this.injectedScope),
      panel.bindings,
    );
    return this.children.sync(panel.id, index, own, reference, scope);
  }

  /**
   * A child's findings change under a host that drives it through
   * `panelRuntime` — an edit, an apply — and nothing re-syncs the dashboard
   * for that. The panel's issues are rebuilt from what the last sync knew
   * and what the child says now, so a header marker is never a sync behind
   * the body it sits over. Mid-sync the array still holds the previous
   * child, if any, and the sync in progress reports for the new one.
   */
  private refreshPanelIssues(panelId: string): void {
    const child = this.children.get(panelId);
    const at = this.state.panels.findIndex(panel => panel.id === panelId);
    if (!child || at < 0) return;
    const current = this.state.panels[at];
    if (current.runtime !== child.runtime) return;
    const issues = panelIssues(child.index, child.own, child.runtime);
    if (dequal(issues, current.issues)) return;
    const panels = [...this.state.panels];
    panels[at] = { ...current, issues };
    this.setState({ panels });
  }

  private setState(patch: Partial<DashboardRuntimeState>): void {
    this.state = { ...this.state, ...patch };
    this.syncTimer();
    // Commit first, notify second: a listener always reads the new snapshot.
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * Re-syncs the timer for something that is no state change of the dashboard
   * itself — the page hidden or shown, a panel's query starting or landing —
   * and notifies only when the due time actually moved. That is at most twice
   * a round however many panels there are, which is what keeps the grid from
   * re-rendering on every panel request while the countdown in the title bar
   * still answers to the timer the panels hold up.
   */
  private retime(): void {
    const before = this.state;
    this.syncTimer();
    if (this.state === before) return;
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * One timer for the whole dashboard, held for the same four reasons a data
   * view holds its own, with "a request in flight" meaning any panel's.
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
    // Published with the timer, from the clock it runs on: the board's one
    // countdown counts to the board's one timer.
    this.setDueAt(this.environment.now().getTime() + delay);
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
      interval === null ||
      this.state.editing ||
      this.loading() ||
      hasError(this.state.issues) ||
      !this.environment.visibility.isVisible()
    )
      return null;
    return Math.min(interval * 1000, MAX_TIMER_DELAY_MS);
  }

  private loading(): boolean {
    return this.children.loading();
  }

  /** As a data view's: written into the snapshot, notified by the caller. */
  private setDueAt(at: number | null): void {
    if (this.state.nextRefreshAt === at) return;
    this.state = { ...this.state, nextRefreshAt: at };
  }

  private stopTimer(): void {
    this.setDueAt(null);
    if (this.timer === undefined) return;
    this.environment.clearTimeout(this.timer);
    this.timer = undefined;
    this.timerDelay = null;
  }
}
