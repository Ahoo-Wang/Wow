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
import {
  isFilterGroup,
  isFilterLeaf,
  walkFilter,
  walkFilterShape,
} from './tree.js';

/** The operators a group may carry; a tree from a store may say anything. */
const GROUP_OPERATORS: readonly FilterGroupOperator[] = ['and', 'or', 'nor'];

export interface ValidateFilterOptions {
  limits?: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>;
}

/**
 * Admits a tree that arrived from a store.
 *
 * The shape is checked first, with an iterative walk, so an oversized tree is
 * reported instead of exhausting the stack in a later pass, and an entry that
 * is not a node at all is reported at its path instead of dereferenced by one.
 * Fields come as a list rather than as a definition, because a dashboard
 * validates its own global fields.
 */
export function validateFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
  options: ValidateFilterOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const shape = checkShape(tree, fields, kinds, limits);
  if (shape.length > 0) return shape;

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
    if (isBlankLeafValue(node.value, node.operator, field, kind, kinds))
      continue;

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
 * The whole tree's skeleton: its budget, nested trees included, and every
 * entry that is not a node.
 *
 * One budget covers the nesting rather than one per level: the limits are
 * there so a tree from a store cannot exhaust the stack, and a per-level
 * budget would let a leaf carry a full tree that carries a full tree, which
 * is the same unbounded growth counted differently. Depth continues through
 * a nested root, so nesting costs depth as plainly as a group does.
 *
 * A malformed entry is found on the same walk, because the budget is the one
 * pass that may see the whole tree, and it costs a node like any other so a
 * list of a million `null`s is stopped by the count. Exceeding the budget
 * ends the walk at once and is the only finding, since the tree is refused
 * whole; otherwise every malformed entry is collected, so a corrupt config
 * is reported in one round.
 */
function checkShape(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>,
): Issue[] {
  const malformed: Issue[] = [];
  const budget = walkShape(tree, fields, kinds, limits, {
    counted: { nodes: 0 },
    depthOffset: 0,
    prefix: [],
    malformed,
  });
  return budget ? [budget] : malformed;
}

interface ShapeWalk {
  counted: { nodes: number };
  depthOffset: number;
  prefix: IssuePath;
  malformed: Issue[];
}

/** The budget issue that ended the walk, or `null` when it ran to the end. */
function walkShape(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>,
  walk: ShapeWalk,
): Issue | null {
  const byName = new Map(fields.map(field => [field.name, field]));

  for (const { node, path, depth } of walkFilterShape(tree)) {
    walk.counted.nodes += 1;
    const at = [...walk.prefix, ...path];
    if (depth + walk.depthOffset > limits.maxFilterDepth)
      return issue('filter.tree.too-deep', at, { max: limits.maxFilterDepth });
    if (walk.counted.nodes > limits.maxFilterNodes)
      return issue('filter.tree.too-many-nodes', [], {
        max: limits.maxFilterNodes,
      });

    if (node === null) {
      walk.malformed.push(issue('filter.node.invalid', at));
      continue;
    }
    if (!isFilterLeaf(node)) continue;
    const field = byName.get(node.field);
    const kind = field ? kinds.get(field.kind) : undefined;
    const nested = kind?.nested?.(node.value, field as FieldDefinition);
    if (!nested) continue;

    const budget = walkShape(nested.tree, nested.fields, kinds, limits, {
      ...walk,
      depthOffset: depth + walk.depthOffset,
      prefix: at,
    });
    if (budget) return budget;
  }
  return null;
}

/**
 * True when the tree says nothing: it has no condition, or every condition it
 * has is still waiting to be filled in.
 *
 * This is the same judgement `validateFilter` and `compileFilter` make leaf by
 * leaf, asked of the whole tree, so a kind whose value is a tree can answer
 * `isBlank` with it and stay consistent with the tree around it. A leaf that
 * is wrong rather than unfilled — an unknown field, an unregistered kind, an
 * operator the field does not offer — is not blank, because forgiving it here
 * would hide the finding `validateFilter` owes.
 */
export function isBlankFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
): boolean {
  const byName = new Map(fields.map(field => [field.name, field]));
  for (const { node } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    const field = byName.get(node.field);
    const kind = field ? kinds.get(field.kind) : undefined;
    if (!field || !kind) return false;
    if (!operatorsOf(field, kind).includes(node.operator)) return false;
    if (!isBlankLeafValue(node.value, node.operator, field, kind, kinds))
      return false;
  }
  return true;
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
