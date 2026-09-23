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

import { useCallback, useMemo, useSyncExternalStore } from 'react';
import {
  DASHBOARD_GRID_COLUMNS,
  type AnalysisDateUnit,
  type DashboardField,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardTab,
  type DashboardTimeGrouping,
  type FilterValue,
  type Issue,
  type PanelLayout,
} from '../model/index.js';
import { filtersOf, type FilterReach } from '../dashboard/index.js';
import type {
  DashboardEditing,
  DashboardFilterEditing,
  DashboardPanelState,
  DashboardRuntime,
  DataViewRuntime,
  PanelGrouping,
  ValueCandidateSource,
} from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';

/** One panel, ready to place: geometry, what runs inside, what is wrong. */
export interface DashboardPanelView {
  id: string;
  title: string | undefined;
  panel: DashboardPanel;
  layout: PanelLayout;
  /** The runtime of a data panel that can run; `null` otherwise. */
  runtime: DataViewRuntime | null;
  issues: Issue[];
  /** True when this panel alone cannot show anything. */
  broken: boolean;
  /** The tab it is on; `null` on a board without tabs. */
  tab: string | null;
  /** What each filter does to it, by filter name (`filterReach`). */
  reach: Readonly<Record<string, FilterReach>>;
  /** What the time grouping does to it (`PanelGrouping`). */
  grouping: PanelGrouping;
}

export interface DashboardController {
  panels: DashboardPanelView[];
  /** Columns a layout is placed in; the kernel admits against the same number. */
  columns: number;
  /** The tab on screen, whose panels alone run; `null` without tabs. */
  tab: string | null;
  /** Shows another tab (`DashboardRuntime.showTab`). */
  showTab(tabId: string): void;
  /** Issues about the dashboard as a whole, panels excluded. */
  issues: Issue[];
  /** True while a panel reference is still loading. */
  resolving: boolean;
  /**
   * True while any panel has a query in flight.
   *
   * A dashboard runs nothing of its own — `state.query` never leaves `idle`
   * — so "is something running here" is only answerable by asking the
   * panels, which is what the dashboard's own timer does before it fires
   * (`DashboardViewRuntime.loading`). A control that read the dashboard's
   * query state instead would call a board of twelve loading panels idle.
   */
  loading: boolean;
  dirty: boolean;
  /**
   * Moves or resizes one panel, and applies that alone, like sorting a
   * table: the panels it lands on are pushed down out of its way, and a
   * global filter still being edited stays unapplied
   * (`DashboardRuntime.place`).
   */
  place(panelId: string, layout: PanelLayout): void;
  /** Re-runs every panel at once. */
  refresh(): void;
  /** Re-runs one panel: the retry on a panel whose query failed. */
  refreshPanel(panelId: string): void;
  /**
   * Building the board (D22 A–E): the runtime's own commands, each written
   * into the draft and onto the screen at once; `null` while no board is
   * open. Whether they are on offer is the UI's to say, by permission.
   */
  edit: (DashboardEditing & DashboardFilterEditing) | null;
  /**
   * The board's filters as on screen, in the bar's order (D22 F); the same
   * list the draft and the applied config hold, since every edit writes
   * both.
   */
  filterFields: readonly DashboardField[];
  /** The board's time grouping; `null` on a board without one. */
  timeGrouping: DashboardTimeGrouping | null;
  /** What the filters hold now: the reader's, never the config's. */
  filters: DashboardFilters;
  /**
   * Sets what one filter holds, `null` clearing it; the panels run on it a
   * moment later (`DashboardRuntime.setFilterValue`). Answers why a value
   * was refused.
   */
  setFilterValue(name: string, value: FilterValue | null): Issue[];
  setGroupingUnit(unit: AnalysisDateUnit): void;
  /** 「清空」: every filter cleared, the required ones to their defaults. */
  clearFilters(): void;
  /** What a text filter offers to pick from (`DashboardRuntime.valueCandidates`). */
  filterCandidates(name: string): ValueCandidateSource | null;
  /**
   * The board's tabs in the order of its bar, as they are on screen (D22
   * E); none on a board without. Fewer than two draw no bar.
   */
  tabs: readonly DashboardTab[];
  /**
   * Loads a saved view before a panel shows it, so the panel is added at
   * the size of what it shows (`DashboardRuntime.preload`); never rejects.
   */
  preload(instanceId: string): Promise<void>;
}

const EMPTY_PANELS: DashboardPanelState[] = [];
const NO_TABS: readonly DashboardTab[] = [];
const NO_FILTERS: DashboardFilters = { values: {} };

