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

import type {
  DashboardFilters,
  DashboardPanel,
  DashboardViewConfig,
  DashboardViewPanel,
  FilterNode,
  FilterTree,
  Issue,
  PanelBinding,
  RuntimeLimits,
} from '../../model/index.js';
import {
  isEmptyFilter,
  isPlainObject,
  mergeFilters,
  type FieldKindRegistry,
} from '../../filter/index.js';
import {
  bindingsOf,
  boardCondition,
  filterReach,
  isViewPanel,
  mapGlobalFilter,
  panelFilterTree,
  panelsOf,
  panelTab,
  type FilterReach,
} from '../../dashboard/index.js';
import { boardFieldsOf } from '../../dashboard/boardFields.js';
import type { HandOver } from '../navigation.js';
import { hasError } from '../runtimeStore.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { PanelView } from './children.js';
import type { DashboardRuntimeState } from './contract.js';
import {
  blocksBoard,
  clickInForce,
  panelOf,
  type DashboardPanelState,
} from './panels.js';
import { regrouped, type PanelGrouping } from './grouping.js';
import { presentedConfig } from './presentation.js';
import { panelAnchor, type AnchorClock, type PanelAnchor } from './anchor.js';

/** What a panel's child is handed by the board it sits on. */
export interface PanelRun {
  /** The view, its config the view's own as the board presents it. */
  view: PanelView;
  /** The condition the board narrows it by, in the view's own field names. */
  scope: FilterTree;
  /** What the board says about it: an override dropped, a unit kept. */
  issues: Issue[];
  /** A trend card anchored to the period a date filter picked (D39). */
  anchor: PanelAnchor | null;
}

/**
 * What one data panel runs on this board: the view's own config with the
 * panel's override of how it looks laid over it (`presentedConfig`), its
 * time dimension at the board's unit where its definition allows that
 * (`regrouped`, D22 F), and under the board's standing condition and
 * every filter wired to it that holds a value — each in the
 * panel's own field names (`panelFilterTree`) — but one whose value was
 * pressed on this panel (`DashboardFilters.from`). A trend card whose axis
 * a date filter holds one period of runs anchored to it (`panelAnchor`,
 * D39): its own dates read as of that period, the window ending with it.
 */
export function panelRun(
  panel: DashboardViewPanel,
  index: number,
  view: PanelView,
  board: {
    applied: DashboardViewConfig;
    filters: DashboardFilters;
    kinds: FieldKindRegistry;
    limits: RuntimeLimits;
    clock: AnchorClock;
  },
): PanelRun {
  const { applied, filters, kinds, limits, clock } = board;
  const presented = presentedConfig(
    view.config,
    panel.presentation,
    { definition: view.definition, kinds, limits },
    ['panels', index, 'presentation'],
  );
  const grouped = regrouped(presented.config, view.definition, filters.unit, [
    'panels',
    index,
  ]);
  const anchor = panelAnchor(
    grouped.config,
    wiredOn(panel, filters),
    { applied, filters },
    clock,
  );
  return {
    view: { ...view, config: anchor?.config ?? grouped.config },
    scope: panelScope(panel, { applied, filters, kinds }, anchor),
    issues: [...presented.issues, ...grouped.issues],
    anchor,
  };
}

/**
 * The one condition a data panel runs under, in its own field names: the
 * board's fixed scope (`boardCondition`: `fixed`, D26 Q31), mapped through
 * the panel's bindings, ANDed with every filter wired to it that holds a
 * value in `filters` (`panelFilterTree`) — but one whose value was pressed
 * on this panel (`DashboardFilters.from`). The panel runs under it with
 * every value in force (`panelRun`); what a text filter offers is counted
 * under it with only the values the host holds
 * (`FilterValues.candidatesOf`), so the two never tell apart what scope a
 * panel is in. An anchored card (`panelAnchor`, D39) runs under its window
 * in place of the date filter's own condition.
 */
