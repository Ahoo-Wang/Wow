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
  boardWidth,
  type AnalysisDateUnit,
  type DashboardWidth,
  type DashboardField,
  type DashboardFilters,
  type DashboardPanel,
  type DashboardTab,
  type DashboardTimeGrouping,
  type DataViewConfig,
  type FieldOption,
  type FilterValue,
  type Issue,
  type PanelClick,
  type PanelLayout,
  type RecordData,
} from '../model/index.js';
import { filtersOf, tabsOf, type FilterReach } from '../dashboard/index.js';
import type {
  CrossFilterOutcome,
  DashboardEditing,
  DashboardFilterEditing,
  DashboardPanelState,
  DashboardRuntime,
  DestinationBoard,
  EditHistoryState,
  HandOver,
  OptionSource,
  PanelGrouping,
  PressDestination,
  ValueCandidateSource,
  ViewRuntime,
} from '../runtime/index.js';
import { boardFindings } from '../runtime/dashboard/panels.js';
import type { FieldKindRegistry } from '../filter/index.js';
import { useViewRuntime } from './useViewEngine.js';

/** One panel, ready to place: geometry, what runs inside, what is wrong. */
export interface DashboardPanelView {
  id: string;
  title: string | undefined;
  panel: DashboardPanel;
  layout: PanelLayout;
  /** The runtime of a data panel that can run; `null` otherwise. */
  runtime: ViewRuntime<DataViewConfig> | null;
  issues: Issue[];
  /** True when this panel alone cannot show anything. */
  broken: boolean;
  /** The tab it is on; `null` on a board without tabs. */
  tab: string | null;
  /** What each filter does to it, by filter name (`filterReach`). */
  reach: Readonly<Record<string, FilterReach>>;
  /** What the time grouping does to it (`PanelGrouping`). */
  grouping: PanelGrouping;
  /**
   * What a press on one of its groups does (D22 I), or `null` for the
   * follow-up menu (`DashboardPanelState.click`).
   */
  click: PanelClick | null;
}

export interface DashboardController {
  panels: DashboardPanelView[];
  /** Columns a layout is placed in; the kernel admits against the same number. */
  columns: number;
  /**
   * How wide the board on screen is laid out (D31, `boardWidth`): `full`
   * while no board is open, and for one saved before a board could say.
   */
  width: DashboardWidth;
  /** The tab on screen, whose panels alone run; `null` without tabs. */
  tab: string | null;
  /** Shows another tab (`DashboardRuntime.showTab`). */
  showTab(tabId: string): void;
  /**
   * What the board says about itself, above its panels (`boardFindings`):
   * its own findings — "too many panels" among them — and a panel's the
   * draft raised that no panel wears yet. Everything else a panel says in
   * its own frame.
   */
  issues: Issue[];
  /** True while a panel reference is still loading. */
  resolving: boolean;
  /**
   * True while any panel has a query in flight.
   *
   * A dashboard runs nothing of its own — `state.query` never leaves `idle`
   * — so "is something running here" is only answerable by asking the
   * panels, which is what the dashboard's own timer does before it fires
   * (`PanelChildren.loading`). A control that read the dashboard's
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
   * Whether the board is being built — 「编辑」 pressed, not yet 「完成」 or
   * 「取消」 (`DashboardRuntimeState.building`). The runtime's, so its auto
   * refresh waits for it (D26 Q39) and every surface reads the same one.
   */
  building: boolean;
  /** Starts or ends building the board (`DashboardRuntime.setBuilding`). */
  setBuilding(active: boolean): void;
  /**
   * Building the board (D22 A–E): the runtime's own commands, each written
   * into the draft and onto the screen at once; `null` while no board is
   * open. Whether they are on offer is the UI's to say, by permission.
   */
  edit: (DashboardEditing & DashboardFilterEditing) | null;
  /**
   * What building the board can take back and make again — the step
   * `edit.undo()` and `edit.redo()` would, or `null` (`DashboardRuntimeState.history`).
   */
  history: EditHistoryState;
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
   * The list a filter without one of its own picks from, as its wired
   * fields declare it (`DashboardRuntime.wiredOptions`); `null` for none.
   */
  filterChoices(name: string): FieldOption[] | null;
  /** An id filter's candidates, by the host's source key (`optionSource`). */
  filterOptions(remote: string): OptionSource | null;
  /** The field kinds the board's filters are edited with; `null` without a board. */
  kinds: FieldKindRegistry | null;
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
  /**
   * A press on one group of a panel whose click sets a filter (D22 I,
   * `DashboardRuntime.crossFilter`): set from it, or cleared on a second
   * press of the same group.
   */
  crossFilter(panelId: string, row: RecordData): CrossFilterOutcome;
  /**
   * Whether a group is one its panel's press set a filter to — the click's,
   * or a span of its time axis (`pressSpan`).
   */
  pressed(panelId: string, row: RecordData): boolean;
  /**
   * The date filters a span of a panel's time axis can set
   * (`DashboardRuntime.spanFilters`, D33 Q52).
   */
  spanFilters(panelId: string): readonly DashboardField[];
  /**
   * A span of a panel's time axis set into one of those filters
   * (`DashboardRuntime.pressSpan`): the panel keeps every group.
   */
  pressSpan(
    panelId: string,
    name: string,
    row: RecordData,
    through: RecordData,
  ): CrossFilterOutcome;
  /** Where a press on a panel with a custom destination goes. */
  destination(
    panelId: string,
    row: RecordData,
  ): Promise<PressDestination | null>;
  /**
   * Another board a click opens, read for 「点击时…」 to list its filters
   * (`DashboardRuntime.destinationBoard`); `null` for one gone or unreadable.
   */
  destinationBoard(instanceId: string): Promise<DestinationBoard | null>;
  /**
   * What one data panel's view takes off the board as it stands
   * (`DashboardRuntime.handOver`, D26 Q30): every way off it — 「在工作台中
   * 打开」, the follow-ups, a view destination — hands this over.
   */
  handOver(panelId: string): HandOver | null;
}

