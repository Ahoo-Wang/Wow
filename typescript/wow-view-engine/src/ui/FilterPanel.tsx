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

import type { FocusEvent, KeyboardEvent } from 'react';
import type { FieldOption } from '../model/index.js';
import type { FilterEditorController } from '../react/index.js';
import { LineAlert } from './alerts.js';
import { AlertTitle } from './components/alert.js';
import { AddEntry } from './filter/AddEntry.js';
import { isPlainEnter } from './filter/enter.js';
import { FilterModeToggle } from './filter/FilterModes.js';
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
  /**
   * Whether the panel carries its own control over the editing mode.
   *
   * False where the surface around it has a better place for one — a
   * workbench whose only editor is this panel puts it on the fold's toggle
   * in the title bar. True, the default, everywhere else: a workbench whose
   * editor is this panel *and something else* cannot fold both under the
   * word "Filter", and a mode that exists but cannot be reached is a
   * capability the user has lost rather than a tidier screen.
   */
  modes?: boolean;
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
 * filled and removed. Where the surface around the panel has a place for the
 * mode — the fold's toggle in a workbench whose only editor is this panel —
 * it takes it (`modes={false}`), because a control for *how* to edit sitting
 * among the conditions was a line of chrome over every filter ever written.
 * Where it has not, the panel keeps its own: a mode that exists but cannot be
 * reached is a capability lost, not a tidier screen.
 *
 * Nothing is applied until submit, which is the whole point of keeping a
 * draft apart from what ran: typing in here never re-queries. Enter in a
 * value editor is the one shortcut to that submit — the same command the
 * Apply button runs, refused under the same conditions.
 */
