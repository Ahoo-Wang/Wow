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

/**
 * What a view's config uses that its source no longer admits (capabilities.md
 * Q2), and taking it out.
 *
 * The findings are the ones admission raises against the definition as the
 * source's descriptor narrowed it and does not raise against the definition
 * as declared: the config was fine, the deployment is what changed. The
 * view waits to be fixed, as for any error, and nothing is sent; this is the
 * one-click fix 「移除不可用的条件」 offers.
 */

import {
  without,
  type AnalysisViewConfig,
  type DataViewConfig,
  type FilterTree,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import {
  isFilterLeaf,
  removeConditionAt,
  walkFilter,
  type FilterPath,
} from '../filter/index.js';

/** The errors of `effective` that `declared` does not have. */
export function unavailableIssues(
  effective: readonly Issue[],
  declared: readonly Issue[],
): Issue[] {
  const known = new Set(declared.filter(isError).map(found => keyOf(found)));
  return effective.filter(
    found =>
      isError(found) &&
      !known.has(keyOf(found)) &&
      // A view with no condition where the source wants one uses nothing
      // it lacks: it has not asked yet, and says so as its own state (Q3).
      found.code !== 'record.filter.required',
  );
}

/**
 * The config with the first finding that can be taken out taken out, or
 * `null` when none of them can: a condition goes (a filter's own or a
 * metric's), a sort entry goes, 「只保留」 goes whole, a dimension keeps
 * its field but loses the missing-value group or the filled gaps. A
 * dimension or a metric the source cannot compute stays — taking it out
 * would change the question, not trim it — and is left to the reader.
 */
export function withoutFirstUnavailable<C extends DataViewConfig>(
  config: C,
  issues: readonly Issue[],
): C | null {
  for (const found of issues) {
    const next = without1(config, found);
    if (next) return next;
  }
  return null;
}

/** Whether `withoutFirstUnavailable` can take a finding out. */
export function isRemovable(config: DataViewConfig, found: Issue): boolean {
  return without1(config, found) !== null;
}

function without1<C extends DataViewConfig>(config: C, found: Issue): C | null {
  const [head, index, member] = found.path;
  if (head === 'children') {
    const at = treePath(found.path);
    return at.length === 0
      ? null
      : { ...config, filter: removeConditionAt(config.filter, at) };
  }
  if (head === 'sort' && typeof index === 'number') {
    const sort = (config.sort as unknown[]).filter((_, i) => i !== index);
    return { ...config, sort };
  }
  if (config.kind !== 'analysis') return null;
  if (head === 'having' && config.having !== undefined) {
    const next: AnalysisViewConfig = { ...config };
    delete next.having;
    return next as C;
  }
  if (head === 'groups' && typeof index === 'number') {
    const group = config.groups[index];
    if (!group || (member !== 'missingKey' && member !== 'dense')) return null;
    const groups = config.groups.map((entry, i) =>
      i === index ? without(entry as never, member) : entry,
    );
    return { ...config, groups };
  }
  if (head === 'metrics' && typeof index === 'number' && member === 'filter') {
    const metric = config.metrics[index];
    if (!metric || !('filter' in metric) || !metric.filter) return null;
    const filter = trimmed(metric.filter, found);
    if (!filter) return null;
    const metrics = config.metrics.map((entry, i) =>
      i === index ? { ...entry, filter } : entry,
    );
    return { ...config, metrics };
  }
  return null;
}

/**
 * A metric's condition less what the finding is about: the node it points
 * into, or every condition on the field it names.
 */
function trimmed(tree: FilterTree, found: Issue): FilterTree | null {
  const at = treePath(found.path.slice(3));
  if (at.length > 0) return removeConditionAt(tree, at);
  const field = found.params?.field;
  if (typeof field !== 'string') return null;
  const paths: FilterPath[] = [];
  for (const visit of walkFilter(tree))
    if (isFilterLeaf(visit.node) && visit.node.field === field)
      paths.push(treePath(visit.path));
  if (paths.length === 0) return null;
  // The deepest and last first, so an earlier path still points where it did.
  return paths
    .reverse()
    .reduce((next, path) => removeConditionAt(next, path), tree);
}

/** The node an issue path points into: each index that follows `children`. */
function treePath(path: IssuePath): FilterPath {
  const at: FilterPath = [];
  for (let i = 0; i + 1 < path.length && path[i] === 'children'; i += 2) {
    const index = path[i + 1];
    if (typeof index !== 'number') break;
    at.push(index);
  }
  return at;
}

function isError(found: Issue): boolean {
  return found.severity === 'error';
}

function keyOf(found: Issue): string {
  return `${found.code} ${JSON.stringify(found.path)}`;
}
