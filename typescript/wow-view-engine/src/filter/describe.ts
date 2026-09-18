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
  FilterGroupOperator,
  FilterLeaf,
  FilterTree,
  IssuePath,
} from '../model/index.js';
import {
  isBlankLeafValue,
  type FieldKind,
  type FieldKindRegistry,
} from './fieldKind.js';
import { isFilterGroup, isFilterNode } from './tree.js';

/** One applied condition, for the summary bar above a result. */
export interface FilterSummaryItem {
  /** Location of the node, so the bar can remove or focus it. */
  path: IssuePath;
  /** Human-readable condition supplied by the kind, or a group's read out. */
  text: string;
  /** The field or its kind is no longer available, in it or under it. */
  unresolved: boolean;
  /** A condition's field; a group has none. */
  field?: string;
  /** Field label, or the raw name when the field is gone. */
  label?: string;
  /** Present for a group: the operator whose word joins its text. */
  group?: FilterGroupOperator;
}

/** How a group's own operator reads between its conditions. */
export function groupJoinWord(op: FilterGroupOperator): string {
  return op === 'or' ? ' or ' : op === 'nor' ? ' nor ' : ' and ';
}

/**
 * The conditions in force, one item per child of the root, so the summary
 * keeps the tree's logic: a group under the root is one item that reads out
 * its own conditions joined by its own operator, with a group inside it in
 * parentheses. Items side by side read as "all of"; a root that is `or` or
 * `nor` therefore folds into one item that says so. Blank conditions never
 * reached the query and are left out; a condition whose field or kind is
 * gone is named rather than hidden.
 */
export function describeFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  const byName = new Map(fields.map(field => [field.name, field]));
  const items = describeGroup(tree, [], byName, kinds);
  // Items side by side read as "all of", and one item alone reads the same
  // under `or`; `nor` negates even a lone condition, so it always says so.
  if (tree.op === 'and' || (tree.op === 'or' && items.length < 2)) return items;
  if (items.length === 0) return items;
  return [groupItem(tree.op, [], items)];
}

function describeGroup(
  group: FilterTree,
  path: IssuePath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  const items: FilterSummaryItem[] = [];
  group.children.forEach((node, index) => {
    const at: IssuePath = [...path, 'children', index];
    if (!isFilterNode(node)) return;
    if (isFilterGroup(node)) {
      const inner = describeGroup(node, at, byName, kinds);
      if (inner.length > 0) items.push(groupItem(node.op, at, inner));
      return;
    }
    const item = describeCondition(node, at, byName, kinds);
    if (item) items.push(item);
  });
  return items;
}

function groupItem(
  op: FilterGroupOperator,
  path: IssuePath,
  inner: readonly FilterSummaryItem[],
): FilterSummaryItem {
  const parts = inner.map(item => (item.group ? `(${item.text})` : item.text));
  // "A nor B" needs both sides; a lone condition under `nor` is its negation.
  const text =
    op === 'nor' && parts.length === 1
      ? `not ${parts[0]}`
      : parts.join(groupJoinWord(op));
  return {
    path,
    text,
    unresolved: inner.some(item => item.unresolved),
    group: op,
  };
}

function describeCondition(
  node: FilterLeaf,
  path: IssuePath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): FilterSummaryItem | null {
  const field = byName.get(node.field);
  const kind = field ? kinds.get(field.kind) : undefined;
  // A condition that was never finished did not reach the query, so it is
  // not one of the conditions in force.
  if (
    field &&
    kind &&
    isBlankLeafValue(node.value, node.operator, field, kind, kinds)
  )
    return null;
  const described =
    field && kind ? describeLeaf(kind, node, field, kinds) : undefined;
  if (!field || described === undefined)
    return {
      path,
      field: node.field,
      label: field?.label ?? node.field,
      text: `${field?.label ?? node.field} ${node.operator}`,
      unresolved: true,
    };
  return {
    path,
    field: field.name,
    label: field.label,
    text: described,
    unresolved: false,
  };
}

function describeLeaf(
  kind: FieldKind,
  leaf: FilterLeaf,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
): string | undefined {
  try {
    return kind.describe({ leaf, field, kinds });
  } catch {
    return undefined;
  }
}
