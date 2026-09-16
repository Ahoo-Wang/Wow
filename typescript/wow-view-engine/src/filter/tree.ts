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

export function isFilterGroup(node: FilterNode): node is FilterGroup {
  return (node as FilterGroup).children !== undefined;
}

export function isFilterLeaf(node: FilterNode): node is FilterLeaf {
  return !isFilterGroup(node);
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
    const child: FilterNode | undefined = node.children[index];
    if (!child) return null;
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
  const current = children[index];
  if (!current) return children;

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
export function isEmptyFilter(tree: FilterTree): boolean {
  return countLeaves(tree) === 0;
}

/**
 * `simple` mode shows one AND group of leaves. Anything else needs the
 * advanced editor, which is why the mode travels with the saved config.
 */
export function isSimpleTree(tree: FilterTree): boolean {
  return tree.op === 'and' && tree.children.every(isFilterLeaf);
}

export interface TreeVisit {
  node: FilterNode;
  path: IssuePath;
  depth: number;
}

/**
 * Walks the tree iteratively. Configs arrive from a store, so nothing here may
 * recurse before the budget in `validateFilter` has admitted the tree.
 */
export function* walkFilter(tree: FilterTree): Generator<TreeVisit> {
  const stack: TreeVisit[] = [{ node: tree, path: [], depth: 1 }];
  while (stack.length > 0) {
    const visit = stack.pop() as TreeVisit;
    yield visit;
    if (!isFilterGroup(visit.node)) continue;
    const children = visit.node.children;
    for (let index = children.length - 1; index >= 0; index -= 1) {
      stack.push({
        node: children[index],
        path: [...visit.path, 'children', index],
        depth: visit.depth + 1,
      });
    }
  }
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
