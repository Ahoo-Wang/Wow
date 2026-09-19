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

import type { FocusEvent } from 'react';
import type { FieldOption } from '../model/index.js';
import type { FilterEditorController } from '../react/index.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { AddEntry } from './filter/AddEntry.js';
import { FilterActions } from './filter/FilterActions.js';
import { ConditionStrip, GroupBlock } from './filter/GroupBlock.js';
import { useViewMessages } from './MessagesProvider.js';

export interface FilterPanelProps {
  filter: FilterEditorController;
  /** Candidates for a `remote` value editor, by the key its kind declared. */
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
  /**
   * Whether the panel carries its own way out. An editor that is applied
   * from elsewhere — a dashboard's global band, a host's own button — keeps
   * the fields to add with and loses the pair that would run the query
   * twice.
   */
  submit?: boolean;
}

/**
 * The condition builder. A group is a framed block: its operator, its
 * conditions and room to add. A condition is a compact inline pill — field,
 * operator, value editor — and a group's conditions wrap in one strip, so a
 * filter of five conditions reads in a line rather than five rows. A
 * condition that holds a tree (an element match) is a block like a group,
 * since it is one. Simple mode shows the root's conditions as one strip;
 * anything the simple editor cannot show faithfully — a group anywhere — gets
 * the advanced one, where groups can be flipped between and/or, nested,
 * filled and removed.
 *
 * Nothing is applied until submit, which is the whole point of keeping a
 * draft apart from what ran: typing in here never re-queries.
 */
export function FilterPanel({
  filter,
  optionsFor,
  disabled,
  submit = true,
}: FilterPanelProps) {
  const advanced = filter.mode === 'advanced' || !filter.simple;
  const messages = useViewMessages();
  // A stored tree can exceed the depth or node budget; the validator reports
  // it as an error, and the panel must not recurse into it anyway.
  const overBudget = filter.issues.some(
    found =>
      found.severity === 'error' &&
      (found.code === 'filter.tree.too-deep' ||
        found.code === 'filter.tree.too-many-nodes'),
  );

  return (
    <section
      data-slot="filter-panel"
      aria-label={messages.label('label.filter.panel')}
      className="flex flex-col gap-3"
      // Auto-refresh holds while any control in here has focus. Focus events
      // bubble in React, so the root sees every input; a move from one
      // control to another inside the panel is not a leave and not an enter.
      onFocus={event => {
        if (crossesBoundary(event)) filter.focus();
      }}
      onBlur={event => {
        if (leavesEditor(event)) filter.blur();
      }}
    >
      <div className="flex flex-wrap items-center gap-2">
        <ToggleGroup
          // The effective mode, not the saved one: a simple config holding a
          // tree the simple editor cannot show opens in the advanced one, and
          // the toggle says so instead of contradicting the editor below.
          value={[advanced ? 'advanced' : 'simple']}
          onValueChange={value => {
            const next = value[0];
            if (next === 'simple' || next === 'advanced') filter.setMode(next);
          }}
          variant="outline"
          size="sm"
          aria-label={messages.label('label.filter.mode')}
        >
          <ToggleGroupItem value="simple">
            {messages.label('label.filter.simple')}
          </ToggleGroupItem>
          <ToggleGroupItem value="advanced">
            {messages.label('label.filter.advanced')}
          </ToggleGroupItem>
        </ToggleGroup>
      </div>

      {overBudget ? (
        <p
          data-slot="filter-too-large"
          className="text-muted-foreground text-sm"
        >
          {messages.label('label.filter.too-large')}
        </p>
      ) : advanced ? (
        <GroupBlock
          filter={filter}
          group={filter.tree}
          path={[]}
          disabled={disabled}
          optionsFor={optionsFor}
          isPending={filter.isPending}
        />
      ) : (
        filter.count > 0 && (
          <ConditionStrip
            filter={filter}
            group={filter.tree}
            path={[]}
            disabled={disabled}
            optionsFor={optionsFor}
            isPending={filter.isPending}
          />
        )
      )}

      {/* The way in and the way out, under what they act on: a field to add
          on the left, and on the right the pair that ends an edit. */}
      <div
        data-slot="filter-actions"
        className="flex flex-wrap items-center gap-2"
      >
        <AddEntry
          filter={filter}
          parent={[]}
          disabled={disabled}
          groups={advanced}
        />

        {submit && (
          <FilterActions
            filter={filter}
            disabled={disabled}
            overBudget={overBudget}
          />
        )}
      </div>
    </section>
  );
}

/**
 * Whether a focus event entered or left the element it was handled on, as
 * opposed to moving between two of its descendants. `relatedTarget` is the
 * other side of the move: on focus the element left, on blur the one gained.
 */
export function crossesBoundary(event: FocusEvent<HTMLElement>): boolean {
  const other = event.relatedTarget;
  return !(other instanceof Node && event.currentTarget.contains(other));
}

/**
 * Whether a blur means the user left the editor. A select, a date picker or
 * a menu of one of its controls renders in a portal outside the element, and
 * focus in there is still focus in the editor. Base UI marks the trigger of
 * an open popup, so the editor can tell one of its own is open; when it
 * closes, focus returns to the trigger and a later blur is judged afresh.
 */
export function leavesEditor(event: FocusEvent<HTMLElement>): boolean {
  if (!crossesBoundary(event)) return false;
  return event.currentTarget.querySelector('[data-popup-open]') === null;
}
