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

import type { FilterPath } from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { FieldPicker } from '../FieldMenu.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The one entry for adding to a group: a field, as a condition, or — where
 * the editor shows groups — a group to nest, by its operator.
 */
export function AddEntry({
  filter,
  parent,
  disabled,
  groups,
  label,
}: {
  filter: FilterTreeController;
  parent: FilterPath;
  disabled?: boolean;
  /** Whether groups may be added here: only the advanced editor shows them. */
  groups: boolean;
  /** The accessible name; the catalogue's own when a caller names none. */
  label?: string;
}) {
  const messages = useViewMessages();
  const name = label ?? messages.label('label.filter.add');
  const nestable = (['and', 'or', 'nor'] as const).map(op => ({
    key: `group:${op}`,
    label: messages.label(
      op === 'and'
        ? 'label.filter.all-of'
        : op === 'or'
          ? 'label.filter.any-of'
          : 'label.filter.none-of',
    ),
    pick: () => filter.addGroup(op, parent),
  }));

  return (
    <FieldPicker
      items={filter.fieldsFor(parent)}
      groups={filter.fieldGroups}
      extras={
        groups
          ? {
              label: messages.label('label.filter.nested-group'),
              entries: nestable,
            }
          : undefined
      }
      label={name}
      disabled={disabled}
      itemKey={field => field.name}
      itemLabel={field => field.label}
      onPick={field => filter.addLeaf(field.name, parent)}
    />
  );
}
