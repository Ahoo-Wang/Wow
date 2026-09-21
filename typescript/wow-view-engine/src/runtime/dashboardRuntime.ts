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
  type DashboardPanel,
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
  type PanelReference,
} from '../dashboard/index.js';
import type { RuntimeEnvironment } from './environment.js';
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

/** Loads what a panel references; rejects when it is gone or unreadable. */
export type PanelResolver = (instanceId: string) => Promise<PanelReference>;

/** Builds the child runtime of one data panel, with its scope already in force. */
export type PanelRuntimeFactory = (
  reference: PanelReference,
  scopeFilter: FilterTree | null,
) => DataViewRuntime;

/** One panel as the grid renders it. */
export interface DashboardPanelState {
  id: string;
  panel: DashboardPanel;
  /**
   * The child runtime of a data panel, once its reference has been loaded and
   * admitted. `null` for a content panel and for one that cannot run.
   */
  runtime: DataViewRuntime | null;
  /** Issues about this panel alone; the dashboard around it still works. */
  issues: Issue[];
}

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
  /** Resolved references by instance id; `null` once known to be unreadable. */
  private readonly references = new Map<string, PanelReference | null>();
  /** Why a resolved reference could not be brought into service, by id. */
  private readonly failures = new Map<string, string>();
  private readonly pending = new Map<string, Promise<void>>();
  /**
   * Child runtimes by panel id, with the subscription that watches each, and
   * what the last sync knew about the panel — its index in the config and
   * the dashboard's own findings about it — so the panel's issues can be
   * rebuilt when the child alone changes.
   */
  private readonly children = new Map<string, PanelChild>();

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
    // A reference that arrives may add panels of its own to load, so this
    // drains rather than awaiting one round.
    while (this.pending.size > 0) await Promise.all([...this.pending.values()]);
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
    for (const child of this.children.values()) child.runtime.refresh();
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
    for (const child of this.children.values()) {
      child.unsubscribe();
      child.runtime.dispose();
    }
    this.children.clear();
    // The last notification, so a subscriber reading `disposed` sees it now.
    for (const listener of [...this.listeners]) listener();
    this.listeners.clear();
  }

  /** The child runtime of one panel, for a host that drives a panel itself. */
  panelRuntime(panelId: string): DataViewRuntime | null {
    return this.children.get(panelId)?.runtime ?? null;
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
      validateDashboard(merged, scope, this.references, this.kinds, {
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
   * Loads the references a config needs and revalidates as each arrives.
   *
   * `awaited` says whether anyone is waiting on the outcome. `ready()` waits
   * on what the constructor starts, so a load that cannot be brought into
   * service at all refuses the opening. A load `edit` or `adoptSaved` starts
   * has no such caller: letting it reject would leave an unhandled rejection
   * and no trace on screen, so its failure becomes this panel's issue.
   */
  private load(config: DashboardViewConfig, awaited = false): void {
    const wanted = new Set(
      panelsOf(config)
        .filter(isViewPanel)
        .map(panel => panel.instanceId)
        .filter(id => !this.references.has(id) && !this.pending.has(id)),
    );
    if (wanted.size === 0) return;

    for (const id of wanted) {
      // A rejection is an answer too: the instance was deleted, or this user
      // may not read it, and only that one panel is affected.
      const loading = this.options.resolve(id).then(
        reference => this.resolved(id, reference),
        () => this.resolved(id, null),
      );
      this.pending.set(
        id,
        awaited ? loading : loading.catch(error => this.failed(id, error)),
      );
    }
    this.setState({ resolving: true });
  }

  private resolved(id: string, reference: PanelReference | null): void {
    this.pending.delete(id);
    if (this.stopped) return;
    this.references.set(id, reference);
    this.sync({
      issues: this.admit(this.state.draft, this.state.scope),
      resolving: this.pending.size > 0,
    });
  }

  /**
   * A reference that arrived but could not be put to work — its definition
   * names a source the host does not resolve, say. Admission cannot see this,
   * so it is remembered here and reported against the panel that asked for
   * it, and the panel stops trying rather than throwing on every sync.
   */
  private failed(id: string, error: unknown): void {
    if (this.stopped) return;
    this.failures.set(id, reasonOf(error));
    this.sync({
      issues: this.admit(this.state.draft, this.state.scope),
      resolving: this.pending.size > 0,
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

    for (const panelId of [...this.children.keys()])
      if (!live.has(panelId)) this.dropChild(panelId);

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
   * One panel's child, and what the panel reports. A child admits the scope
   * it is handed like any condition, and a refusal is this panel's problem:
   * the child stops rather than running its previous scope, and the reasons
   * land in the panel's issues where the dashboard's own would. A warning the
   * child raises about its own saved config travels the same way, so a panel
   * that runs with a caveat says so instead of running as if it had none.
   */
  private syncPanel(
    panel: DashboardViewPanel,
    index: number,
    applied: DashboardViewConfig,
    own: Issue[],
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const failure = this.failures.get(panel.instanceId);
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
    const existing = this.children.get(panel.id);
    if (existing && holds(existing.runtime, reference)) {
      // Before the scope goes in: the child may notify on the spot, and the
      // refresh that answers it reads these.
      existing.index = index;
      existing.own = own;
      const refused = existing.runtime.setScopeFilter(scope);
      if (!hasError(refused))
        return {
          runtime: existing.runtime,
          issues: panelIssues(index, own, existing.runtime),
        };
      this.dropChild(panel.id);
      return { runtime: null, issues: [...own, ...atPanel(index, refused)] };
    }
    // The panel points somewhere else now, or the instance was reloaded.
    if (existing) this.dropChild(panel.id);

    const runtime = this.options.createPanelRuntime(reference, scope);
    const refused = runtime.getSnapshot().issues;
    if (hasError(refused)) {
      runtime.dispose();
      return { runtime: null, issues: [...own, ...atPanel(index, refused)] };
    }
    // The dashboard's timer waits on its panels, so it watches them, and a
    // panel's issues follow its child. The UI subscribes to each child
    // itself for everything else and is not notified from here.
    const unsubscribe = runtime.subscribe(() => {
      this.retime();
      this.refreshPanelIssues(panel.id);
    });
    this.children.set(panel.id, { runtime, unsubscribe, index, own });
    runtime.apply();
    return { runtime, issues: panelIssues(index, own, runtime) };
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

  private dropChild(panelId: string): void {
    const child = this.children.get(panelId);
    if (!child) return;
    child.unsubscribe();
    child.runtime.dispose();
    this.children.delete(panelId);
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
    for (const child of this.children.values())
      if (child.runtime.getSnapshot().query.status === 'loading') return true;
    return false;
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

/**
 * The panels a config holds, read as the untrusted thing a stored config is.
 * Admission reports a `panels` that is not an array; until it is fixed there
 * is nothing to load or run, and nothing to throw about.
 */
function panelsOf(config: DashboardViewConfig): readonly DashboardPanel[] {
  return Array.isArray(config.panels) ? config.panels : [];
}

/** The panel an issue belongs to, or `null` for one about the dashboard. */
function panelOf(found: Issue): number | null {
  const [head, index] = found.path;
  return head === 'panels' && typeof index === 'number' ? index : null;
}

/** What a thrown value says for itself; not everything thrown is an `Error`. */
function reasonOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

interface PanelChild {
  runtime: DataViewRuntime;
  unsubscribe: () => void;
  /** The panel's index in the config, where its issues are addressed. */
  index: number;
  /** The dashboard's own findings about the panel, as of the last sync. */
  own: Issue[];
}

/**
 * What a panel reports for a child that runs: the dashboard's own findings
 * about it, then what the child still has to say about its saved config,
 * re-addressed to the panel.
 *
 * The child's part is read off its snapshot rather than off `setScopeFilter`,
 * which answers `[]` for a scope it already holds — a layout edit re-syncs
 * every panel with the scope unchanged, and a warning must not vanish on
 * that. And it is read second: the dashboard re-validates the merged filter
 * against the child's fields, and the child admits the same merged tree, so
 * a kind that warns is heard twice. The panel shows the wording, and the
 * same sentence twice tells nobody anything more, so a warning the
 * dashboard already reported is not repeated.
 */
function panelIssues(
  index: number,
  own: readonly Issue[],
  runtime: DataViewRuntime,
): Issue[] {
  const caveats = runtime
    .getSnapshot()
    .issues.filter(
      found =>
        found.severity === 'warning' &&
        !own.some(
          said =>
            said.severity === 'warning' &&
            said.code === found.code &&
            dequal(said.params, found.params),
        ),
    );
  return [...own, ...atPanel(index, caveats)];
}

/** A child's issues, addressed from the dashboard's config. */
function atPanel(index: number, issues: readonly Issue[]): Issue[] {
  return issues.map(found => ({
    ...found,
    path: ['panels', index, ...found.path],
  }));
}

/** Whether a child runtime still stands for exactly this reference. */
function holds(runtime: DataViewRuntime, reference: PanelReference): boolean {
  const saved = runtime.getSnapshot().saved;
  return (
    saved?.id === reference.instance.id &&
    saved.revision === reference.instance.revision
  );
}

function samePanels(
  previous: readonly DashboardPanelState[],
  next: readonly DashboardPanelState[],
): boolean {
  return (
    previous.length === next.length &&
    previous.every((panel, index) => {
      const other = next[index];
      return (
        panel.id === other.id &&
        panel.panel === other.panel &&
        panel.runtime === other.runtime &&
        dequal(panel.issues, other.issues)
      );
    })
  );
}
