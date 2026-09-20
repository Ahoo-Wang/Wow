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

import { ChevronDownIcon } from 'lucide-react';
import type { FilterGroupOperator } from '../../model/index.js';
import type { FilterPath } from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { Button } from '../components/button.js';
import { ButtonGroup } from '../components/button-group.js';
import {
  DropdownMenu,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { FieldChecklist } from './FieldChecklist.js';
import { GROUP_OPERATOR_LABEL } from './groupOperators.js';

/**
 * The way into a group: its fields, and — where the editor shows groups — a
 * group to nest.
 *
 * The two are separate controls because they are separate kinds of thing.
 * Nesting a group was the last entry of the field list while the list was a
 * menu of one-shot picks; a list the user ticks several fields in has no
 * place for an entry that is not a field, and an operator is not a field.
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

  const picker = (
    <FieldChecklist
      filter={filter}
      parent={parent}
      disabled={disabled}
      label={name}
    />
  );

  if (!groups) return picker;

  return (
    <ButtonGroup>
      {picker}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="outline"
              size="sm"
              disabled={disabled}
              aria-label={messages.label('label.filter.add-group')}
            />
          }
        >
          <ChevronDownIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {(['and', 'or', 'nor'] as const).map(op => (
            <DropdownMenuItem
              key={op}
              onClick={() => filter.addGroup(op, parent)}
            >
              {/* The code first, then the sentence: a user who already thinks
                  in AND/OR finds it at a glance, and one who does not reads
                  what it will mean for the conditions under it. */}
              <span className="text-muted-foreground font-mono text-xs">
                {operatorCode(op)}
              </span>
              {/* The space is load-bearing: without it the two run together
                  in the accessible name and a screen reader reads
                  "ORAny condition". The flex gap only separates them on
                  screen. */}{' '}
              {messages.label(GROUP_OPERATOR_LABEL[op])}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </ButtonGroup>
  );
}

function operatorCode(op: FilterGroupOperator): string {
  return op.toUpperCase();
}
