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
  FilterTree,
  IssuePath,
} from '../model/index.js';
import {
  isBlankLeafValue,
  type FieldKind,
  type FieldKindRegistry,
} from './fieldKind.js';
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
 *
 * The same holds for a leaf the kind cannot read: a saved config arrives from
 * a store and may hold a value its field no longer admits, and a summary bar
 * that threw would take the whole view down with it rather than showing which
 * condition needs fixing.
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
    // A condition that was never finished did not reach the query, so it is
    // not one of the conditions in force.
    if (
      field &&
      kind &&
      isBlankLeafValue(node.value, node.operator, field, kind)
    )
      continue;
    const described =
      field && kind ? describeLeaf(kind, node, field) : undefined;
    if (!field || described === undefined) {
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
      text: described,
      unresolved: false,
    });
  }

  return items;
}

/**
 * A kind describes an admitted leaf; this one may not have been admitted.
 * A kind is an extension point, so what it does with a value it cannot read is
 * not this layer's to predict — only to survive.
 */
function describeLeaf(
  kind: FieldKind,
  leaf: FilterLeaf,
  field: FieldDefinition,
): string | undefined {
  try {
    return kind.describe({ leaf, field });
  } catch {
    return undefined;
  }
}
