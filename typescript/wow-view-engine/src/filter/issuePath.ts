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
 * How an issue path reads against a filter tree.
 *
 * A finding names where it is, as a path of config keys and indexes, and two
 * questions get asked of that path all over this package: **is it about the
 * tree I am showing**, and **which node of it**. Both were spelled out
 * wherever they were needed — in the editor, in the pending report, on a
 * condition pill, in the applied bar — and a spelling is exactly the kind of
 * thing that drifts: a metric's filter reports the same codes under
 * `['metrics', …]`, so a predicate that forgets to ask would mark the root
 * tree for a nested tree's error.
 */
import type { Issue, IssuePath } from '../model/index.js';
import type { FilterPath } from './tree.js';

/**
 * Whether a finding is about the config's own filter tree rather than a
 * nested one.
 *
 * The root tree's findings sit either at the config root — the tree as a
 * whole was refused, for its size or its shape — or under `['children', …]`,
 * which addresses one of its nodes. A metric's, an element's or a dashboard
 * panel's filter is validated in its own scope and re-pathed under
 * `['metrics', …]`, `['elements', …]` or `['panels', …]`, so the first
 * segment is what tells the two apart.
 */
export function isRootFilterIssue(found: Issue): boolean {
  return found.path.length === 0 || found.path[0] === 'children';
}

/**
 * The node a path addresses, as the tree's own addressing: an issue path
 * interleaves the `children` key with each index (`['children', 1]`), and a
 * `FilterPath` is the indexes alone (`[1]`).
 */
export function filterIndexes(path: IssuePath): FilterPath {
  return path.filter(
    (segment): segment is number => typeof segment === 'number',
  );
}
