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
  FilterGroup,
  FilterLeaf,
  FilterNode,
  FilterTree,
  IssuePath,
} from '../model/index.js';

/**
 * Whether a value is a node at all.
 *
 * Trees arrive from a store, so a child may be `null`, a number, or an object
 * that is neither a group nor a leaf. Every predicate here is total over
 * `unknown`, and the walk skips what fails this one, so a corrupt entry is
 * reported by `validateFilter` at its path rather than thrown as a
 * `TypeError` from whichever pass reached it first.
 */
export function isFilterNode(value: unknown): value is FilterNode {
  return isFilterGroup(value) || isWellFormedLeaf(value);
}

/**
 * Asks what the node actually is rather than whether a property happens to
 * be present: a leaf carrying a stray `children` of the wrong shape would
 * otherwise be walked as a group.
 */
export function isFilterGroup(node: unknown): node is FilterGroup {
  return isObject(node) && Array.isArray(node.children);
}

export function isFilterLeaf(node: unknown): node is FilterLeaf {
  return !isFilterGroup(node) && isWellFormedLeaf(node);
}

/** A leaf names a field and an operator; the value is the kind's to judge. */
function isWellFormedLeaf(node: unknown): node is FilterLeaf {
  return (
    isObject(node) &&
    typeof node.field === 'string' &&
    typeof node.operator === 'string'
  );
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** An empty tree. A config always carries a tree, never `null`. */
export function emptyFilter(): FilterTree {
  return { op: 'and', children: [] };
}

/** Drops every condition while keeping the tree's shape valid. */
export function clearFilter(): FilterTree {
  return emptyFilter();
}

/**
 * ANDs several trees into one, dropping the empty ones. The first tree is
 * the base and keeps its shape: a plain AND group contributes its children
 * as they are, so a path into it still leads to the same node. Every later
 * tree is appended as one nested group, never flattened: a group holds one
 * condition per field, and an injected scope on a field the base already
 * asks about — a host narrowing to one region over a view saved for another
 * — is a second question, not a duplicate. It costs one level of depth for
 * the scope's own subtree, which a single condition does not even show on
 * the wire, since a group of one compiles to that one.
 *
 * The runtime uses it for `setScopeFilter`, and the dashboard kernel for the
 * global filter it maps onto each panel.
 */
export function mergeFilters(
  ...trees: (FilterTree | null | undefined)[]
): FilterTree {
  const [base, ...scopes] = trees;
  const present = scopes.filter(
    (scope): scope is FilterTree => !!scope && !isEmptyFilter(scope),
  );
  // Nothing to merge is the base as it stands: an `or` root must not gain a
  // wrapper here that admission never saw, or a tree judged at the depth
  // budget would run one level deeper than it was admitted at.
  if (present.length === 0) return base ?? emptyFilter();
  const children: FilterNode[] = [];
  if (base && !isEmptyFilter(base))
    children.push(...(base.op === 'and' ? base.children : [base]));
  children.push(...present);
  return { op: 'and', children };
}

/**
 * Where a node sits: child indexes from the root outwards, so `[1, 0]` is the
 * first child of the second child. The root group itself is `[]`.
 */
export type FilterPath = number[];

/** The node at a path, or `null` when the path leads nowhere. */
export function nodeAt(tree: FilterTree, path: FilterPath): FilterNode | null {
  let node: FilterNode = tree;
  for (const index of path) {
    if (!isFilterGroup(node)) return null;
    const child: unknown = node.children[index];
    if (!isFilterNode(child)) return null;
    node = child;
  }
  return node;
}

/**
 * Replaces the node at a path, or removes it when `update` returns `null`.
 * Everything outside the path keeps its identity, so an editor re-renders only
 * the branch it touched. The root cannot be replaced: `[]` returns the tree.
 */
export function updateAt(
  tree: FilterTree,
  path: FilterPath,
  update: (node: FilterNode) => FilterNode | null,
): FilterTree {
  if (path.length === 0) return tree;
  return { ...tree, children: updateChildren(tree.children, path, update) };
}

/** Removes the node at a path. */
export function removeAt(tree: FilterTree, path: FilterPath): FilterTree {
  return updateAt(tree, path, () => null);
}

/** Appends a node to the group at `parent`; `[]` is the root group. */
export function insertAt(
  tree: FilterTree,
  parent: FilterPath,
  node: FilterNode,
): FilterTree {
  if (parent.length === 0)
    return { ...tree, children: [...tree.children, node] };
  return updateAt(tree, parent, current =>
    isFilterGroup(current)
      ? { ...current, children: [...current.children, node] }
      : current,
  );
}

function updateChildren(
  children: FilterNode[],
  path: FilterPath,
  update: (node: FilterNode) => FilterNode | null,
): FilterNode[] {
  const [index, ...rest] = path;
  const current: unknown = children[index];
  if (!isFilterNode(current)) return children;

  if (rest.length === 0) {
    const next = update(current);
    return next === null
      ? [...children.slice(0, index), ...children.slice(index + 1)]
      : [...children.slice(0, index), next, ...children.slice(index + 1)];
  }
  if (!isFilterGroup(current)) return children;

  // Depth is bounded by `RuntimeLimits.maxFilterDepth`, checked on admission.
  return [
    ...children.slice(0, index),
    { ...current, children: updateChildren(current.children, rest, update) },
    ...children.slice(index + 1),
  ];
}

/** True when the tree holds no leaf at any depth. */
/**
 * Whether a tree asks nothing: well-formed groups all the way down and not
 * one leaf. A tree holding a malformed entry is not empty — it is admission's
 * to report — so `mergeFilters` must not drop it as if it said nothing, or a
 * stored filter that lost its shape would vanish behind an injected scope
 * and the query would run wider than the view was saved to be.
 */
export function isEmptyFilter(tree: FilterTree): boolean {
  for (const visit of walkFilterShape(tree)) {
    if (visit.node === null || isFilterLeaf(visit.node)) return false;
  }
  return true;
}

/**
 * A group that says "not this": `nor` over exactly one condition.
 *
 * It is how a condition is negated (D18-7): the pill's switch wraps the leaf
 * in a group of its own rather than every kind growing a negated operator,
 * and the compiler already turns `nor` into the protocol's negation. It is
 * also the one group shape the simple editor still draws as a pill.
 */
export function isNegation(
  node: unknown,
): node is FilterGroup & { children: [FilterLeaf] } {
  return (
    isFilterGroup(node) &&
    node.op === 'nor' &&
    node.children.length === 1 &&
    isFilterLeaf(node.children[0])
  );
}

/**
 * `simple` mode shows one AND group of conditions, each on its own or
 * negated. Anything else needs the advanced editor, which is why the mode
 * travels with the saved config.
 */
export function isSimpleTree(tree: FilterTree): boolean {
  return (
    isFilterGroup(tree) &&
    tree.op === 'and' &&
    tree.children.every(child => isFilterLeaf(child) || isNegation(child))
  );
}

/**
 * The tree with the condition at `path` negated — or, when it already sits
 * alone in a `nor` group, that group replaced by the condition itself.
 * `path` is the leaf's, inside its wrapper when it has one, which is the
 * path the pill holds either way. The root is never wrapped: a root that
 * negates itself has no simple reading and no pill.
 */
export function negateAt(tree: FilterTree, path: FilterPath): FilterTree {
  const leaf = nodeAt(tree, path);
  if (path.length === 0 || !isFilterLeaf(leaf)) return tree;
  const above = path.slice(0, -1);
  const parent = above.length === 0 ? tree : nodeAt(tree, above);
  if (above.length > 0 && isNegation(parent))
    return updateAt(tree, above, () => leaf);
  return updateAt(tree, path, () => ({ op: 'nor', children: [leaf] }));
}

export interface TreeVisit {
  node: FilterNode;
  path: IssuePath;
  depth: number;
}

/**
 * A place in the tree holding something that is not a node: `null`, a
 * number, an object with neither `children` nor `field`. It has a path and a
 * depth like any node, so the budget counts it and an issue can point at it,
 * but nothing below it is walked.
 */
export interface MalformedVisit {
  node: null;
  path: IssuePath;
  depth: number;
}

/**
 * Walks the tree iteratively, malformed entries included. Configs arrive from
 * a store, so nothing here may recurse before the budget in `validateFilter`
 * has admitted the tree, and that budget is the one caller that needs to see
 * the malformed entries: they cost a node each, and they are its to report.
 */
export function* walkFilterShape(
  tree: unknown,
): Generator<TreeVisit | MalformedVisit> {
  const stack: { value: unknown; path: IssuePath; depth: number }[] = [
    { value: tree, path: [], depth: 1 },
  ];
  while (stack.length > 0) {
    const { value, path, depth } = stack.pop() as (typeof stack)[number];
    // A tree is a group; a leaf at the root is as malformed as a number.
    if (!isFilterNode(value) || (depth === 1 && !isFilterGroup(value))) {
      yield { node: null, path, depth };
      continue;
    }
    yield { node: value, path, depth };
    if (!isFilterGroup(value)) continue;
    const children = value.children;
    for (let index = children.length - 1; index >= 0; index -= 1) {
      stack.push({
        value: children[index],
        path: [...path, 'children', index],
        depth: depth + 1,
      });
    }
  }
}

/** Walks the nodes of the tree, skipping whatever is not one. */
export function* walkFilter(tree: FilterTree): Generator<TreeVisit> {
  for (const visit of walkFilterShape(tree))
    if (visit.node !== null) yield visit;
}

/**
 * How many places a comparison may look at before it gives up and answers
 * "not the same".
 *
 * These functions run on a *draft*, which `validateFilter` has not admitted
 * and may never admit: the editor asks what changed on every render, and a
 * tree deeper than `maxFilterDepth`, wider than `maxFilterNodes` or holding
 * a cycle would otherwise exhaust the stack in the middle of one. So the
 * walk is iterative and counted, and the count is generous next to the
 * limits the validator budgets with — a tree it admits is 256 nodes deep at
 * most 8, and even one carrying a value per node stays far under this —
 * because a comparison that gave up early would report an edit that is not
 * there. A draft past this point is one the panel refuses to render anyway.
 */
export const DEFAULT_COMPARE_BUDGET = 100_000;

/**
 * Whether two nodes say the same thing *at their own level*: a leaf by its
 * field, operator and value, a group by its operator alone.
 *
 * A group deliberately ignores its children, because the callers walk the
 * tree and ask about every node. Comparing children here would mark a group,
 * its parent and the root as changed for one edited condition, and "3 not
 * applied" for a single edit is a lie the user cannot act on. `null` stands
 * for "no node there", which is never equal to a node — an added condition
 * is a change, whatever it says.
 *
 * `dequal` would answer the value part, but the runtime is the only layer
 * allowed to import it (`test/architecture.test.ts`), and this comparison
 * belongs to the editor rather than to the store of state.
 */
export function sameFilterNode(
  left: FilterNode | null,
  right: FilterNode | null,
  budget: number = DEFAULT_COMPARE_BUDGET,
): boolean {
  if (left === right) return true;
  if (left === null || right === null) return false;
  if (isFilterGroup(left) || isFilterGroup(right))
    return isFilterGroup(left) && isFilterGroup(right) && left.op === right.op;
  return (
    left.field === right.field &&
    left.operator === right.operator &&
    sameValue(left.value, right.value, budget)
  );
}

/** Whether two trees are the same tree, children and all. */
export function sameFilterTree(
  left: FilterTree,
  right: FilterTree,
  budget: number = DEFAULT_COMPARE_BUDGET,
): boolean {
  return sameValue(left, right, budget);
}

/**
 * Structural equality over what a config may hold: JSON values, and the
 * nodes built from them. Configs round-trip through a store, so two trees
 * that mean the same thing are rarely the same objects.
 *
 * Iterative and budgeted, for the reason `DEFAULT_COMPARE_BUDGET` gives: an
 * over-deep or cyclic draft answers `false` rather than throwing.
 */
function sameValue(left: unknown, right: unknown, budget: number): boolean {
  const stack: { left: unknown; right: unknown }[] = [{ left, right }];
  let visited = 0;
  while (stack.length > 0) {
    visited += 1;
    if (visited > budget) return false;
    const pair = stack.pop() as (typeof stack)[number];
    const here = pair.left;
    const there = pair.right;
    if (here === there) continue;
    if (Array.isArray(here) || Array.isArray(there)) {
      if (
        !Array.isArray(here) ||
        !Array.isArray(there) ||
        here.length !== there.length
      )
        return false;
      for (let index = 0; index < here.length; index += 1)
        stack.push({ left: here[index], right: there[index] });
      continue;
    }
    if (!isObject(here) || !isObject(there)) return false;
    const keys = Object.keys(here);
    if (keys.length !== Object.keys(there).length) return false;
    for (const key of keys) {
      if (!(key in there)) return false;
      stack.push({ left: here[key], right: there[key] });
    }
  }
  return true;
}

export function countLeaves(tree: FilterTree): number {
  let leaves = 0;
  for (const { node } of walkFilter(tree)) if (isFilterLeaf(node)) leaves += 1;
  return leaves;
}

/** Every field the tree mentions, in first-seen order. */
export function filterFields(tree: FilterTree): string[] {
  const seen = new Set<string>();
  for (const { node } of walkFilter(tree))
    if (isFilterLeaf(node)) seen.add(node.field);
  return [...seen];
}
