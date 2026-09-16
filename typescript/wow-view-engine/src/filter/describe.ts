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

import type { FieldDefinition, FilterTree, IssuePath } from '../model/index.js';
import type { FieldKindRegistry } from './fieldKind.js';
import { isFilterLeaf, walkFilter } from './tree.js';

/** One applied condition, for the summary bar above a result. */
export interface FilterSummaryItem {
  /** Location of the leaf, so the bar can remove or focus it. */
  path: IssuePath;
  field: string;
  /** Field label, or the raw name when the field is gone. */
  label: string;
  /** Human-readable condition supplied by the kind. */
  text: string;
  /** The field or its kind is no longer available. */
  unresolved: boolean;
}

/**
 * Summarises the applied conditions. It reports a leaf whose field or kind has
 * disappeared instead of hiding it, so a view that needs fixing says so.
 */
export function describeFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  const byName = new Map(fields.map(field => [field.name, field]));
  const items: FilterSummaryItem[] = [];

  for (const { node, path } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    const field = byName.get(node.field);
    const kind = field ? kinds.get(field.kind) : undefined;
    if (!field || !kind) {
      items.push({
        path,
        field: node.field,
        label: field?.label ?? node.field,
        text: `${field?.label ?? node.field} ${node.operator}`,
        unresolved: true,
      });
      continue;
    }
    items.push({
      path,
      field: field.name,
      label: field.label,
      text: kind.describe({ leaf: node, field }),
      unresolved: false,
    });
  }

  return items;
}
