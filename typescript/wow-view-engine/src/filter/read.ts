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
  FilterGroup,
  FilterLeaf,
  FilterTree,
} from '../model/index.js';
import type { FieldKindRegistry } from './fieldKind.js';
import { filterIndexes } from './issuePath.js';
import {
  isFilterGroup,
  isFilterLeaf,
  walkFilter,
  type FilterPath,
} from './tree.js';

/**
 * `tree` with each leaf read as its field's kind reads it
 * (`FieldKind.readLeaf`): a condition saved before its field changed kind
 * — an `EQ` on a field that was a string and is now an enum — in the shape
 * the kind offers. The same tree where no leaf reads differently.
 *
 * A tree from a store may hold anything, and may be deeper than any budget
 * admits: it is walked iteratively, and only the groups above a leaf that
 * reads differently are copied. An entry that is not a leaf, a field nobody
 * declares or a kind nobody registered is left as it came, for
 * `validateFilter` to say. A host's kind whose `readLeaf` throws leaves its
 * leaf as it came too; its `validate` judges it as written.
 */
export function readFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
): FilterTree {
  if (!isFilterGroup(tree)) return tree;
  const byName = new Map(fields.map(field => [field.name, field]));
  let read = tree;
  for (const { node, path } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    const leaf = readLeaf(node, byName, kinds);
    if (leaf !== node) read = replaceAt(read, filterIndexes(path), leaf);
  }
  return read;
}

function readLeaf(
  leaf: FilterLeaf,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): FilterLeaf {
  const field = byName.get(leaf.field);
  const kind = field && kinds.get(field.kind);
  if (!field || !kind?.readLeaf) return leaf;
  try {
    return kind.readLeaf(leaf, field);
  } catch {
    return leaf;
  }
}

/** `tree` with the node at `at` replaced, each group above it copied. */
function replaceAt(
  tree: FilterTree,
  at: FilterPath,
  leaf: FilterLeaf,
): FilterTree {
  const root: FilterTree = { ...tree, children: [...tree.children] };
  let group: FilterGroup = root;
  for (const index of at.slice(0, -1)) {
    const child = group.children[index] as FilterGroup;
    const copy: FilterGroup = { ...child, children: [...child.children] };
    group.children[index] = copy;
    group = copy;
  }
  group.children[at[at.length - 1]] = leaf;
  return root;
}