const EMPTY_PANELS: DashboardPanelState[] = [];
const NO_FILTERS: DashboardFilters = { values: {} };
const NO_ISSUES: Issue[] = [];
const NO_FIELDS: readonly DashboardField[] = [];
const NO_HISTORY: EditHistoryState = { undo: null, redo: null };

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
        .filter(
          (child): child is ViewRuntime<DataViewConfig> => child !== null,
        ),
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
  const filtersNow = state?.filters ?? null;
  const appliedTabs = applied?.tabs;
  const issues = state?.issues;

  return {
    panels: useMemo(() => panels.map(toView), [panels]),
    columns: DASHBOARD_GRID_COLUMNS,
    width: boardWidth(applied ?? {}),
    tab: state?.tab ?? null,
    showTab: useCallback((tabId: string) => runtime?.showTab(tabId), [runtime]),
    issues: useMemo(
      () => (issues ? boardFindings({ issues, panels }) : NO_ISSUES),
      [issues, panels],
    ),
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
    building: state?.building ?? false,
    setBuilding: useCallback(
      (active: boolean) => runtime?.setBuilding(active),
      [runtime],
    ),
    edit: runtime,
    history: state?.history ?? NO_HISTORY,
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
    filterChoices: useCallback(
      (name: string) => runtime?.wiredOptions(name) ?? null,
      [runtime],
    ),
    filterOptions: useCallback(
      (remote: string) => runtime?.optionSource(remote) ?? null,
      [runtime],
    ),
    kinds: runtime?.kinds ?? null,
    // What is on screen: an edit writes the draft and the applied config
    // alike, so the two agree on the tabs whenever a panel is drawn.
    tabs: useMemo(() => tabsOf({ tabs: appliedTabs ?? [] }), [appliedTabs]),
    preload: useCallback(
      async (instanceId: string) => {
        await runtime?.preload(instanceId);
      },
      [runtime],
    ),
    crossFilter: useCallback(
      (panelId: string, row: RecordData): CrossFilterOutcome =>
        runtime?.crossFilter(panelId, row) ?? { kind: 'none' },
      [runtime],
    ),
    // Read against what the filters hold now, so a new value marks another
    // group: the callback is a new one whenever they change.
    pressed: useCallback(
      (panelId: string, row: RecordData) =>
        runtime !== null && filtersNow !== null
          ? runtime.pressed(panelId, row)
          : false,
      [runtime, filtersNow],
    ),
    // Read against what the filters hold now, as `pressed` is: a filter the
    // host took hold of since is no longer one a span can set.
    spanFilters: useCallback(
      (panelId: string) =>
        runtime !== null && filtersNow !== null
          ? runtime.spanFilters(panelId)
          : NO_FIELDS,
      [runtime, filtersNow],
    ),
    pressSpan: useCallback(
      (panelId: string, name: string, row: RecordData, through: RecordData) =>
        runtime?.pressSpan(panelId, name, row, through) ?? { kind: 'none' },
      [runtime],
    ),
    destination: useCallback(
      async (panelId: string, row: RecordData) =>
        runtime ? runtime.destination(panelId, row) : null,
      [runtime],
    ),
    destinationBoard: useCallback(
      async (instanceId: string) =>
        runtime ? runtime.destinationBoard(instanceId) : null,
      [runtime],
    ),
    handOver: useCallback(
      (panelId: string) => runtime?.handOver(panelId) ?? null,
      [runtime],
    ),
  };
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
    click: panel.click,
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
