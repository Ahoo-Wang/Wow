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

import { ChevronDownIcon, LockIcon } from 'lucide-react';
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
import { summaryText } from '../summary.js';
import { EditorSlot, WrappingBadge } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { TermTip } from './TermTip.js';

/**
 * The last row of the tray (D71): the range the analysis runs over. It is
 * the record view's condition panel, unchanged, with the one simple/advanced
 * switch there is at the row's end — the grammar of the condition tree,
 * which governs the range and every metric's own conditions alike (D20: no
 * simple/advanced tray, only the tree's `filterMode`).
 *
 * It comes last because it depends on nothing above it: it narrows the
 * outermost records, whatever is expanded, and the question is read top to
 * bottom as what is counted, then over which records.
 *
 * **What the page set is drawn as it is**: the host's own conditions
 * (`scoped`) and the readings the source applies unasked (`implied`) stand
 * before the panel as locked badges saying whose they are — 「由页面设定」,
 * 「缺省口径」 — with no way to take them out, because they are nobody's
 * here to take out. Without them the row said the analysis ran over all
 * records while the page had narrowed it.
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
  const display = useSurfaceDisplay();
  const mode = filterModeLabel(filter, messages);
  const title = messages.label('label.analysis.slot.range');
  const locked = [
    ...filter.scoped.map(item => ({
      key: `scoped:${item.path.join('.')}`,
      text: summaryText(item, messages, display),
      whose: messages.label('label.applied.scoped'),
      mark: 'scoped',
    })),
    ...filter.implied.map(item => ({
      key: `implied:${item.field ?? ''}`,
      text: summaryText(item, messages, display),
      whose: messages.label('label.applied.implied'),
      mark: 'implied',
    })),
  ];
  return (
    <EditorSlot
      name="range"
      title={title}
      tip={
        <TermTip
          label={messages.label('label.analysis.tip-of', { term: title })}
          tip={messages.label('label.analysis.tip.range')}
        />
      }
      end={
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
      {locked.length > 0 && (
        <ul
          data-slot="range-locked"
          className="fve:flex fve:flex-wrap fve:gap-1"
        >
          {locked.map(item => (
            <li key={item.key}>
              <WrappingBadge variant="outline" data-locked={item.mark}>
                <LockIcon data-icon="inline-start" aria-hidden />
                {item.text}
                <span className="fve:text-muted-foreground">
                  · {item.whose}
                </span>
              </WrappingBadge>
            </li>
          ))}
        </ul>
      )}
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
