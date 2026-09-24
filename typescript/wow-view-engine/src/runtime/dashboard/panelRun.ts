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
  FilterTree,
  Issue,
  RuntimeLimits,
} from '../../model/index.js';
import { mergeFilters, type FieldKindRegistry } from '../../filter/index.js';
import {
  bindingsOf,
  filterReach,
  mapGlobalFilter,
  panelFilterTree,
  type FilterReach,
} from '../../dashboard/index.js';
import type { PanelView } from './children.js';
import { regrouped, type PanelGrouping } from './grouping.js';
import { presentedConfig } from './presentation.js';

/** What a panel's child is handed by the board it sits on. */
export interface PanelRun {
  /** The view, its config the view's own as the board presents it. */
  view: PanelView;
  /** The condition the board narrows it by, in the view's own field names. */
  scope: FilterTree;
  /** What the board says about it: an override dropped, a unit kept. */
  issues: Issue[];
}

/**
 * What one data panel runs on this board: the view's own config with the
 * panel's override of how it looks laid over it (`presentedConfig`), its
 * time dimension at the board's unit where its definition allows that
 * (`regrouped`, D22 F), and under the board's standing condition, the
 * host's, and every filter wired to it that holds a value — each in the
 * panel's own field names (`panelFilterTree`) — but one whose value was
 * pressed on this panel (`DashboardFilters.from`).
 */
export function panelRun(
  panel: DashboardViewPanel,
  index: number,
  view: PanelView,
  board: {
    applied: DashboardViewConfig;
    filters: DashboardFilters;
    injected: FilterTree | null;
    kinds: FieldKindRegistry;
    limits: RuntimeLimits;
  },
): PanelRun {
  const { applied, filters, injected, kinds, limits } = board;
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
  return {
    view: { ...view, config: grouped.config },
    scope: panelScope(panel, { applied, filters, injected, kinds }),
    issues: [...presented.issues, ...grouped.issues],
  };
}

/**
 * The one condition a data panel runs under, in its own field names: the
 * board's fixed scope (`config.filter`) and the host's condition, mapped
 * through the panel's bindings, ANDed with every filter wired to it that
 * holds a value in `filters` (`panelFilterTree`) — but one whose value was
 * pressed on this panel (`DashboardFilters.from`). The panel runs under it
 * with every value in force (`panelRun`); what a text filter offers is
 * counted under it with only the values the host holds
 * (`FilterValues.candidatesOf`), so the two never tell apart what scope a
 * panel is in.
 */
export function panelScope(
  panel: DashboardViewPanel,
  board: {
    applied: DashboardViewConfig;
    filters: DashboardFilters;
    injected: FilterTree | null;
    kinds: FieldKindRegistry;
  },
): FilterTree {
  const { applied, filters, injected, kinds } = board;
  const bindings = bindingsOf(panel);
  // A filter whose value was pressed on this very panel does not narrow it
  // (D22 I): the panel keeps every group, and marks the one pressed.
  const wired = bindings.filter(
    binding => filters.from?.[binding.globalField] !== panel.id,
  );
  return mergeFilters(
    mapGlobalFilter(mergeFilters(applied.filter, injected), bindings),
    panelFilterTree(applied, filters, wired, kinds),
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
    reach: filterReach(applied, panel, view?.definition.fields ?? null),
    grouping: view
      ? regrouped(view.config, view.definition, filters.unit, []).grouping
      : null,
  };
}
