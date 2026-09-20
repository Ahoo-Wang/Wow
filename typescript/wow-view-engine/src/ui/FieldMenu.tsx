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

import type { ReactNode } from 'react';
import type { FieldGroupDefinition } from '../model/index.js';
import { fieldGroups } from '../filter/index.js';
import {
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from './components/dropdown-menu.js';

/**
 * A picker's entries by group: the fields outside any group first, then
 * each group under its label. The definition says which group a field is
 * in; this only lays them out, the same way in every picker that lists
 * fields, so a user finds a field in the same place whether adding a
 * condition, a column or a grouping.
 *
 * A searchable combobox of one-shot picks lived here too, until the
 * condition editor's field list became a set of ticks a user works through
 * without the list closing (`filter/FieldChecklist.tsx`). Nothing picks a
 * field one at a time any more, so it is gone rather than kept for a caller
 * that does not exist.
 */
export function GroupedMenu<T>({
  items,
  groups,
  itemKey,
  render,
}: {
  items: readonly T[];
  groups: readonly FieldGroupDefinition[];
  itemKey(item: T): string;
  render(item: T): ReactNode;
}) {
  return (
    <>
      {fieldGroups(items, groups, itemKey).map((entry, index) => (
        <DropdownMenuGroup key={entry.group?.id ?? ''}>
          {index > 0 && <DropdownMenuSeparator />}
          {entry.group !== undefined && (
            <DropdownMenuLabel>{entry.group.label}</DropdownMenuLabel>
          )}
          {entry.items.map(render)}
        </DropdownMenuGroup>
      ))}
    </>
  );
}
