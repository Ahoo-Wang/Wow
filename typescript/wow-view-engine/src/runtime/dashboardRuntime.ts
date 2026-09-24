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
  type DashboardDefinition,
  type DashboardFilters,
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
  isPlainObject,
  issue,
  type FieldKindRegistry,
} from '../filter/index.js';
import {
  NO_REFUSAL,
  scopeRefusal,
  withScopeFilter,
  withoutScopeModeWarning,
} from './scope.js';
import {
  admitFilters,
  filtersOf,
  isViewPanel,
  migrateDashboardConfig,
  panelTab,
  referencedInstance,
  validateDashboard,
} from '../dashboard/index.js';
import { panelReach, panelRun } from './dashboard/panelRun.js';
import { FilterValues } from './dashboard/filterValues.js';
import { PanelPresses } from './dashboard/press.js';
import {
  boardEditing,
  type DashboardEditing,
  type DashboardFilterEditing,
} from './dashboard/editing.js';
import { BoardCommands } from './dashboard/commands.js';
import type { ValueCandidateSource } from './valueCandidates.js';
import type { RuntimeEnvironment } from './environment.js';
import { hasError, RuntimeStore } from './runtimeStore.js';
import type { OptionSource } from './source.js';
import {
  PanelChildren,
  panelView,
  type PanelView,
} from './dashboard/children.js';
import {
  blocksBoard,
  clickInForce,
  panelOf,
  reissued,
  panelsOf,
  samePanels,
  migrated,
  shownTab,
  type DashboardPanelState,
} from './dashboard/panels.js';
import { PanelReferences } from './dashboard/references.js';
import type { WriteState } from './write.js';
import type { DataViewRuntime } from './viewRuntime.js';
import type { ManagedViewRuntime, ViewQueryState } from './viewRuntimeTypes.js';

export type { PanelResolver } from './dashboard/references.js';
export type { PanelRuntimeFactory, PanelView } from './dashboard/children.js';
export type { DashboardPanelState } from './dashboard/panels.js';
export { stopsSave } from './dashboard/panels.js';
export type {
  DashboardEditing,
  DashboardFilterEditing,
} from './dashboard/editing.js';
export type { PanelGrouping } from './dashboard/grouping.js';
export type {
  DashboardNavigation,
  DashboardRuntime,
  DashboardRuntimeOptions,
  DashboardRuntimeState,
} from './dashboard/contract.js';
export type {
  CrossFilterOutcome,
  DestinationBoard,
  PressDestination,
} from './dashboard/press.js';
import type {
  DashboardRuntimeOptions,
  DashboardRuntimeState,
} from './dashboard/contract.js';

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
 *
 * The store half of it — the snapshot, the subscribers, that one timer and
 * dirty-against-saved — is the `RuntimeStore` a data view holds as well; what
 * is left here is composition.
 */
