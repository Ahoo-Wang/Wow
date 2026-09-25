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
  DataViewDefinition,
  FieldDefinition,
  FilterTree,
  Issue,
  IssuePath,
} from '../model/index.js';
import { isFilterGroup, issue } from '../filter/index.js';
import { isForbiddenFailure, type SourceFailure } from './sourceReason.js';

/**
 * The Issue a failed query shows, from what the source said.
 *
 * A source that refused the reader rather than the query — HTTP 403, or one
 * of Wow's `IllegalAccess*` codes (`isForbiddenFailure`) — is
 * `runtime.query.forbidden`, with `reason` and the service's `errorCode`:
 * the permission state, which a retry does not change.
 *
 * Without a violation it is `runtime.query.failed` with the source's reason.
 * With one — a Wow service naming the rule the query broke — the code is
 * `runtime.query.failed.<code>` (the violation's code in lower case), which
 * the catalogue words per rule; a code it has no wording for falls back
 * along the dots to `runtime.query.failed` and the service's own words, so
 * a rule a newer service adds still reads. The params say:
 *
 * - `reason`: the service's message, always;
 * - `code` and `path`: the violation as the service gave it;
 * - `field`: the field it is about by its label, when the definition
 *   declares one at that path (an element field as its array's label and
 *   its own), else the path itself;
 * - `name`: that field's name in the definition, when it declares one.
 *
 * The Issue's `path` is the condition of the view's own filter the rule is
 * about (`['children', 1]`), when one names that field — an element-match
 * condition when the field is one of its array's elements — so the editor
 * can mark that row; otherwise `[]`.
 */
export function queryFailureIssue(
  failure: SourceFailure,
  definition: DataViewDefinition,
  filter: FilterTree | undefined,
): Issue {
  const { reason, violation } = failure;
  // Refused the reader, not the query: the permission state, which asking
  // again does not change (`runtime.query.forbidden`).
  if (isForbiddenFailure(failure))
    return issue('runtime.query.forbidden', [], {
      reason,
      ...(failure.errorCode ? { errorCode: failure.errorCode } : {}),
    });
  if (!violation) return issue('runtime.query.failed', [], { reason });
  const found = fieldAt(definition.fields, violation.path);
  const params: Record<string, string> = {
    reason,
    code: violation.code,
    path: violation.path,
    field: found?.label ?? violation.path,
  };
  if (found) params.name = found.name;
  const at =
    found && filter ? conditionOf(filter, found.name, found.root) : null;
  return issue(
    `runtime.query.failed.${violation.code.toLowerCase()}`,
    at ?? [],
    params,
  );
}

interface FoundField {
  /** The name a condition on it holds: the field's, or `array.element`. */
  name: string;
  label: string;
  /** For an element field, the array's name: an element-match holds it. */
  root?: string;
}

/**
 * The declared field at a Wow logical path: a root field by its name, or an
 * element of an array of objects as `array.element`.
 */
function fieldAt(
  fields: readonly FieldDefinition[],
  path: string,
): FoundField | null {
  if (!path) return null;
  const root = fields.find(field => field.name === path);
  if (root) return { name: root.name, label: root.label };
  for (const field of fields) {
    if (!field.elements || !path.startsWith(`${field.name}.`)) continue;
    const rest = path.slice(field.name.length + 1);
    const element = field.elements.find(entry => entry.name === rest);
    if (element)
      return {
        name: path,
        label: `${field.label} · ${element.label}`,
        root: field.name,
      };
  }
  return null;
}

/**
 * The first condition of `tree` on the field, depth first, as an issue path
 * (`['children', 0, 'children', 2]`); an element field's condition is the
 * element-match on its array.
 */
function conditionOf(
  tree: FilterTree,
  name: string,
  root: string | undefined,
): IssuePath | null {
  const walk = (node: unknown, at: IssuePath): IssuePath | null => {
    if (isFilterGroup(node)) {
      for (const [index, child] of node.children.entries()) {
        const found = walk(child, [...at, 'children', index]);
        if (found) return found;
      }
      return null;
    }
    const field = (node as { field?: unknown } | null)?.field;
    return field === name || (root !== undefined && field === root) ? at : null;
  };
  return walk(tree, []);
}

/** Whether a query's Issue is the permission state; see `queryFailureIssue`. */
export function isForbiddenQuery(found: Issue | null | undefined): boolean {
  return found?.code === 'runtime.query.forbidden';
}
