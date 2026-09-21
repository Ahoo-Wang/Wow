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

import { dequal } from 'dequal';
import {
  nodeAt,
  sameFilterNode,
  walkFilter,
  type FilterPath,
} from '../filter/index.js';
import type { FilterTree, Issue, ViewConfig } from '../model/index.js';

/**
 * What the draft says that the applied config does not (D17-6).
 *
 * "Changed, not applied" is one of the screen's three credentials (D2), and
 * it used to be measured on the filter tree alone. Every other member of the
 * config could stand apart from `applied` with nothing on screen to say so:
 * a control that edits without applying — the filter mode, the analysis
 * editor's groups, metrics, sort, limit and chart, which all wait for Run —
 * and a control that edits and applies whose apply was refused, because the
 * draft holds an error and `apply` does not land. The sort header, the page
 * size and the column settings then read the draft while the rows are still
 * the last execution's.
 *
 * So the credential is the whole config: `draft` against `applied`, member
 * by member. The conditions keep their finer reading — node by node, which is
 * what marks one pill and not its ancestors — and every other member counts
 * as one edit when it differs. A refused apply counts, since the two configs
 * still disagree; a draft equal to what was applied does not, whatever the
 * issues say about either.
 *
 * It lives in the runtime layer because `dequal` is the runtime's (see
 * `test/architecture.test.ts`): the same equality `revert` decides by.
 */
export interface PendingReport {
  /** True when the draft says anything the applied config does not. */
  pending: boolean;
  /**
   * How many edits wait for apply: the conditions edited since the last
   * apply, node by node, plus one for every other member that differs.
   */
  count: number;
  /**
   * Whether the conditions themselves differ. The filter editor's discard
   * puts the conditions back and nothing else, so it is offered on this
   * rather than on `pending`.
   */
  conditions: boolean;
}

const EMPTY_TREE: FilterTree = { op: 'and', children: [] };

/**
 * Whether `issues` refuse the config's own filter tree for its size. Such a
 * tree — from a store, possibly holding a cycle — is not walked at all: it
 * blocked apply, so it stands apart from `applied` by construction and
 * counts as one edit. An analysis element's or a dashboard panel's own
 * filter reports the very same codes under `['elements', …]` or
 * `['panels', …]`; the path is what says the finding is the root tree's.
 */
export function filterOverBudget(issues: readonly Issue[]): boolean {
  return issues.some(
    found =>
      (found.code === 'filter.tree.too-deep' ||
        found.code === 'filter.tree.too-many-nodes') &&
      (found.path.length === 0 || found.path[0] === 'children'),
  );
}

export function comparePending(
  draft: ViewConfig,
  applied: ViewConfig,
  issues: readonly Issue[],
): PendingReport {
  let count = 0;
  let conditions = false;
  // Member by member, over whichever members either side has: a config is a
  // closed record per kind, but this compares all three kinds the same way.
  const was: Record<string, unknown> = { ...applied };
  const now: Record<string, unknown> = { ...draft };
  const keys = new Set([...Object.keys(now), ...Object.keys(was)]);
  for (const key of keys) {
    // The dot says "the rows on screen do not answer the config you see".
    // A member that never reaches the query cannot make that true: the
    // editor's simple/advanced mode, and a record view's table-or-cards
    // layout, both draw the same result — switching them is complete the
    // moment it is done, and a dot that asked for an Apply with nothing
    // to run would teach the user to press buttons that change nothing.
    // (An analysis layout is not one of these: the kernel shapes a chart
    // only for the layout that ran, so `setLayout` there applies at once.)
    if (key === 'filterMode' || (key === 'layout' && draft.kind === 'record'))
      continue;
    const before = was[key];
    const after = now[key];
    if (key === 'filter') {
      if (before === after) continue;
      const nodes = filterOverBudget(issues)
        ? 1
        : pendingNodes(
            (after as FilterTree | undefined) ?? EMPTY_TREE,
            (before as FilterTree | undefined) ?? EMPTY_TREE,
          );
      if (nodes > 0) conditions = true;
      count += nodes;
      continue;
    }
    if (!dequal(before, after)) count += 1;
  }
  return { pending: count > 0, count, conditions };
}

/**
 * The nodes of `tree` that say something other than `inForce` at the same
 * path, and the nodes only `inForce` has. A condition taken out of the draft
 * is still an edit not applied: the rows on screen were fetched under it.
 * Counting the draft alone would leave a badge of 0 beside an Apply button
 * that has something to do — a cleared filter most of all.
 */
function pendingNodes(tree: FilterTree, inForce: FilterTree): number {
  let count = 0;
  const visited = new Set<string>();
  for (const { node, path } of walkFilter(tree)) {
    const at = indexesOf(path);
    visited.add(at.join(','));
    if (!sameFilterNode(node, nodeAt(inForce, at))) count += 1;
  }
  for (const { path } of walkFilter(inForce)) {
    const at = indexesOf(path);
    if (!visited.has(at.join(','))) count += 1;
  }
  return count;
}

/** A walk's path as the editor addresses nodes: the child indexes alone. */
function indexesOf(path: readonly (string | number)[]): FilterPath {
  return path.filter((step): step is number => typeof step === 'number');
}