export function panelScope(
  panel: DashboardViewPanel,
  board: {
    applied: DashboardViewConfig;
    filters: DashboardFilters;
    kinds: FieldKindRegistry;
  },
  anchor: PanelAnchor | null = null,
): FilterTree {
  const { applied, filters, kinds } = board;
  const bindings = bindingsOf(panel);
  const wired = anchoredOut(wiredOn(panel, filters), anchor);
  const own = panelFilterTree(applied, filters, wired, kinds);
  return mergeFilters(
    mapGlobalFilter(boardCondition(applied), bindings),
    anchor
      ? { op: 'and', children: [...(own?.children ?? []), anchor.leaf] }
      : own,
  );
}

/** The bindings but the one an anchored card's window stands in for. */
function anchoredOut(
  bindings: PanelBinding[],
  anchor: PanelAnchor | null,
): PanelBinding[] {
  return anchor
    ? bindings.filter(
        binding => binding.globalField !== anchor.binding.globalField,
      )
    : bindings;
}

/**
 * The same condition as `panelScope`, in the two parts a view takes off
 * the board (D26 Q30): what is not the reader's — the board's fixed scope
 * (`fixed`, D26 Q31) and what the page holds (`held`) — is the scope the
 * opened view runs under, which nobody there takes off; the rest — the
 * reader's values — becomes the view's own conditions. `null` for a part
 * that holds nothing. An anchored card hands its window over in place of
 * the date filter's condition, in the part that filter's value is in.
 */
export function panelHandOver(
  panel: DashboardViewPanel,
  board: {
    applied: DashboardViewConfig;
    filters: DashboardFilters;
    held(name: string): boolean;
    kinds: FieldKindRegistry;
  },
  anchor: PanelAnchor | null = null,
): { scopeFilter: FilterTree | null; filter: FilterTree | null } {
  const { applied, filters, held, kinds } = board;
  const wired = anchoredOut(wiredOn(panel, filters), anchor);
  const window = (page: boolean) =>
    anchor && held(anchor.binding.globalField) === page ? [anchor.leaf] : [];
  const part = (page: boolean) => ({
    values: Object.fromEntries(
      Object.entries(filters.values).filter(([name]) => held(name) === page),
    ),
  });
  const bindings = bindingsOf(panel);
  // Each one flat "all of", the scope with the board's fixed scope first:
  // the conditions a reader then sees one by one.
  const scope = [
    ...conjuncts(mapGlobalFilter(boardCondition(applied), bindings)),
    ...conjuncts(panelFilterTree(applied, part(true), wired, kinds)),
    ...window(true),
  ];
  const own = [
    ...conjuncts(panelFilterTree(applied, part(false), wired, kinds)),
    ...window(false),
  ];
  return {
    scopeFilter: scope.length > 0 ? { op: 'and', children: scope } : null,
    filter: own.length > 0 ? { op: 'and', children: own } : null,
  };
}

/** A panel's child as the board left it, and what the panel reports. */
export interface PanelOutcome {
  runtime: DataViewRuntime | null;
  issues: Issue[];
}

/**
 * The state of every panel on a board as it now stands (`boardPanels`):
 * what the runtime knows of it, and its two ways of treating a data panel's
 * child — `run` for one on the tab shown, `hold` for one elsewhere.
 */
export interface BoardPanelsHost {
  applied: DashboardViewConfig;
  /** Admission of `applied`. */
  issues: readonly Issue[];
  tab: string | null;
  filters: DashboardFilters;
  held(name: string): boolean;
  viewOf(panel: DashboardViewPanel): PanelView | null;
  /** A data panel on the tab shown, its child brought in line with it. */
  run(panel: DashboardViewPanel, index: number, own: Issue[]): PanelOutcome;
  /** A data panel elsewhere: the child it has, kept as it stands, or none. */
  hold(
    panel: DashboardViewPanel,
    index: number,
    own: Issue[],
  ): PanelOutcome | null;
}

/**
 * Every panel's state on the board as it now stands. Only the tab on
 * screen runs (D22 E): a panel elsewhere keeps the child it has, rows and
 * all, exactly as it stands — neither re-scoped nor re-run — until its tab
 * is shown; one never shown has none yet. A problem with the board itself
 * stops every panel, the rule `apply` follows; a panel's own problem stops
 * only that one. It is judged here because a reference arriving also comes
 * here, and a view waiting to be fixed must not start querying behind it.
 */