export class DashboardViewRuntime
  extends BoardCommands
  implements ManagedViewRuntime<DashboardViewConfig>
{
  readonly id: string;
  readonly kind = 'dashboard' as const;
  readonly definition: DashboardDefinition;
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;

  private readonly store: RuntimeStore<DashboardRuntimeState>;
  private readonly options: DashboardRuntimeOptions;
  /** What the panels point at, as far as it is known; see `PanelReferences`. */
  private readonly references: PanelReferences;
  /** The child runtime of each data panel that runs; see `PanelChildren`. */
  private readonly children: PanelChildren;
  /** Building the board; see `runtime/dashboard/editing.ts`. */
  protected readonly edits: DashboardEditing & DashboardFilterEditing;
  /** What the board's filters hold; see `FilterValues`. */
  protected readonly values: FilterValues;
  /** A press on a panel's group; see `PanelPresses`. */
  protected readonly presses: PanelPresses;

  private injectedScope: FilterTree | null;
  /** The tab the reader asked for; see `DashboardRuntimeState.tab`. */
  private requestedTab: string | null = null;
  /** Whether the panels were brought in line once: until then a tab is only noted. */
  private synced = false;

  constructor(options: DashboardRuntimeOptions) {
    super();
    this.options = options;
    this.id = options.id;
    this.definition = options.definition;
    this.kinds = options.kinds;
    this.limits = options.limits;
    this.environment = options.environment;
    this.injectedScope = null;
    // Both talk back only through the runtime's own re-sync: a reference
    // settling re-judges the draft, and a child notifying re-times the board
    // and rebuilds its panel's issues.
    this.references = new PanelReferences(options.resolve, () =>
      this.settled(),
    );
    this.children = new PanelChildren(options.createPanelRuntime, panelId => {
      this.store.retime();
      this.refreshPanelIssues(panelId);
    });
    this.edits = boardEditing({
      draft: () => (this.disposed ? null : this.state.draft),
      maxPanels: options.limits.maxDashboardPanels,
      viewConfig: id => this.references.get(id)?.instance.config,
      seed: instance => {
        const found = options.definitions(instance.definitionId);
        if (found) this.references.seed({ ...found, instance });
      },
      fieldsOf: panel => this.viewOf(panel)?.definition.fields ?? null,
      restructure: change => this.restructure(change),
    });
    this.values = new FilterValues({
      kinds: options.kinds,
      environment: options.environment,
      candidateSources: options.candidateSources,
      applied: () => this.state.applied,
      current: () => this.state.filters,
      commit: filters => this.store.setState({ filters }),
      started: () => this.synced,
      run: () => this.sync(),
      viewOf: panel => this.viewOf(panel),
      scope: () => this.injectedScope,
    });
    this.presses = new PanelPresses({
      kinds: options.kinds,
      applied: () => this.state.applied,
      filters: () => this.state.filters,
      child: panelId => this.panelRuntime(panelId),
      press: (name, value, panelId) => this.values.press(name, value, panelId),
      reference: async id => {
        await this.references.fetch(id);
        return this.references.get(id) ?? null;
      },
    });

    // Everything that comes in is read into the form this engine writes —
    // the config and the baseline alike, so a board stored in the old grid
    // opens clean rather than dirty with its own migration (D22 E).
    const saved = migrated(options.saved ?? null);
    const config = migrateDashboardConfig(options.config);
    // The injected condition is judged with the config from the start, as a
    // data view does, so a scope the panels cannot carry is never pushed onto
    // them. What this board's own fields refuse is the host's condition and
    // not the board's defect (D17-5): it is left out rather than written into
    // the issues, the panels run un-narrowed, and `refusedScope` says which
    // condition did not take.
    const wanted = options.scopeFilter ?? null;
    const own = this.admit(config, options.scope, null);
    const merged =
      wanted === null ? own : this.admit(config, options.scope, wanted);
    const refusedScope = scopeRefusal(own, merged);
    const refused = refusedScope.length > 0;
    if (!refused) this.injectedScope = wanted;
    this.store = new RuntimeStore<DashboardRuntimeState>({
      state: {
        saved,
        title: options.title,
        scope: options.scope,
        draft: config,
        applied: config,
        issues: refused ? own : merged,
        dirty: saved === null,
        query: IDLE,
        result: null,
        selection: [],
        write: null,
        editing: false,
        autoApply: false,
        nextRefreshAt: null,
        panels: [],
        resolving: false,
        tab: shownTab(config, null),
        filters: admitFilters(config, null, options.kinds).filters,
      },
      environment: options.environment,
      refusedScope,
      admit: draft => this.admit(draft, this.state.scope),
      // A panel's own error stops that panel, not the board's timer.
      blocking: blocksBoard,
      apply: () => this.apply(),
      refresh: () => this.refresh(),
      // One clock for the whole board, so a request in flight is any panel's.
      holding: () => this.children.loading(),
      // And one moment a card on the tab shown moves on, the soonest.
      expiresAt: () => this.children.rolloverAt(this.onTab()),
      release: () => {
        this.values.stop();
        this.children.disposeAll();
      },
      // A restored draft may name panels this opening has not resolved yet,
      // so it goes through `load` exactly as an edit does.
      restored: draft => this.load(draft),
    });
    this.load(config, true);
  }

  get disposed(): boolean {
    return this.store.disposed;
  }

  /** See `ViewRuntime.refusedScope`; the store keeps it. */
  get refusedScope(): Issue[] {
    return this.store.refusedScope;
  }

  /** The snapshot the store holds; every command reads it and patches it back. */
  private get state(): DashboardRuntimeState {
    return this.store.state;
  }

  /**
   * A dashboard declares its own filters; there is no definition to ask.
   * Only the well-formed entries: a stored `fields` may hold something that
   * is no field, which admission reports, and an editor mapping fields by
   * name must not be the second place to find out.
   */
  get fields(): readonly FieldDefinition[] {
    return filtersOf(this.state.draft);
  }

  /** The injected condition in force; see `ViewRuntime.scopeFilter`. */
  get scopeFilter(): FilterTree | null {
    return this.injectedScope;
  }

  getSnapshot(): DashboardRuntimeState {
    return this.store.getSnapshot();
  }

  subscribe(listener: () => void): () => void {
    return this.store.subscribe(listener);
  }

  optionSource(remote: string): OptionSource | null {
    const resolve = this.options.resolveOptions;
    return resolve ? resolve(remote) : null;
  }

  /** What a text filter offers to pick from; see `FilterValues.candidatesOf`. */
  valueCandidates(name: string): ValueCandidateSource | null {
    return this.values.candidatesOf(name);
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
    if (this.disposed) return;
    const draft = { ...this.state.draft, ...patch };
    this.store.setState({
      draft,
      issues: this.admit(draft, this.state.scope),
      dirty: this.store.isDirty(draft, this.state.saved),
    });
    // New panels need their references before the draft can be judged fully.
    this.load(draft);
  }

  /**
   * Promotes the draft and brings the panels in line with it.
   *
   * Only an error about the board itself refuses it (`blocksBoard`). A
   * panel in error is promoted with the rest and stays out on its own —
   * `sync` runs nothing for it and its issues say why — so one broken panel
   * cannot hold the global filter back from all the others.
   */
  apply(): void {
    if (this.disposed || blocksBoard(this.state.issues)) return;
    // Promotion and the panels that follow from it commit together, so a
    // subscriber is notified once and never sees the two disagree.
    this.sync({ applied: this.state.draft });
  }

  /** Discards the edits and re-runs what was saved; see `RuntimeStore.revert`. */
  revert(): void {
    this.store.revert();
  }

  /**
   * One clock for every panel; a referenced view's own interval is ignored.
   *
   * Like a data view's, this re-runs what was applied, so an invalid draft
   * does not block it: the children that exist are the ones `sync` admitted.
   */
  refresh(): void {
    if (this.disposed) return;
    this.children.refresh(this.onTab());
  }

  /** Whether a panel is on the tab shown. */
  private onTab(): (panelId: string) => boolean {
    const { panels, tab } = this.state;
    return id => panels.some(panel => panel.id === id && panel.tab === tab);
  }

  showTab(tabId: string | null): void {
    if (this.disposed) return;
    this.requestedTab = tabId;
    // Before the first sync this only says where the board opens: nothing
    // has run yet, and the first sync starts on this tab rather than on the
    // first and then here.
    if (!this.synced) {
      this.store.setState({ tab: shownTab(this.state.applied, tabId) });
      return;
    }
    this.sync();
  }

  /**
   * One edit to the board, applied as a placement is: into the draft and
   * into what is on screen alike, and nothing else of either moves — a
   * global filter still being composed stays pending (D22 A: the panels run
   * on the draft as it is built; saving writes it, `revert` undoes it). A
   * panel the edit adds is loaded like any the draft names.
   *
   * A placement is a gesture that lands at once, like sorting a table, but
   * it is not an apply of the whole draft: going through `edit` + `apply`
   * would also run whatever else was waiting — a global filter the user was
   * still composing ran the moment they nudged a panel. So the layout is
   * written into both the draft and the applied config, the panels it
   * pushes with it, and nothing else in either moves.
   */
  private restructure(
    change: (config: DashboardViewConfig) => DashboardViewConfig,
  ): void {
    if (this.disposed) return;
    const { draft, applied } = this.state;
    const nextDraft = change(draft);
    const nextApplied = change(applied);
    if (nextDraft === draft && nextApplied === applied) return;
    this.sync({
      draft: nextDraft,
      applied: nextApplied,
      issues: this.admit(nextDraft, this.state.scope),
      dirty: this.store.isDirty(nextDraft, this.state.saved),
    });
    this.load(nextDraft);
  }

  async preload(instanceId: string): Promise<void> {
    if (this.disposed) return;
    const loading = this.references.fetch(instanceId);
    if (!loading) return;
    this.store.setState({ resolving: true });
    await loading;
  }

  /**
   * Re-runs one panel — the retry a panel whose query failed offers. It is
   * the board's refresh narrowed to one child, so a panel that cannot run
   * has nothing to re-run and this does nothing for it.
   */
  refreshPanel(panelId: string): void {
    if (this.disposed) return;
    this.children.runtimeOf(panelId)?.refresh();
  }

  /**
   * The preference is the user's and is kept like any other; what it may run
   * is the model's to say, and it declares no dashboard member that runs on
   * its own (`autoRunMembers`), so the switch arms nothing here.
   */
  setAutoApply(on: boolean): void {
    if (this.disposed || this.state.autoApply === on) return;
    this.store.setState({ autoApply: on });
  }

  setEditing(active: boolean): void {
    if (this.disposed || this.state.editing === active) return;
    this.store.setState({ editing: active });
  }

  /**
   * An outer condition, in the dashboard's own field names. It is admitted
   * exactly like a user's own: the merged global filter must still map onto
   * every panel, so an embedding host cannot quietly break one.
   */
  setScopeFilter(tree: FilterTree | null): Issue[] {
    if (this.disposed) return this.refusedScope;
    if (dequal(tree ?? null, this.injectedScope))
      return this.store.refuse(NO_REFUSAL);
    const applied = this.state.applied;
    const own = this.admit(applied, this.state.scope, null);
    const merged = this.admit(applied, this.state.scope, tree);
    // Only what the condition alone breaks keeps it out; a board already
    // waiting to be fixed is not fixed by refusing the host's condition too.
    if (this.store.refuse(scopeRefusal(own, merged)).length > 0)
      return this.refusedScope;

    this.injectedScope = tree ?? null;
    // The values a filter offers were counted under the old scope.
    this.values.rescoped();
    // The draft is judged with the scope too, so its issues move with it.
    this.sync({ issues: this.admit(this.state.draft, this.state.scope) });
    return this.refusedScope;
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
    if (this.disposed) return;
    this.moveBaseline(instance);
    this.store.setState({ write: null });
  }

  moveBaseline(stored: ViewInstance): void {
    if (this.disposed) return;
    const instance = migrated(stored) as ViewInstance;
    this.store.setState({
      saved: instance,
      title: instance.title,
      scope: instance.scope,
      dirty: this.store.isDirty(this.state.draft, instance),
    });
  }

  adoptSaved(stored: ViewInstance): void {
    if (this.disposed) return;
    const instance = migrated(stored) as ViewInstance;
    const draft = instance.config as DashboardViewConfig;
    this.store.setState({
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
    if (this.disposed) return;
    this.store.setState({ write });
  }

  dispose(): void {
    this.store.dispose();
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
    const merged = withScopeFilter(config, scopeFilter);
    return withoutScopeModeWarning(
      validateDashboard(merged, scope, this.references.known, this.kinds, {
        limits: this.options.limits,
        definitions: this.options.definitions,
      }),
      config,
      scopeFilter,
    );
  }

  /**
   * Starts loading the references a config needs; each one re-judges the
   * draft as it settles (`settled`). `awaited` says whether anyone waits on
   * the outcome — see `PanelReferences.load`.
   */
  private load(config: DashboardViewConfig, awaited = false): void {
    if (this.references.load(config, awaited))
      this.store.setState({ resolving: true });
  }

  /** A reference settled — loaded, unreadable or failed — so the draft is re-judged. */
  private settled(): void {
    if (this.disposed) return;
    // A reference arriving is where a global field first meets the panel
    // field it binds to, so it is also where an injected condition can turn
    // out to be one this board cannot carry. It is refused here on the same
    // terms as on the way in, rather than becoming an error of the board's.
    this.dropRefusedScope();
    this.sync({
      issues: this.admit(this.state.draft, this.state.scope),
      resolving: this.references.resolving,
    });
  }

  /** Lets go of an injected condition the references have now refused. */
  private dropRefusedScope(): void {
    if (this.injectedScope === null) return;
    const applied = this.state.applied;
    const own = this.admit(applied, this.state.scope, null);
    const merged = this.admit(applied, this.state.scope);
    if (this.store.refuse(scopeRefusal(own, merged)).length > 0)
      this.injectedScope = null;
  }

  /**
   * Brings the child runtimes in line with the applied panels: one per data
   * panel that can run, none for the rest, and the current scope in each.
   */
  private sync(patch: Partial<DashboardRuntimeState> = {}): void {
    if (this.disposed) return;
    this.synced = true;
    const applied = patch.applied ?? this.state.applied;
    const issues = this.admit(applied, this.state.scope);
    // Only the tab on screen runs (D22 E). A panel elsewhere keeps the child
    // it has, rows and all, exactly as it stands — neither re-scoped nor
    // re-run — until its tab is shown; one never shown has none yet.
    const tab = shownTab(applied, this.requestedTab);
    const panels: DashboardPanelState[] = [];
    const live = new Set<string>();
    // A problem with the dashboard itself stops every panel, which is the
    // same rule `apply` follows; a panel's own problem stops only that one.
    // The check belongs here because a reference arriving also gets us here,
    // and a view waiting to be fixed must not start querying behind that.
    // "Too many panels" sits at `['panels']` and belongs to no one panel, so
    // it counts against the whole rather than slipping between the two.
    const blocked = blocksBoard(issues);
    // What the filters hold, read against the board as it now is: a filter
    // taken off holds nothing, a required one never less than its default.
    const filters = this.values.on(applied);

    panelsOf(applied).forEach((panel, index) => {
      // Admission reports an entry that is no panel at its index; there is
      // no id to build a state under, and nothing to run.
      if (!isPlainObject(panel)) return;
      const own = issues.filter(found => panelOf(found) === index);
      const on = panelTab(applied, panel);
      const shown = on === tab;
      const runs = isViewPanel(panel) && !blocked;
      const { runtime, issues: reported } = !runs
        ? { runtime: null, issues: own }
        : shown
          ? this.syncPanel(panel, index, applied, filters, own)
          : (this.children.hold(panel.id, index, own) ?? {
              runtime: null,
              issues: own,
            });
      if (runtime) live.add(panel.id);
      const view = isViewPanel(panel) ? this.viewOf(panel) : null;
      panels.push({
        id: panel.id,
        panel,
        runtime,
        issues: reported,
        tab: on,
        waiting: runs && !shown && runtime === null && !hasError(own),
        click: clickInForce(panel, reported),
        ...panelReach(applied, panel, view, filters),
      });
    });

    this.children.keepOnly(live);

    // A re-sync that changes nothing keeps the previous array, so a grid
    // bound with `useSyncExternalStore` does not re-render on every apply.
    this.store.setState({
      ...patch,
      tab,
      filters,
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
    filters: DashboardFilters,
    own: Issue[],
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const instanceId = referencedInstance(panel) ?? '';
    const failure = this.references.failure(instanceId);
    if (failure !== undefined)
      return {
        runtime: null,
        issues: [
          ...own,
          issue('dashboard.panel.failed', ['panels', index, 'instanceId'], {
            instance: instanceId,
            reason: failure,
          }),
        ],
      };

    const view = this.viewOf(panel);
    // A panel with a problem of its own does not query; the others still do.
    if (!view || hasError(own)) return { runtime: null, issues: own };

    const run = panelRun(panel, index, view, {
      applied,
      filters,
      injected: this.injectedScope,
      kinds: this.kinds,
      limits: this.limits,
    });
    return this.children.sync(
      panel.id,
      index,
      [...own, ...run.issues],
      run.view,
      run.scope,
    );
  }

  /** The view a data panel shows, once there is one; see `panelView`. */
  private viewOf(panel: DashboardViewPanel): PanelView | null {
    return panelView(
      panel,
      id => this.references.get(id),
      this.options.definitions,
      this.state.scope,
    );
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
    const panels = reissued(this.state.panels, this.children.get(panelId));
    if (panels) this.store.setState({ panels });
  }
}
