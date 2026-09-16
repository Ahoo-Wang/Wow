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
import type { FieldKindRegistry } from './fieldKind.js';
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

  return kind.compile({
    leaf: node,
    field,
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
  if (operands.length === 0) return null;
  if (operands.length === 1) return operands[0];
  return group.op === 'or' ? filter.or(operands) : filter.and(operands);
}
