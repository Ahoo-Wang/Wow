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
  FilterLeaf,
  FilterNode,
  FilterTree,
} from '../model/index.js';

/**
 * The search a view keeps on hand: the text of the search condition on the
 * tree's root, which is where a search box reads and writes it.
 *
 * One condition, on the root: a search box is one box, and a search nested
 * in a group is a condition somebody composed in the editor — the box
 * neither reads it as its own nor overwrites it. `''` when there is none.
 */
export function rootSearch(tree: FilterTree, field: string): string {
  if (tree.op !== 'and') return '';
  const leaf = tree.children.find(
    (node): node is FilterLeaf => isLeaf(node) && node.field === field,
  );
  return leaf && typeof leaf.value === 'string' ? leaf.value : '';
}

/**
 * The tree with its root search set to `text`: the condition's value
 * replaced where it is, added at the end where there was none, and taken
 * out when the text is blank — an empty search is no condition, and a pill
 * left behind for it would be one the reader never added.
 *
 * A root that is not an `and` — an advanced tree whose top is an `or` —
 * keeps its meaning: it becomes the first child of a new `and`, next to the
 * search, so the search narrows it rather than joining its alternatives.
 */
export function withRootSearch(
  tree: FilterTree,
  field: string,
  text: string,
): FilterTree {
  const blank = text.trim().length === 0;
  const root: FilterTree =
    tree.op === 'and' || (blank && tree.children.length === 0)
      ? tree
      : { op: 'and', children: tree.children.length > 0 ? [tree] : [] };
  const at = root.children.findIndex(
    node => isLeaf(node) && node.field === field,
  );
  if (blank)
    return at < 0
      ? root
      : { ...root, children: root.children.filter((_, i) => i !== at) };
  const leaf: FilterLeaf = { field, operator: 'SEARCH', value: text };
  const children =
    at < 0
      ? [...root.children, leaf]
      : root.children.map((node, i) => (i === at ? leaf : node));
  return { ...root, children };
}

/**
 * The definition's search field — the first, where it declares several —
 * that still searches: one narrowed to no operator (its source offers no
 * search it can use, capabilities.md 4.2, G15) draws no search box.
 */
export function searchFieldOf(
  fields: readonly FieldDefinition[],
): FieldDefinition | null {
  return (
    fields.find(
      field => field.kind === 'search' && field.operators?.length !== 0,
    ) ?? null
  );
}

function isLeaf(node: FilterNode): node is FilterLeaf {
  return 'field' in node;
}