export function FilterPanel({
  filter,
  optionsFor,
  disabled,
  submit = true,
  modes = true,
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

  // Simple mode with nothing in it draws nothing at all — not an empty
  // scroll port, which is a block promising content it does not have.
  const tree = advanced ? (
    <GroupBlock
      filter={filter}
      group={filter.tree}
      path={[]}
      disabled={disabled}
      optionsFor={optionsFor}
      isPending={filter.isPending}
    />
  ) : filter.count > 0 ? (
    <ConditionStrip
      filter={filter}
      group={filter.tree}
      path={[]}
      disabled={disabled}
      optionsFor={optionsFor}
      isPending={filter.isPending}
      negatable
    />
  ) : null;

  return (
    <section
      data-slot="filter-panel"
      aria-label={messages.label('label.filter.panel')}
      // Announced rather than only implemented: a keyboard shortcut nobody
      // can discover is a shortcut for whoever wrote it.
      aria-keyshortcuts={submit ? 'Enter' : undefined}
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
      // Enter in a value editor is the same decision the Apply button is, so
      // it runs the same command under the same conditions — never while
      // apply is refused, and never when the keystroke was already somebody
      // else's (see `appliesOnEnter`).
      onKeyDown={event => {
        if (!submit || disabled || filter.blocked > 0) return;
        if (!appliesOnEnter(event)) return;
        // The panel has taken the keystroke; nothing above it — a host's own
        // form, most of all — should act on it a second time.
        event.preventDefault();
        filter.submit();
      }}
    >
      {modes && (
        <div className="flex flex-wrap items-center gap-2">
          <FilterModeToggle filter={filter} disabled={disabled} />
        </div>
      )}

      {overBudget ? (
        // Why the editor is refusing to draw, which is a callout rather than
        // a caption: `info`, because nothing here is wrong with the tree
        // that this screen can fix — Clear is still the way out.
        <LineAlert tone="info" data-slot="filter-too-large">
          <AlertTitle>{messages.label('label.filter.too-large')}</AlertTitle>
        </LineAlert>
      ) : (
        tree !== null && (
          // The conditions scroll; the tray does not grow without end.
          //
          // A filter of seven conditions in advanced mode drew a 758px tray,
          // which on an 800×900 screen put the result toolbar 74px below the
          // fold: the rows the conditions are about were not on the screen
          // the conditions were being written on. The tray is what the user
          // reads to *change* the query and the result is what they read to
          // *see* it, so the one that is a means gives way to the one that
          // is the end — capped here rather than folded, because folding
          // hides conditions that are in force, and "which conditions am I
          // looking at" is the question this block exists to answer.
          //
          // 40vh is the largest cap that leaves the other 60% to the title
          // bar, the applied conditions and the first rows of the result:
          // measured at 800×900 it holds the whole tray, its actions row
          // included, inside 424px and puts the toolbar at 640px.
          <div data-slot="filter-tree" className="max-h-[40vh] overflow-y-auto">
            {tree}
          </div>
        )
      )}

      {/* The way in and the way out, under what they act on: a field to add
          on the left, and on the right the pair that ends an edit (D12 Ⅱ).

          The field on the left is simple mode's. Advanced mode draws the
          root as a group block, and a group block already carries its own
          way in — so a second pair here made four entries for one group
          ("Add condition / Add a group / Add / Add a group"), two of
          which did exactly what the other two did. One group, one set: the
          root's lives in the root's frame, directly above this row, and
          what is left here is the way out. */}
      {(!advanced || submit) && (
        <div
          data-slot="filter-actions"
          className="flex flex-wrap items-center gap-2"
        >
          {!advanced && (
            <AddEntry
              filter={filter}
              parent={[]}
              disabled={disabled}
              groups={false}
            />
          )}

          {submit && (
            <FilterActions
              filter={filter}
              disabled={disabled}
              overBudget={overBudget}
            />
          )}
        </div>
      )}
    </section>
  );
}

/**
 * Whether `node` is the element the event was handled on, or something
 * inside it. Every boundary question below is this one asked of a different
 * node, because a React event says nothing about where in the DOM it
 * started: it bubbles through a portal just as it bubbles through a child.
 */
function within(root: HTMLElement, node: EventTarget | null): boolean {
  return node instanceof Node && root.contains(node);
}

/**
 * Whether a focus event entered or left the element it was handled on, as
 * opposed to moving between two of its descendants. `relatedTarget` is the
 * other side of the move: on focus the element left, on blur the one gained.
 */
export function crossesBoundary(event: FocusEvent<HTMLElement>): boolean {
  return !within(event.currentTarget, event.relatedTarget);
}

/**
 * Whether this Enter means "apply".
 *
 * The panel listens at its root so every value editor gets the shortcut
 * without knowing about it, and the cost of listening that high is that
 * keystrokes arrive which were never meant for it. Four of them are not:
 *
 * - one an IME is using to accept the characters being composed, and one
 *   held with a modifier, which is some other shortcut, possibly the host
 *   page's. These two are `isPlainEnter`, which the value editors that
 *   answer Enter themselves ask as well, so that a modified Enter is left
 *   to the host wherever in the editor it was pressed;
 * - one inside a popup of one of the panel's own controls — a select's list,
 *   a date picker, a combobox. It is portalled outside the panel, yet a
 *   React event still bubbles here from it, and while it is open Enter is
 *   its answer to give. Base UI marks the open trigger, which is the same
 *   mark `leavesEditor` reads;
 * - one on a control that acts on Enter itself. Enter on "Add" opens the
 *   field picker and Enter on Clear clears: one keystroke, one meaning, and
 *   the panel does not get to add a second.
 */
function appliesOnEnter(event: KeyboardEvent<HTMLElement>): boolean {
  if (!isPlainEnter(event)) return false;
  if (!within(event.currentTarget, event.target)) return false;
  if (event.currentTarget.querySelector('[data-popup-open]') !== null)
    return false;
  return !actsOnEnter(event.target);
}

/** Controls whose own answer to Enter the panel must not talk over. */
const ENTER_IS_TAKEN =
  'button, a[href], summary, textarea, [role="button"], [role="link"]';

function actsOnEnter(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(ENTER_IS_TAKEN) !== null;
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
