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

import type { FilterNode, FilterTree, Issue } from '../model/index.js';
import { filterIndexes } from './issuePath.js';
import {
  isFilterGroup,
  isFilterLeaf,
  nodeAt,
  type FilterPath,
} from './tree.js';

/**
 * The errors a condition editor is not already marking.
 *
 * A wrong condition is marked on its own pill and counted on the Apply
 * button, which is both nearer to the mistake and the only place the mistake
 * can be corrected; repeating it in a strip above says the same thing twice
 * and further from the fix. Everything else — a column the definition
 * dropped, a page size it no longer admits — has no pill to sit on, and a
 * strip is the only way it is ever seen.
 *
 * Which is why the draft tree is asked rather than the path alone: a
 * condition-shaped path is not the same thing as a pill. An editor draws a
 * node only when the tree really holds one there — a malformed child is
 * skipped, and a group carries no marker of its own — so an error addressing
 * anything but a rendered condition is marked nowhere and belongs in the
 * strip. Apply is still refused for it: `blocked` counts every
 * condition-level error, whether or not a pill could be found for it.
 *
 * It lives here rather than beside the editor because it is a question about
 * the tree and nothing else: which paths resolve to a condition a renderer
 * can draw. `useFilterEditor` hands the answer out as `unmarked`, the pair of
 * `blocked`.
 */
export function unmarkedErrors(
  issues: readonly Issue[],
  /** The draft the editor beside this strip is showing. */
  tree: FilterTree,
): Issue[] {
  return issues.filter(
    found => found.severity === 'error' && !isMarked(found, tree),
  );
}

/** Whether a condition editor puts this finding on a pill of its own. */
function isMarked(found: Issue, tree: FilterTree): boolean {
  if (!found.code.startsWith('filter.') || found.path[0] !== 'children')
    return false;
  // Only a condition wears a mark; a group's own findings wear none.
  return isFilterLeaf(conditionAt(tree, filterIndexes(found.path)));
}

/**
 * The node a path addresses, descending into a predicate leaf's own tree as
 * the panel does: an element match renders the group its value carries, and
 * the findings inside it are marked on the conditions of that group.
 */
function conditionAt(tree: FilterTree, path: FilterPath): FilterNode | null {
  let node: FilterNode | null = tree;
  for (const index of path) {
    const group = groupOf(node);
    if (group === null) return null;
    node = nodeAt(group, [index]);
  }
  return node;
}

/** The group a node holds conditions in, its own or a predicate's. */
function groupOf(node: FilterNode | null): FilterTree | null {
  if (node === null) return null;
  if (isFilterGroup(node)) return node;
  return isFilterGroup(node.value) ? node.value : null;
}
