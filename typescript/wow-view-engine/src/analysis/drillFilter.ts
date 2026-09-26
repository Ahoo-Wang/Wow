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
  FilterLeaf,
  FilterNode,
  FilterTree,
  FilterValue,
} from '../model/index.js';
import {
  isDateTimeFilterValue,
  isFilterGroup,
  isFilterLeaf,
  isSimpleTree,
  resolveDateTimeRange,
  sameFilterTree,
} from '../filter/index.js';

/**
 * How a drilled group's conditions join the conditions it was drilled
 * under (`drillFilter`), and whether a view still narrows to them
 * (`narrowsTo`).
 */

/**
 * The filter a drilled view opens under: the analysis view's own conditions
 * with the row's added, flattened into one "all of" group when the analysis
 * filter was one — so the record view opens in simple mode wherever the
 * analysis view was in it — and nested under it otherwise.
 *
 * A group of a simple tree holds one condition per field
 * (`filter.field.duplicate-in-group`), and a row's range is on the field the
 * analysis was often already scoped by — the day of a stretch of days, a
 * status among the statuses asked about. Both still hold, so neither is
 * dropped unless the other says all of it: a scope's absolute stretch that
 * holds the row's whole stretch gives its place to the row's, which then
 * says the same records in one condition. Otherwise the row's conditions on
 * that field go into an "all of" of their own beside the scope's — the one
 * way a tree asks two things of one field — which is exact, and read in the
 * editor's advanced mode.
 */
export function drillFilter(
  applied: FilterTree,
  conditions: readonly FilterNode[],
): FilterTree {
  if (!isSimpleTree(applied))
    return { op: 'and', children: [applied, ...conditions] };
  const children: FilterNode[] = [...applied.children];
  const added: FilterNode[] = [];
  const nested = new Map<string, FilterNode[]>();
  for (const condition of conditions) {
    const clash =
      isFilterLeaf(condition) && condition.operator !== 'EXPRESSION'
        ? children.findIndex(
            child =>
              isFilterLeaf(child) &&
              child.operator !== 'EXPRESSION' &&
              child.field === condition.field,
          )
        : -1;
    if (clash < 0) {
      added.push(condition);
      continue;
    }
    const leaf = condition as FilterLeaf;
    const own = nested.get(leaf.field);
    if (own) own.push(leaf);
    else if (holdsWhole(children[clash] as FilterLeaf, leaf))
      children[clash] = leaf;
    else {
      const group: FilterNode[] = [leaf];
      nested.set(leaf.field, group);
      added.push({ op: 'and', children: group });
    }
  }
  return { op: 'and', children: [...children, ...added] };
}

/**
 * Whether `scope` holds every record `row` does, read without a clock: two
 * absolute stretches of time, each pinned to a zone or an offset, the one
 * inside the other. A relative or preset scope moves with the clock, and a
 * stretch in no zone waits for the runtime's; both stay beside the row.
 */
function holdsWhole(scope: FilterLeaf, row: FilterLeaf): boolean {
  if (scope.operator !== 'BETWEEN' || row.operator !== 'BETWEEN') return false;
  const outer = pinnedStretch(scope.value);
  const inner = pinnedStretch(row.value);
  return (
    outer !== null &&
    inner !== null &&
    outer.from <= inner.from &&
    inner.to <= outer.to
  );
}

const OFFSET = /(?:Z|[+-]\d{2}:?\d{2})$/i;

function pinnedStretch(
  value: FilterValue,
): { from: number; to: number } | null {
  if (!isDateTimeFilterValue(value) || value.type !== 'absolute') return null;
  if (value.to === undefined) return null;
  const pinned =
    value.timeZone !== undefined ||
    (OFFSET.test(value.from) && OFFSET.test(value.to));
  if (!pinned) return null;
  // The zone only reads a bound without an offset; a pinned one has either.
  const range = resolveDateTimeRange(
    value,
    new Date(0),
    value.timeZone ?? 'UTC',
  );
  const from = Date.parse(range.from);
  const to = range.to === undefined ? NaN : Date.parse(range.to);
  return Number.isFinite(from) && Number.isFinite(to) ? { from, to } : null;
}

/**
 * Whether `filter` still narrows to the group `drillFilter` added: every one
 * of `conditions` is a conjunct of it — a child of its root "all of", or of
 * an "all of" inside that, which is where `drillFilter` puts them however
 * many times it has been over the tree. A condition taken off, edited or
 * negated since is not, and neither is one that now sits under an "any of":
 * the view is no longer that group, and the name that said so is stale.
 */
export function narrowsTo(
  filter: FilterTree,
  conditions: readonly FilterNode[],
): boolean {
  const conjuncts: FilterNode[] = [];
  const pending: FilterNode[] = [filter];
  while (pending.length > 0) {
    const node = pending.pop() as FilterNode;
    if (isFilterGroup(node) && node.op === 'and')
      pending.push(...node.children);
    else conjuncts.push(node);
  }
  return conditions.every(condition =>
    conjuncts.some(node => sameFilterTree(node, condition)),
  );
}
