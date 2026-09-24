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
import type { FieldOption } from '../../model/index.js';
import type { FilterEditorController } from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { FilterModes, filterModeLabel } from '../filter/FilterModes.js';
import { FilterPanel } from '../FilterPanel.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DropdownMenuContent } from '../popups.js';
import { EditorSlot } from '../variants.js';

/**
 * The first slot of the tray: the range the analysis runs over. It is the
 * record view's condition panel, unchanged, under a heading that also holds
 * the one simple/advanced switch there is — the grammar of the condition
 * tree, which governs the range and every metric's own conditions alike
 * (D20: no simple/advanced tray, only the tree's `filterMode`).
 */
export function RangeSlot({
  filter,
  optionsFor,
  disabled,
}: {
  filter: FilterEditorController;
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const mode = filterModeLabel(filter, messages);
  return (
    <EditorSlot
      name="range"
      title={messages.label('label.analysis.slot.range')}
      aside={
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="xs"
                disabled={disabled}
                data-slot="conditions-mode"
              />
            }
          >
            {messages.label('label.analysis.conditions-mode', { mode })}
            <ChevronDownIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <FilterModes filter={filter} />
          </DropdownMenuContent>
        </DropdownMenu>
      }
    >
      <FilterPanel
        filter={filter}
        optionsFor={optionsFor}
        disabled={disabled}
        modes={false}
        submit={false}
      />
    </EditorSlot>
  );
}
