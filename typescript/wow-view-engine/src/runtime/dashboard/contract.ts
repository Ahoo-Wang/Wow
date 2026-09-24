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

/**
 * What a dashboard runtime is to the rest of the package: its snapshot, its
 * public face and what it is built from. `DashboardViewRuntime` is the one
 * class behind it.
 */

import type { EditHistoryState } from './history.js';
import type {
  AnalysisDateUnit,
  AnalysisViewConfig,
  DashboardDefinition,
  DashboardFilters,
  DashboardViewConfig,
  DataViewDefinition,
  FieldOption,
  FilterNode,
  FilterTree,
  FilterValue,
  Issue,
  RecordData,
  RecordViewConfig,
  RuntimeLimits,
  ViewInstance,
  ViewScope,
} from '../../model/index.js';
import type { FieldKindRegistry } from '../../filter/index.js';
import type { PanelDefinition } from '../../dashboard/index.js';
import type { RuntimeEnvironment } from '../environment.js';
import type { OptionSource } from '../source.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { ViewRuntime, ViewRuntimeState } from '../viewRuntimeTypes.js';
import type { PanelRuntimeFactory } from './children.js';
import type { DashboardEditing, DashboardFilterEditing } from './editing.js';
import type { DashboardPanelState } from './panels.js';
import type {
  CrossFilterOutcome,
  DestinationBoard,
  PressDestination,
} from './press.js';
import type { PanelResolver } from './references.js';
import type { ValueCandidateSources } from '../valueCandidates.js';

export interface DashboardRuntimeState extends ViewRuntimeState<DashboardViewConfig> {
  /**
   * The applied panels. Loading, errors and data are each panel's own: a
   * dashboard has no single query state to report.
   */
  panels: DashboardPanelState[];
  /** True while a panel reference is still being loaded. */
  resolving: boolean;
  /**
   * The tab on screen (D22 E): the one asked for (`showTab`) while the board
   * has it, else its first; `null` on a board without tabs. Only its panels
   * run. It is the reader's, not the board's — never in the config, so
   * switching tabs never makes a board dirty.
   */
  tab: string | null;
  /**
   * What the board's filters hold — the values the panels run under, and
   * the time grouping's unit (D22 F). The reader's, like the tab: never in
   * the config, so setting one never makes a board dirty; a host keeps it
   * in its address (`OpenOptions.filters`).
   */
  filters: DashboardFilters;
  /**
   * What building the board can take back and make again (`undo`, `redo`):
   * the step each would, or `null`. Starts empty, and empties on a revert,
   * a save and a board read anew.
   */
  history: EditHistoryState;
}

/**
 * The public face of a dashboard runtime: a view runtime whose snapshot also
 * carries the panels. `open` narrows to it by `kind`, so a caller reaches the
 * panels without knowing the class behind them.
 */
export interface DashboardRuntime
  extends
    ViewRuntime<DashboardViewConfig>,
    DashboardEditing,
    DashboardFilterEditing {
  getSnapshot(): DashboardRuntimeState;
  /** Resolves once every panel reference has been loaded or refused. */
  ready(): Promise<void>;
  /** The child runtime of one panel, for a host that drives a panel itself. */
  panelRuntime(panelId: string): DataViewRuntime | null;
  /** Re-runs one panel on what it has applied — a retry after it failed. */
  refreshPanel(panelId: string): void;
  /**
   * Loads a saved view a panel is about to show, and resolves once it has
   * settled — read or found unreadable, never rejecting. `addPanel` sizes a
   * saved view by what it shows only once it is loaded (a metric card a
   * quarter, a table the full width), so the board's 「添加」 waits for this
   * first; the child then starts on the reference already in hand.
   */
  preload(instanceId: string): Promise<void>;
  /**
   * Shows one tab: its panels run — those shown before keep their rows and
   * run only when what they ask changed, or a refresh went by while they
   * were away — and the other tabs' panels stand still (D22 E). A tab the
   * board lacks shows its first.
   */
  showTab(tabId: string | null): void;
  /**
   * Sets what one filter holds — `null` clears it, and a required one
   * clearing goes back to its default — and the panels it reaches run on
   * it a moment later on their own (「改了就跑」: a burst of keystrokes is
   * one query). Returns why a value was refused, taking nothing then; `[]`
   * when it took.
   */
  setFilterValue(name: string, value: FilterValue | null): Issue[];
  /** Sets the time grouping's unit; one the board does not offer is ignored. */
  setGroupingUnit(unit: AnalysisDateUnit): void;
  /**
   * Clears every filter (「清空」): the required ones go back to their
   * defaults, the time grouping to its default unit — but for what the host
   * holds (`holdFilters`), which stays.
   */
  clearFilters(): void;
  /**
   * The filters a host holds — an embed's locked and hidden ones (D22) —
   * and what they hold (`HeldFilters`): the values go in at once, and the
   * reader's commands leave them as they are — `setFilterValue` refuses one
   * (`dashboard.filter.held`), `clearFilters` and `setGroupingUnit` pass it
   * by, and a panel whose click sets one opens the follow-up menu instead
   * (`DashboardPanelState.click`). Each call replaces the last; a filter let
   * go keeps its value and is the reader's again. The values a text filter
   * offers are counted under what the host holds. Answers what the board
   * refused of the values, left out — every time it is asked.
   */
  holdFilters(held: HeldFilters | null): Issue[];
  /**
   * Puts every filter at once, as a host's address has them: what the
   * board takes goes in, and what it does not — a filter it no longer has,
   * a value its filter refuses — is left out and said in the answer, so one
   * stale entry never costs the rest. `setFilterValue` is all or nothing.
   */
  setFilters(filters: DashboardFilters): Issue[];
  /**
   * What the board refused of the filters it opened on (`OpenOptions.filters`
   * and `OpenOptions.held`) — left out, the rest in force from the first
   * query — so a host can say its address went partly stale; `[]` when it
   * took them all. Written as the board opens, never after.
   */
  readonly refusedFilters: Issue[];
  /**
   * The list a filter without one of its own picks from: the options the
   * fields it is wired to declare, merged, labels shown and codes stored
   * (`wiredOptions`); `null` when none declares one.
   */
  wiredOptions(name: string): FieldOption[] | null;
  /**
   * A press on one group of a panel whose click sets a filter (D22 I,
   * cross-filtering): the filter takes the group's value — every other
   * panel wired to it runs under it, this one does not and marks the group
   * — and the same group pressed again clears it. `row` is the group as
   * the chart or the table hands it back, keyed by alias.
   */
  crossFilter(panelId: string, row: RecordData): CrossFilterOutcome;
  /** Whether this group is the one the panel's press set its filter to. */
  pressed(panelId: string, row: RecordData): boolean;
  /**
   * Where a press on one group of a panel with a custom destination goes
   * (D22 I, 「去另一个视图或页面」), for the host's route; `null` for a
   * panel whose press goes nowhere of the kind.
   */
  destination(
    panelId: string,
    row: RecordData,
  ): Promise<PressDestination | null>;
  /**
   * Another board a panel's click opens, read (D23 Q17): for 「点击时…」 to
   * list its filters, and — the same read — to judge the click against it,
   * so a mapping gone stale warns on the panel from then on. Never read
   * when this board opens. `null` for one gone, unreadable or not a board.
   */
  destinationBoard(instanceId: string): Promise<DestinationBoard | null>;
}

