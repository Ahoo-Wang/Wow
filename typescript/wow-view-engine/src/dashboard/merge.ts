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
  DashboardViewConfig,
  FilterNode,
  FilterTree,
  PanelBinding,
} from '../model/index.js';
import {
  emptyFilter,
  isEmptyFilter,
  isFilterGroup,
  mergeFilters,
} from '../filter/index.js';

/**
 * The condition every data panel of a board runs under before its filters,
 * in the board's own field names: its fixed scope (`fixed`) ANDed with its
 * `filter` — empty on every board this engine writes, and where admission
 * carries a host's condition (`withScopeFilter`).
 *
 * The two ANDs open into one, so a fixed scope read out of a pre-C
 * `filter` (`migrateDashboardConfig`) is judged, mapped and asked exactly
 * as it was there, a host's condition after it. A member that is no tree
 * adds nothing: admission reports it (`config.filter.invalid`).
 */
export function boardCondition(config: DashboardViewConfig): FilterTree {
  const fixed = isFilterGroup(config.fixed) ? config.fixed : emptyFilter();
  const filter = isFilterGroup(config.filter) ? config.filter : emptyFilter();
  if (isEmptyFilter(filter)) return fixed;
  if (isEmptyFilter(fixed)) return filter;
  return { op: 'and', children: [...conjuncts(fixed), ...conjuncts(filter)] };
}

/** The conditions an AND holds, or the tree itself as one. */
function conjuncts(tree: FilterTree): FilterNode[] {
  return tree.op === 'and' ? tree.children : [tree];
}

/**
 * Rewrites a dashboard's global filter onto one panel's fields.
 *
 * The tree keeps its shape and every leaf keeps its operator and value: only
 * the field name changes. That is what makes the mapping boolean-preserving,
 * and it is why `validateDashboard` insists a panel bind *every* field the
 * tree mentions — dropping one branch of an OR would silently narrow the
 * condition, and treating it as true would erase it.
 *
 * The tree must have been admitted first: its depth is bounded by
 * `RuntimeLimits.maxFilterDepth`, so the walk below cannot run away.
 */
export function mapGlobalFilter(
  tree: FilterTree,
  bindings: readonly PanelBinding[],
): FilterTree {
  const byGlobal = new Map(
    bindings.map(binding => [binding.globalField, binding.panelField]),
  );
  return mapGroup(tree, byGlobal);
}

/**
 * The panel's own applied filter ANDed with the mapped global filter.
 *
 * A dashboard's condition never reaches the referenced instance's draft or
 * saved config; it is an outer scope, which is why the runtime injects the
 * mapped tree through `setScopeFilter` and this function exists for admission,
 * where the merged tree is what the budget and the panel's definition judge.
 */
export function mergeGlobalFilter(
  panelFilter: FilterTree,
  dashboardFilter: FilterTree,
  bindings: readonly PanelBinding[],
): FilterTree {
  return mergeFilters(panelFilter, mapGlobalFilter(dashboardFilter, bindings));
}

function mapGroup(
  group: FilterTree,
  byGlobal: ReadonlyMap<string, string>,
): FilterTree {
  return {
    ...group,
    children: group.children.map(child => mapNode(child, byGlobal)),
  };
}

function mapNode(
  node: FilterNode,
  byGlobal: ReadonlyMap<string, string>,
): FilterNode {
  if (isFilterGroup(node)) return mapGroup(node, byGlobal);
  const mapped = byGlobal.get(node.field);
  // An unbound leaf keeps its global name and is then reported as an unknown
  // field by the panel's own `validateFilter`, rather than vanishing here.
  return mapped === undefined ? node : { ...node, field: mapped };
}
