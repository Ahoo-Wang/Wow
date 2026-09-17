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

import { filter, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FieldDefinition,
  FilterGroup,
  FilterNode,
  FilterTree,
} from '../model/index.js';
import { isBlankLeafValue, type FieldKindRegistry } from './fieldKind.js';
import { isFilterGroup } from './tree.js';

/**
 * When a filter is evaluated, and in which zone. Relative conditions are
 * resolved here rather than by the backend, so a compilation is reproducible.
 */
export interface FilterCompileContext {
  now: Date;
  timeZone: string;
}

/**
 * Compiles an admitted tree into a Wow filter expression.
 *
 * Only `validateFilter` decides what is admissible; this function assumes the
 * tree passed it and throws if a field or kind is missing, which is a
 * programming error rather than a user-visible issue.
 */
export function compileFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): FilterExpression {
  const byName = new Map(fields.map(field => [field.name, field]));
  const compiled = compileNode(tree, byName, kinds, context);
  // An empty tree means "no condition", which Wow spells as MATCH_ALL.
  return compiled ?? filter.matchAll();
}

function compileNode(
  node: FilterNode,
  fields: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): FilterExpression | null {
  if (isFilterGroup(node)) return compileGroup(node, fields, kinds, context);

  const field = fields.get(node.field);
  if (!field)
    throw new Error(`Filter references an undeclared field: ${node.field}`);
  const kind = kinds.get(field.kind);
  if (!kind)
    throw new Error(`Filter uses an unregistered field kind: ${field.kind}`);

  // An unfinished condition narrows nothing. Returning null drops it the way
  // an empty group is dropped, so a half-written row never reaches the server
  // as `amount = 0` or "created today".
  if (isBlankLeafValue(node.value, node.operator, field, kind)) return null;

  return kind.compile({
    leaf: node,
    field,
    kinds,
    now: context.now,
    timeZone: context.timeZone,
  });
}

function compileGroup(
  group: FilterGroup,
  fields: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): FilterExpression | null {
  const operands: FilterExpression[] = [];
  for (const child of group.children) {
    const compiled = compileNode(child, fields, kinds, context);
    // Empty groups carry no condition; Wow rejects empty AND/OR operands.
    if (compiled) operands.push(compiled);
  }
  // An empty group carries no condition, whatever its operator: `and` and
  // `or` over nothing are vacuous, and so is "none of nothing".
  if (operands.length === 0) return null;
  // A lone operand is its own conjunction and its own disjunction — but not
  // its own negation, so `nor` keeps its wrapper or it would compile to the
  // exact condition it was meant to exclude.
  if (operands.length === 1 && group.op !== 'nor') return operands[0];
  if (group.op === 'nor') return filter.nor(operands);
  return group.op === 'or' ? filter.or(operands) : filter.and(operands);
}
