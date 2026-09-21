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

import type { FilterMode } from '../../model/index.js';
import type { FilterEditorController } from '../../react/index.js';
import {
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
} from '../components/dropdown-menu.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The mode the editor is actually in, and whether the other one is reachable.
 *
 * A config saved as simple may hold a group, which the simple editor cannot
 * draw faithfully; the editor opens advanced regardless. Every control over
 * the mode reads this rather than `filter.mode`, so none of them ever claims
 * a mode the editor below is not in.
 */
function effective(filter: FilterEditorController): {
  mode: FilterMode;
  locked: boolean;
} {
  const locked = !filter.simple;
  return {
    mode: filter.mode === 'advanced' || locked ? 'advanced' : 'simple',
    locked,
  };
}

/**
 * Whether the condition editor shows one strip of conditions or the whole
 * tree, as the two items of the editor's own menu.
 *
 * The mode is a way of *editing* rather than part of the filter, which is
 * why it left the panel: the panel is the conditions, and everything about
 * how they are edited now hangs off the one toggle in the title bar.
 *
 * What it shows is the mode in force, not the mode stored. A config saved as
 * simple may hold a group, which the simple editor cannot draw faithfully,
 * and the editor opens advanced regardless; the menu says so — and says why
 * simple is not on offer — rather than claiming a mode the editor below is
 * not in.
 */
export function FilterModes({ filter }: { filter: FilterEditorController }) {
  const messages = useViewMessages();
  const { mode, locked } = effective(filter);

  return (
    <DropdownMenuRadioGroup
      value={mode}
      // Base UI types a radio group's value as `any`. Naming the
      // parameter's type keeps that `any` out of this file; a runtime guard
      // would be a branch nothing can reach, because the only two values in
      // the group are the two items below.
      onValueChange={(next: FilterMode) => filter.setMode(next)}
    >
      <DropdownMenuLabel>
        {messages.label('label.filter.mode')}
      </DropdownMenuLabel>
      <DropdownMenuRadioItem
        value="simple"
        closeOnClick
        disabled={locked}
        // The reason travels with the item rather than sitting under the
        // menu as a line of prose: a screen reader announces a disabled
        // option and then has nowhere to go looking for why.
        aria-description={
          locked ? messages.label('config.filterMode.not-simple') : undefined
        }
      >
        {messages.label('label.filter.simple')}
      </DropdownMenuRadioItem>
      <DropdownMenuRadioItem value="advanced" closeOnClick>
        {messages.label('label.filter.advanced')}
      </DropdownMenuRadioItem>
    </DropdownMenuRadioGroup>
  );
}

/**
 * The same choice, as a control inside the panel.
 *
 * It is what a surface falls back on when it has nowhere better to put the
 * mode. A workbench whose only editor *is* this panel does have somewhere
 * better — the fold's toggle in the title bar — but one whose editor is this
 * panel and something else besides cannot fold them both under the word
 * "Filter", so the choice stays here rather than going missing. A mode that
 * exists but cannot be reached is a capability the user has lost.
 *
 * Two short options, so a segmented control rather than a row of separate
 * buttons — `spacing={0}`, and the house rule in `docs/design/ui`.
 */
export function FilterModeToggle({
  filter,
  disabled,
}: {
  filter: FilterEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const { mode, locked } = effective(filter);

  return (
    <ToggleGroup
      value={[mode]}
      onValueChange={(value: string[]) => {
        const next = value[0];
        if (next === 'simple' || next === 'advanced') filter.setMode(next);
      }}
      variant="outline"
      size="sm"
      spacing={0}
      disabled={disabled}
      aria-label={messages.label('label.filter.mode')}
    >
      <ToggleGroupItem
        value="simple"
        disabled={locked}
        // The reason travels with the control rather than sitting beside it
        // as a line of prose: a screen reader announces a disabled option
        // and then has nowhere to go looking for why.
        aria-description={
          locked ? messages.label('config.filterMode.not-simple') : undefined
        }
      >
        {messages.label('label.filter.simple')}
      </ToggleGroupItem>
      <ToggleGroupItem value="advanced">
        {messages.label('label.filter.advanced')}
      </ToggleGroupItem>
    </ToggleGroup>
  );
}

/** The mode in force, for the editor toggle's name. */
export function filterModeLabel(
  filter: FilterEditorController,
  messages: ReturnType<typeof useViewMessages>,
): string {
  return messages.label(
    effective(filter).mode === 'advanced'
      ? 'label.filter.advanced'
      : 'label.filter.simple',
  );
}
