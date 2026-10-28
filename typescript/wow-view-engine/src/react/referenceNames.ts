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

import { useMemo } from 'react';
import { isReferenceFilterValue } from '../filter/index.js';
import type { FieldDefinition, FilterNode } from '../model/index.js';
import type { ReferenceLabelReader } from '../runtime/referenceLabels.js';
import { useReferenceLabels } from './useReferenceLabels.js';

/**
 * A filter whose reference values carry the names the engine knows:
 * an item whose stored label is only its id — a value set from a link, a
 * board's default written before its name was known — reads as the name
 * once the cache holds it, so a pill says 「客户 = 张三」 rather than the id.
 * A stored label that is a name stays as written; nothing else changes, and
 * a tree with nothing to rename is the same object.
 */
export function namedReferences<T extends FilterNode>(
  node: T,
  fields: readonly FieldDefinition[],
  labels: ReferenceLabelReader,
): T {
  const byName = new Map(fields.map(field => [field.name, field]));
  const walk = (one: FilterNode): FilterNode => {
    if ('children' in one) {
      const children = one.children.map(walk);
      return children.every((child, index) => child === one.children[index])
        ? one
        : { ...one, children };
    }
    const field = byName.get(one.field);
    const remote = field?.kind === 'reference' ? field.remote : undefined;
    const value = one.value;
    if (remote === undefined || !isReferenceFilterValue(value)) return one;
    const items = value.items.map(({ id, label }) => ({
      id,
      label: label === String(id) ? labels.text(remote, id) : label,
    }));
    return items.every((item, index) => item.label === value.items[index].label)
      ? one
      : { ...one, value: { items } };
  };
  return walk(node) as T;
}

/** `namedReferences` over one reader, a new function as names arrive. */
export type ReferenceNamer = <T extends FilterNode>(
  node: T,
  fields: readonly FieldDefinition[],
) => T;

/**
 * `namedReferences` bound to `reader` and followed: the function changes
 * whenever a name arrives, so a list described through it is described
 * again. Without a reader a tree is returned as it is.
 */
export function useReferenceNamer(
  reader: ReferenceLabelReader | null | undefined,
): ReferenceNamer {
  const version = useReferenceLabels(reader);
  return useMemo(() => {
    // Read only to make each version a new function.
    void version;
    return <T extends FilterNode>(
      node: T,
      fields: readonly FieldDefinition[],
    ): T => (reader ? namedReferences(node, fields, reader) : node);
  }, [reader, version]);
}