/**
 * What a host holds of a board's filters (`DashboardRuntime.holdFilters`):
 * each filter named, at the value given — `null` for its default — and,
 * with `unit` present, the time grouping at that unit (`null` for its
 * default).
 */
export interface HeldFilters {
  values: Readonly<Record<string, FilterValue | null>>;
  unit?: AnalysisDateUnit | null;
}

/**
 * What a name 「{subject} · {group}」 claims (D20 追问): the conditions that
 * select the group, and what the view is without them. While every one of
 * `conditions` still narrows the view (`narrowsTo`) the name stands; once
 * one is taken off, edited or negated, the view goes by `subject` — a name
 * that still said the group would say what the view no longer shows.
 */
export interface GroupNaming {
  /**
   * What the view is, the group aside: the definition's name for its
   * records, or the name of the question it was opened from.
   */
  subject: string;
  /** The group's own conditions, as the view opened with them. */
  conditions: readonly FilterNode[];
}

/**
 * Where a way off the board goes, handed to the host's route (D22 D, H, I):
 * the package never touches the address, so a view opened in the workbench,
 * a follow-up on a group and a panel's custom destination are all the
 * host's to take.
 */
export type ViewNavigation =
  /**
   * A saved record or analysis view, under `filter` in its own field names:
   * the board's filters as they reach the panel (在工作台中打开), or the
   * group pressed (a custom destination). A host hands it to the view as
   * its scope.
   */
  | { kind: 'view'; instanceId: string; filter: FilterTree | null }
  /**
   * A view nobody saved: a follow-up on a group — its records, the same
   * question split by another dimension or of the group alone (D22 H) — or
   * an analysis the board owns, opened in the workbench. Its conditions,
   * the board's filters among them, are its own, as a view drilled out of
   * another's are; a host opens it as `DataWorkbench`'s `unsaved`.
   */
  | {
      kind: 'unsaved';
      definitionId: string;
      title: string;
      config: RecordViewConfig | AnalysisViewConfig;
      /** What `title` says of a group pressed, when it names one. */
      named?: GroupNaming;
    }
  /**
   * Another dashboard (D23 Q17, a panel's 「另一块仪表盘」), to open with
   * `filters` as its reader's values — the host hands them to
   * `DashboardWorkbench`'s `initialFilters` (or `OpenOptions.filters`), never
   * into the board's config: each filter the author mapped holds the group's
   * value on its dimension, every other one its default.
   */
  | {
      kind: 'dashboard';
      definitionId: string;
      instanceId: string;
      filters: DashboardFilters;
    }
  /** A page of the host's: a panel's URL filled with the group pressed. */
  | { kind: 'url'; url: string };

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
  /**
   * The definition a view the board owns is of; `null` for one this release
   * does not declare. Definitions are code, so this answers at once.
   */
  definitions(definitionId: string): PanelDefinition | null;
  createPanelRuntime: PanelRuntimeFactory;
  /** See `ViewRuntime.optionSource`. */
  resolveOptions?(key: string): OptionSource;
  /** An outer condition in force from the first execution, as for a data view. */
  scopeFilter?: FilterTree | null;
  /**
   * The values a definition's fields hold, counted, asked under `scope`
   * (`ValueCandidateSources`) — what a text filter offers from the fields it
   * is wired to (D22 G). Without it a filter's value is typed.
   */
  candidateSources?(
    definition: DataViewDefinition,
    scope: () => FilterTree | null,
  ): ValueCandidateSources;
}
