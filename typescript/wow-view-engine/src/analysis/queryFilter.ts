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
  FieldDefinition,
  FilterTree,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import {
  countLeaves,
  issue,
  isBlankLeafValue,
  isFilterLeaf,
  operatorsOf,
  validateFilter,
  walkFilter,
  type FieldKindRegistry,
} from '../filter/index.js';

/**
 * Admits a metric's own filter, which reaches compilation either way.
 *
 * Two checks, because the filter is wrong in two different ways. It is an
 * ordinary filter over the analysis scope, so it must name fields that exist
 * and hold values its operators can take — nothing was checking that at all.
 * It is also in metric position, where Wow allows less than it does at the
 * root.
 */
/** Where a filter sits, which decides what it may say and how it is reported. */
export type QueryFilterPosition = 'metric' | 'element';

const QUERY_FILTER_CODES = {
  metric: {
    empty: 'analysis.metricFilter.empty',
    incomplete: 'analysis.metricFilter.incomplete',
  },
  element: {
    empty: 'analysis.elementFilter.empty',
    incomplete: 'analysis.elementFilter.incomplete',
  },
} as const;

export function queryFilterIssues(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
  position: QueryFilterPosition,
  path: IssuePath,
): Issue[] {
  const admitted = validateFilter(fields, tree, kinds, { limits });
  // The budget is there so a tree from a store cannot cost unbounded work.
  // `validateFilter` answers an oversized tree with the budget issue alone, so
  // a second full walk here would spend exactly what the budget refused.
  const issues = admitted.some(found => BUDGET_CODES.includes(found.code))
    ? admitted
    : [...admitted, ...saysNothingIssues(tree, fields, kinds, position)];
  return issues.map(found => ({ ...found, path: [...path, ...found.path] }));
}

/** What `validateFilter` answers with, alone, when a tree is over budget. */
const BUDGET_CODES = ['filter.tree.too-deep', 'filter.tree.too-many-nodes'];

/**
 * Refuses a filter that says nothing.
 *
 * A filter panel is a surface: someone puts a condition there because it is
 * one they reach for often, and leaving it empty is how they say "not right
 * now". So everywhere else an empty condition is unfinished rather than wrong,
 * and `compileFilter` drops it.
 *
 * A filter that is part of the query is not a surface. Neither a metric's own
 * filter nor an element's expansion gate has an editor at all; the choice is
 * between having one and not having one, and both are written as the `filter`
 * property being present or absent. Having written one, a filter that says
 * nothing compiles to `MATCH_ALL` and widens silently — a count meant to be of
 * paid orders returns all of them, an expansion meant to be of shipped lines
 * takes every line. Wrong numbers, no warning. It can say nothing in two ways,
 * so both are refused: a condition with no value, and no conditions at all.
 *
 * A metric filter carries one more restriction, which an element filter does
 * not. It decides, per record, whether that record counts toward this one
 * metric — MongoDB re-expresses it as a `$cond` guard and Elasticsearch as a
 * filter aggregation — so it has one record's value to work with, and a kind
 * that declares itself non-scalar has no such value: it compiles to a
 * condition over the entries of a collection, or to a match across the
 * record's text. The kind answers this rather than a list of ids here, because
 * `withFieldKinds` lets an app replace a built-in kind or register one of its
 * own, and what settles it is the shape a kind compiles to.
 *
 * The metadata kinds stay usable in a metric filter, unlike inside an element
 * predicate where they are refused: an element is not a record and has no id
 * or owner, but a metric filter is looking at a whole record.
 */
function saysNothingIssues(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  position: QueryFilterPosition,
): Issue[] {
  const codes = QUERY_FILTER_CODES[position];
  if (countLeaves(tree) === 0) return [issue(codes.empty, [])];

  const byName = new Map(fields.map(field => [field.name, field]));
  const issues: Issue[] = [];
  for (const { node, path } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    // An unknown field, a kind no registry holds and an operator the field
    // does not offer are all `validateFilter`'s to report, not this one's.
    const field = byName.get(node.field);
    if (!field) continue;
    const kind = kinds.get(field.kind);
    if (!kind || !operatorsOf(field, kind).includes(node.operator)) continue;

    if (position === 'metric' && kind.scalar === false)
      issues.push(
        issue('analysis.metricFilter.not-scalar', path, { field: field.name }),
      );
    else if (isBlankLeafValue(node.value, node.operator, field, kind, kinds))
      issues.push(issue(codes.incomplete, path, { field: field.name }));
  }
  return issues;
}
