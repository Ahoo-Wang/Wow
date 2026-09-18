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
 * Fields as a picker lists them: the ones outside any group first, then each
 * declared group in the definition's order, each keeping the field list's
 * order inside. A group with no field is not shown, and a field naming a
 * group the definition does not declare is listed as ungrouped — admission
 * reports that as a definition error, so it is never the picker's to guess.
 */
export function fieldGroups<T extends { group?: string }>(
  items: readonly T[],
  groups: readonly FieldGroupDefinition[] = [],
): FieldGroup<T>[] {
  const declared = new Map(groups.map(group => [group.id, group]));
  const members = new Map<string, T[]>(groups.map(group => [group.id, []]));
  const ungrouped: T[] = [];
  for (const item of items) {
    const group =
      item.group === undefined ? undefined : declared.get(item.group);
    if (group) members.get(group.id)?.push(item);
    else ungrouped.push(item);
  }
  const listed: FieldGroup<T>[] = [];
  if (ungrouped.length > 0) listed.push({ group: undefined, items: ungrouped });
  for (const group of groups) {
    const own = members.get(group.id) ?? [];
    if (own.length > 0) listed.push({ group, items: own });
  }
  return listed;
}
