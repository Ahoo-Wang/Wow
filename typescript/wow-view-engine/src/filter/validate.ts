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

import {
  DEFAULT_RUNTIME_LIMITS,
  type FieldDefinition,
  type IssuePath,
  type FilterGroupOperator,
  type FilterTree,
  type Issue,
  type RuntimeLimits,
} from '../model/index.js';
import {
  isBlankLeafValue,
  issue,
  operatorsOf,
  type FieldKindRegistry,
} from './fieldKind.js';
import { isFilterGroup, isFilterLeaf, walkFilter } from './tree.js';

/** The operators a group may carry; a tree from a store may say anything. */
const GROUP_OPERATORS: readonly FilterGroupOperator[] = ['and', 'or', 'nor'];

export interface ValidateFilterOptions {
  limits?: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>;
}

/**
 * Admits a tree that arrived from a store.
 *
 * The budget is checked first, with an iterative walk, so an oversized tree is
 * reported instead of exhausting the stack in a later pass. Fields come as a
 * list rather than as a definition, because a dashboard validates its own
 * global fields.
 */
export function validateFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
  options: ValidateFilterOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const budget = checkBudget(tree, fields, kinds, limits);
  if (budget.length > 0) return budget;

  const byName = new Map(fields.map(field => [field.name, field]));
  const issues: Issue[] = [];

  for (const { node, path } of walkFilter(tree)) {
    if (isFilterGroup(node)) {
      if (!GROUP_OPERATORS.includes(node.op))
        issues.push(issue('filter.group.unknown-operator', path));
      continue;
    }

    const field = byName.get(node.field);
    if (!field) {
      issues.push(issue('filter.field.unknown', path, { field: node.field }));
      continue;
    }

    const kind = kinds.get(field.kind);
    if (!kind) {
      // Without a kind the kernel has no validate and no compile, so this
      // blocks apply rather than degrading to a read-only editor.
      issues.push(
        issue('filter.kind.unregistered', path, { kind: field.kind }),
      );
      continue;
    }

    if (!operatorsOf(field, kind).includes(node.operator)) {
      issues.push(
        issue('filter.operator.unsupported', path, {
          field: field.name,
          operator: node.operator,
        }),
      );
      continue;
    }

    // A condition the user has not finished writing is not a mistake, so it
    // is left alone here and dropped at compile time. Without this, picking a
    // field would report an error before the user could say anything.
    if (isBlankLeafValue(node.value, node.operator, field, kind)) continue;

    issues.push(
      ...kind.validate({
        value: node.value,
        operator: node.operator,
        field,
        kinds,
        path,
      }),
    );
  }

  return issues;
}

/**
 * The whole tree's budget, nested trees included.
 *
 * One budget covers the nesting rather than one per level: the limits are
 * there so a tree from a store cannot exhaust the stack, and a per-level
 * budget would let a leaf carry a full tree that carries a full tree, which
 * is the same unbounded growth counted differently. Depth continues through
 * a nested root, so nesting costs depth as plainly as a group does.
 */
function checkBudget(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>,
): Issue[] {
  const counted = { nodes: 0 };
  return walkBudget(tree, fields, kinds, limits, counted, 0, []);
}

function walkBudget(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>,
  counted: { nodes: number },
  depthOffset: number,
  prefix: IssuePath,
): Issue[] {
  const byName = new Map(fields.map(field => [field.name, field]));

  for (const { node, path, depth } of walkFilter(tree)) {
    counted.nodes += 1;
    const at = [...prefix, ...path];
    if (depth + depthOffset > limits.maxFilterDepth)
      return [
        issue('filter.tree.too-deep', at, { max: limits.maxFilterDepth }),
      ];
    if (counted.nodes > limits.maxFilterNodes)
      return [
        issue('filter.tree.too-many-nodes', [], {
          max: limits.maxFilterNodes,
        }),
      ];

    if (!isFilterLeaf(node)) continue;
    const field = byName.get(node.field);
    const kind = field ? kinds.get(field.kind) : undefined;
    const nested = kind?.nested?.(node.value, field as FieldDefinition);
    if (!nested) continue;

    const found = walkBudget(
      nested.tree,
      nested.fields,
      kinds,
      limits,
      counted,
      depth + depthOffset,
      at,
    );
    if (found.length > 0) return found;
  }
  return [];
}

/** True when every leaf of the tree is usable as it stands. */
export function isExecutableFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
  options: ValidateFilterOptions = {},
): boolean {
  return !validateFilter(fields, tree, kinds, options).some(
    found => found.severity === 'error',
  );
}

export { isFilterLeaf };
