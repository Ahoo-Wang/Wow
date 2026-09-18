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
import { PlusIcon } from 'lucide-react';
import type { FieldGroupDefinition } from '../model/index.js';
import { fieldGroups } from '../filter/index.js';
import { Button } from './components/button.js';
import {
  Combobox,
  ComboboxCollection,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxList,
  ComboboxTrigger,
} from './components/combobox.js';
import {
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from './components/dropdown-menu.js';
import { ComboboxContent } from './popups.js';
import { useViewMessages } from './MessagesProvider.js';

/** One line of the picker: what it says, and what picking it does. */
export interface PickerEntry {
  key: string;
  label: string;
  pick(): void;
}

/** A picker's groups as the combobox takes them: a heading and its items. */
interface PickerGroup {
  value: string;
  label: string | undefined;
  items: PickerEntry[];
}

/**
 * Something to add, picked by name. A button opens a list with a search box
 * above it: the fields under their groups, and after them whatever else the
 * caller offers — a group to nest, say — under a heading of its own. Typing
 * narrows the whole list. Picking adds and closes; nothing is "selected"
 * afterwards, so the same entry can be picked again where that is allowed.
 * A menu did this until fields had groups; a list long enough to need
 * groups is long enough to need a search.
 */
export function FieldPicker<T extends { group?: string }>({
  items,
  groups,
  extras,
  label,
  disabled,
  itemKey,
  itemLabel,
  onPick,
}: {
  items: readonly T[];
  groups: readonly FieldGroupDefinition[];
  /** A trailing section, such as the groups a condition can be nested in. */
  extras?: { label: string; entries: PickerEntry[] };
  /** The button's text, which is also its accessible name. */
  label: string;
  disabled?: boolean;
  itemKey(item: T): string;
  itemLabel(item: T): string;
  onPick(item: T): void;
}) {
  const messages = useViewMessages();
  const grouped: PickerGroup[] = fieldGroups(items, groups).map(entry => ({
    value: entry.group?.id ?? '',
    label: entry.group?.label,
    items: entry.items.map(item => ({
      key: itemKey(item),
      label: itemLabel(item),
      pick: () => onPick(item),
    })),
  }));
  if (extras && extras.entries.length > 0)
    grouped.push({
      value: '\u0000extras',
      label: extras.label,
      items: extras.entries,
    });

  return (
    <Combobox
      items={grouped}
      value={null}
      onValueChange={(picked: PickerEntry | null) => picked?.pick()}
      itemToStringLabel={(entry: PickerEntry) => entry.label}
      itemToStringValue={(entry: PickerEntry) => entry.key}
    >
      <ComboboxTrigger
        aria-label={label}
        render={<Button variant="outline" size="sm" disabled={disabled} />}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </ComboboxTrigger>
      <ComboboxContent>
        <ComboboxInput
          placeholder={messages.label('label.field.search')}
          showTrigger={false}
          aria-label={messages.label('label.field.search')}
        />
        <ComboboxEmpty>{messages.label('label.field.none')}</ComboboxEmpty>
        <ComboboxList>
          {(group: PickerGroup) => (
            <ComboboxGroup key={group.value} items={group.items}>
              {group.label !== undefined && (
                <ComboboxLabel>{group.label}</ComboboxLabel>
              )}
              <ComboboxCollection>
                {(entry: PickerEntry) => (
                  <ComboboxItem key={entry.key} value={entry}>
                    {entry.label}
                  </ComboboxItem>
                )}
              </ComboboxCollection>
            </ComboboxGroup>
          )}
        </ComboboxList>
      </ComboboxContent>
    </Combobox>
  );
}

/**
 * A picker's entries by group: the fields outside any group first, then
 * each group under its label. The definition says which group a field is
 * in; this only lays them out, the same way in every picker that lists
 * fields, so a user finds a field in the same place whether adding a
 * condition, a column or a grouping.
 */
export function GroupedMenu<T extends { group?: string }>({
  items,
  groups,
  render,
}: {
  items: readonly T[];
  groups: readonly FieldGroupDefinition[];
  render(item: T): ReactNode;
}) {
  return (
    <>
      {fieldGroups(items, groups).map((entry, index) => (
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
