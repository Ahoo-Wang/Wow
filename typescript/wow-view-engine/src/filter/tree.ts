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
 * ANDs several trees into one, dropping the empty ones. A tree that is
 * already a plain AND group is flattened rather than nested, so merging an
 * injected scope keeps the depth budget intact.
 *
 * The runtime uses it for `setScopeFilter`, and the dashboard kernel for the
 * global filter it maps onto each panel.
 */
export function mergeFilters(
  ...trees: (FilterTree | null | undefined)[]
): FilterTree {
  const children = trees.flatMap(tree => {
    if (!tree || isEmptyFilter(tree)) return [];
    return tree.op === 'and' ? tree.children : [tree];
  });
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
 * `simple` mode shows one AND group of leaves. Anything else needs the
 * advanced editor, which is why the mode travels with the saved config.
 */
export function isSimpleTree(tree: FilterTree): boolean {
  return (
    isFilterGroup(tree) &&
    tree.op === 'and' &&
    tree.children.every(isFilterLeaf)
  );
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