export function boardPanels(host: BoardPanelsHost): DashboardPanelState[] {
  const { applied, issues, tab, filters } = host;
  // "Too many panels" sits at `['panels']` and belongs to no one panel, so
  // it counts against the whole rather than slipping between the two.
  const blocked = blocksBoard(issues);
  return panelsOf(applied).flatMap((panel, index): DashboardPanelState[] => {
    // Admission reports an entry that is no panel at its index; there is
    // no id to build a state under, and nothing to run.
    if (!isPlainObject(panel)) return [];
    const own = issues.filter(found => panelOf(found) === index);
    const on = panelTab(applied, panel);
    const shown = on === tab;
    const data = isViewPanel(panel) ? panel : null;
    const runs = data !== null && !blocked;
    const outcome = !runs
      ? null
      : shown
        ? host.run(data, index, own)
        : host.hold(data, index, own);
    const { runtime, issues: reported } = outcome ?? {
      runtime: null,
      issues: own,
    };
    return [
      {
        id: panel.id,
        panel,
        runtime,
        issues: reported,
        tab: on,
        waiting: runs && !shown && runtime === null && !hasError(own),
        ...clickInForce(panel, reported, host.held),
        ...panelReach(applied, panel, data && host.viewOf(data), filters),
      },
    ];
  });
}

/**
 * What one data panel's view takes off the board as it stands
 * (`DashboardRuntime.handOver`): its two parts (`panelHandOver`), and — on
 * a board that was saved — the board it left, for the way back to it with
 * the tab and the filters it was read on. `null` for no such data panel.
 */
export function boardHandOver(
  panelId: string,
  state: Pick<
    DashboardRuntimeState,
    'applied' | 'filters' | 'saved' | 'title' | 'tab'
  >,
  board: {
    definitionId: string;
    held(name: string): boolean;
    kinds: FieldKindRegistry;
    /** The window a panel anchored at its last run (`PanelRun.anchor`). */
    anchor?(panelId: string): PanelAnchor | null;
  },
): HandOver | null {
  const { applied, filters, saved, title, tab } = state;
  const panel = panelsOf(applied).find(
    (entry): entry is DashboardViewPanel =>
      isViewPanel(entry) && entry.id === panelId,
  );
  if (!panel) return null;
  const parts = panelHandOver(
    panel,
    { ...board, applied, filters },
    board.anchor?.(panelId) ?? null,
  );
  if (!saved) return parts;
  return {
    ...parts,
    from: {
      title,
      back: {
        kind: 'dashboard',
        definitionId: board.definitionId,
        instanceId: saved.id,
        filters,
        tab,
      },
    },
  };
}

/** The conditions a tree ANDs, one by one; none for an empty or no tree. */
function conjuncts(tree: FilterTree | null): FilterNode[] {
  if (!tree || isEmptyFilter(tree)) return [];
  return tree.op === 'and' ? tree.children : [tree];
}

/**
 * The panel's bindings but the ones whose value was pressed on this very
 * panel, which do not narrow it (D22 I): the panel keeps every group, and
 * marks the one pressed.
 */
function wiredOn(
  panel: DashboardViewPanel,
  filters: DashboardFilters,
): PanelBinding[] {
  return bindingsOf(panel).filter(
    binding => filters.from?.[binding.globalField] !== panel.id,
  );
}

/**
 * What the board's filters and its time grouping do to one panel, for the
 * panel to say (「不受此筛选影响」) and the bar to dim what reaches nothing
 * on the tab: see `filterReach` and `PanelGrouping`.
 */
export function panelReach(
  applied: DashboardViewConfig,
  panel: DashboardPanel,
  view: PanelView | null,
  filters: DashboardFilters,
): { reach: Record<string, FilterReach>; grouping: PanelGrouping } {
  return {
    reach: filterReach(
      applied,
      panel,
      view ? boardFieldsOf(view.config.kind, view.definition.fields) : null,
    ),
    grouping: view
      ? regrouped(view.config, view.definition, filters.unit, []).grouping
      : null,
  };
}
