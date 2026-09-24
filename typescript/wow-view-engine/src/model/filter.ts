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

import type { FilterOperator } from '@ahoo-wang/wow-client';
import type { JsonValue } from './json.js';

/**
 * Operator name as stored in a configuration: the same values as Wow's
 * `FilterOperator`, as string literals, so a config stays plain JSON.
 */
export type FilterOperatorName = `${FilterOperator}`;

/**
 * The scope of a view, shared by all three kinds. Depth and node count are
 * bounded by `RuntimeLimits`, because a tree arrives from a store.
 */
export type FilterTree = FilterGroup;

/**
 * How a group combines its children, as the same three Wow spells `AND`,
 * `OR` and `NOR`. `nor` means none of the children match, which is the only
 * way to express a negation here: leaves negate through their operator, and a
 * group has no operator of its own to negate with.
 */
export type FilterGroupOperator = 'and' | 'or' | 'nor';

/**
 * The three, in the order a group's select offers them.
 *
 * One list, beside the type it spells out: the select in a group's header,
 * the menu that nests a new group and the admission of a tree from a store
 * all have to agree on which operators exist, and three copies of
 * `['and', 'or', 'nor']` is three chances for one of them to be behind.
 */
export const FILTER_GROUP_OPERATORS: readonly FilterGroupOperator[] = [
  'and',
  'or',
  'nor',
];

/**
 * Whether a value is one of the three. A tree arrives from a store and a
 * select hands back a string, so both ask here rather than spelling the
 * list out again as a chain of comparisons.
 */
export function isFilterGroupOperator(
  value: unknown,
): value is FilterGroupOperator {
  return FILTER_GROUP_OPERATORS.includes(value as FilterGroupOperator);
}

export interface FilterGroup {
  op: FilterGroupOperator;
  children: FilterNode[];
}

export interface FilterLeaf {
  /** Field name as declared by the definition, or a dashboard global field. */
  field: string;
  operator: FilterOperatorName;
  value: FilterValue;
}

export type FilterNode = FilterGroup | FilterLeaf;

/**
 * The semantic value of a leaf. Its shape belongs to the field's `FieldKind`
 * (see the `filter` layer), never to a compiled query: "last 7 days" is stored
 * as a relative intent and evaluated at execution time.
 */
export type FilterValue = JsonValue;

/** `simple` mode only admits a single AND group whose children are all leaves. */
export type FilterMode = 'simple' | 'advanced';