/**
 * A dashboard as a grid of panels.
 *
 * It reports the applied panels rather than the draft's, because a panel is
 * a running query and not a form field: what the grid shows is what is
 * executing. Geometry is the exception the design allows — moving a panel
 * applies that move alone in one gesture, so a drag lands immediately.
 */
export function useDashboard(
  runtime: DashboardRuntime | null,
): DashboardController {
  const state = useViewRuntime(runtime);
  const panels = state?.panels ?? EMPTY_PANELS;
  const applied = state?.applied;

  // The children, watched here rather than left to the panels that draw
  // them: a child's query moving does not notify this runtime's subscribers
  // (it re-syncs the timer and the panel's issues, deliberately, so a grid
  // does not re-render on every panel request), so anything above the grid
  // that must know a request is out has to subscribe to the children itself.
  // The panels array keeps its identity while the set of panels is
  // unchanged, so neither memo churns as queries come and go.
  const children = useMemo(
    () =>
      panels
        .map(panel => panel.runtime)
        .filter((child): child is DataViewRuntime => child !== null),
    [panels],
  );
  const watch = useCallback(
    (listener: () => void) => {
      const drop = children.map(child => child.subscribe(listener));
      return () => drop.forEach(stop => stop());
    },
    [children],
  );
  const anyLoading = useCallback(
    () =>
      children.some(child => child.getSnapshot().query.status === 'loading'),
    [children],
  );
  const loading = useSyncExternalStore(watch, anyLoading, anyLoading);

  return {
    panels: useMemo(() => panels.map(toView), [panels]),
    columns: DASHBOARD_GRID_COLUMNS,
    tab: state?.tab ?? null,
    showTab: useCallback((tabId: string) => runtime?.showTab(tabId), [runtime]),
    // A panel's own issues travel with the panel; what is left belongs here.
    issues: (state?.issues ?? []).filter(found => found.path[0] !== 'panels'),
    resolving: state?.resolving ?? false,
    loading,
    dirty: state?.dirty ?? false,
    place: useCallback(
      (panelId: string, layout: PanelLayout) => runtime?.place(panelId, layout),
      [runtime],
    ),
    refresh: useCallback(() => runtime?.refresh(), [runtime]),
    refreshPanel: useCallback(
      (panelId: string) => runtime?.refreshPanel(panelId),
      [runtime],
    ),
    edit: runtime,
    filterFields: useMemo(() => (applied ? filtersOf(applied) : []), [applied]),
    timeGrouping: applied?.timeGrouping ?? null,
    filters: state?.filters ?? NO_FILTERS,
    setFilterValue: useCallback(
      (name: string, value: FilterValue | null) =>
        runtime?.setFilterValue(name, value) ?? [],
      [runtime],
    ),
    setGroupingUnit: useCallback(
      (unit: AnalysisDateUnit) => runtime?.setGroupingUnit(unit),
      [runtime],
    ),
    clearFilters: useCallback(() => runtime?.clearFilters(), [runtime]),
    filterCandidates: useCallback(
      (name: string) => runtime?.valueCandidates(name) ?? null,
      [runtime],
    ),
    // What is on screen: an edit writes the draft and the applied config
    // alike, so the two agree on the tabs whenever a panel is drawn.
    tabs: useMemo(() => tabsOf(state?.applied.tabs), [state?.applied.tabs]),
    preload: useCallback(
      async (instanceId: string) => {
        await runtime?.preload(instanceId);
      },
      [runtime],
    ),
  };
}

/**
 * The well-formed tabs of a stored config: admission reports the rest, and
 * a bar or a menu naming tabs must not be the second place to find out.
 */
function tabsOf(tabs: unknown): readonly DashboardTab[] {
  if (!Array.isArray(tabs) || tabs.length === 0) return NO_TABS;
  return tabs.filter(
    (tab): tab is DashboardTab =>
      typeof tab === 'object' &&
      tab !== null &&
      typeof (tab as { id?: unknown }).id === 'string' &&
      typeof (tab as { title?: unknown }).title === 'string',
  );
}

function toView(panel: DashboardPanelState): DashboardPanelView {
  return {
    id: panel.id,
    title: panel.panel.title,
    panel: panel.panel,
    layout: panel.panel.layout,
    runtime: panel.runtime,
    issues: panel.issues,
    tab: panel.tab,
    reach: panel.reach,
    grouping: panel.grouping,
    // A data panel with no runtime cannot query, and any panel carrying an
    // error was refused by `validateDashboard` — a content panel included,
    // whose markdown, image or link the grid would otherwise render as
    // though nothing were wrong with it.
    // One on a tab not shown yet was never asked, which is not broken.
    broken:
      (panel.runtime === null &&
        panel.panel.kind === 'view' &&
        !panel.waiting) ||
      panel.issues.some(found => found.severity === 'error'),
  };
}
