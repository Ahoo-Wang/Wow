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

import type { FieldGroupDefinition } from '../model/index.js';

/** One group of a picker: its label, or none for the fields outside any. */
export interface FieldGroup<T> {
  group: FieldGroupDefinition | undefined;
  items: T[];
}

/**
 * Fields as a picker lists them: the ones no group lists first, in the
 * field list's order, then each declared group in the definition's order,
 * each in its own `fields` order. A picker often lists a subset — the fields
 * not yet a condition, the ones a column can show — so a group none of the
 * items belong to is not shown. A field two groups list, or one group lists
 * twice, is kept where it was listed first; admission reports that as a
 * definition error, so it is never the picker's to guess.
 */
export function fieldGroups<T>(
  items: readonly T[],
  groups: readonly FieldGroupDefinition[],
  key: (item: T) => string,
): FieldGroup<T>[] {
  const groupOf = new Map<string, FieldGroupDefinition>();
  for (const group of groups)
    for (const field of group.fields)
      if (!groupOf.has(field)) groupOf.set(field, group);

  const grouped = new Map<string, T>();
  const ungrouped: T[] = [];
  for (const item of items) {
    const name = key(item);
    if (groupOf.has(name)) grouped.set(name, item);
    else ungrouped.push(item);
  }

  const listed: FieldGroup<T>[] = [];
  if (ungrouped.length > 0) listed.push({ group: undefined, items: ungrouped });
  for (const group of groups) {
    const own: T[] = [];
    for (const field of group.fields) {
      const item = grouped.get(field);
      if (item !== undefined && groupOf.get(field) === group) {
        own.push(item);
        grouped.delete(field);
      }
    }
    if (own.length > 0) listed.push({ group, items: own });
  }
  return listed;
}
