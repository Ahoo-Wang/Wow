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

import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type RefObject,
} from 'react';
import type { DashboardController } from '../../react/index.js';
import type { EditCommand, EditStep } from '../../runtime/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import type { MessageKey } from '../messages.js';

/** What a step is, in the words the edit bar says it in, by its command. */
const PHRASES: Readonly<
  Record<EditCommand, readonly [MessageKey, 'panel' | 'filter' | null]>
> = {
  addPanel: ['label.history.add-panel', 'panel'],
  duplicatePanel: ['label.history.add-panel', 'panel'],
  removePanel: ['label.history.remove-panel', 'panel'],
  place: ['label.history.move-panel', 'panel'],
  reorderPanel: ['label.history.move-panel', 'panel'],
  movePanelToTab: ['label.history.move-panel', 'panel'],
  renamePanel: ['label.history.change-panel', 'panel'],
  replacePanelView: ['label.history.change-panel', 'panel'],
  editPanelContent: ['label.history.change-panel', 'panel'],
  setPresentation: ['label.history.change-panel', 'panel'],
  referToSaved: ['label.history.change-panel', 'panel'],
  setPanelClick: ['label.history.change-panel', 'panel'],
  addTab: ['label.history.add-tab', null],
  removeTab: ['label.history.remove-tab', null],
  renameTab: ['label.history.change-tabs', null],
  moveTab: ['label.history.change-tabs', null],
  addFilter: ['label.history.add-filter', 'filter'],
  removeFilter: ['label.history.remove-filter', 'filter'],
  renameFilter: ['label.history.change-filter', 'filter'],
  retypeFilter: ['label.history.change-filter', 'filter'],
  setFilterDefault: ['label.history.change-filter', 'filter'],
  setFilterRequired: ['label.history.change-filter', 'filter'],
  setFilterMultiple: ['label.history.change-filter', 'filter'],
  setFilterOneDay: ['label.history.change-filter', 'filter'],
  setFilterOptions: ['label.history.change-filter', 'filter'],
  moveFilter: ['label.history.change-filter', 'filter'],
  bindPanel: ['label.history.change-filter', 'filter'],
  unbindPanels: ['label.history.change-filter', 'filter'],
  setTimeGrouping: ['label.history.change-grouping', null],
  removeFixedScope: ['label.history.remove-fixed', null],
  setWidth: ['label.history.change-width', null],
};

/** 撤销 and 重做 as the edit bar draws them. */
export interface BoardHistory {
  /** The button's name: 「撤销」, or 「撤销移除「北区订单」」 when there is a step. */
  undoLabel: string;
  redoLabel: string;
  canUndo: boolean;
  canRedo: boolean;
  undo(): void;
  redo(): void;
  /**
   * ⌘Z／Ctrl+Z, and ⇧⌘Z／Ctrl+Shift+Z／Ctrl+Y, on the board — never inside
   * a field, whose own undo the keys are, nor inside a dialog or a popup
   * the board opened (those are not in `board`, whatever React's tree says).
   */
  onKeyDown(event: KeyboardEvent<HTMLElement>): void;
  /**
   * Puts the keyboard on 「撤销」 once the board has drawn what a removal
   * left — where a panel that went is brought back from.
   */
  land(): void;
}

export interface BoardHistoryInput {
  dashboard: DashboardController;
  /** Every panel's name by id, as the board names them now. */
  names: ReadonlyMap<string, string>;
  messages: MessageFormatters;
  /** Whether the board is being built: undo is building's alone. */
  editing: boolean;
  /** Says a line once, in the board's live region. */
  say(line: string): void;
  /** The board: where the keys are listened for. */
  board: RefObject<HTMLElement | null>;
  undoRef: RefObject<HTMLButtonElement | null>;
  redoRef: RefObject<HTMLButtonElement | null>;
  /** The edit bar's name: where the keyboard goes when neither button can take it. */
  landing: RefObject<HTMLElement | null>;
}

/**
 * The board's undo and redo (`DashboardEditing.undo`, `redo`) as a screen
 * offers them: named after the step they would take (a panel that went is
 * still named, since the names seen while the board was built are kept),
 * said aloud when taken, and the keyboard never left on nothing.
 *
 * The keyboard: a press that disables its own button moves to the other
 * one, as a move button's does at the end of a list (`listFocus.ts`); a
 * step taken by key that took the focused control with it — a panel added
 * then taken back — lands on 「撤销」, or 「重做」, or the edit bar.
 */
export function useBoardHistory({
  dashboard,
  names,
  messages,
  editing,
  say,
  board,
  undoRef,
  redoRef,
  landing,
}: BoardHistoryInput): BoardHistory {
  // Names as last seen, never forgotten while the board is open: the step
  // that removed 「北区订单」 is named after a panel no longer on it. Kept as
  // state and caught up during render, so the names drawn are this render's.
  const [seen, setSeen] = useState<Seen>(NOTHING_SEEN);
  const known = caughtUp(seen, names, dashboard.filterFields);
  if (known !== seen) setSeen(known);
  const what = (step: EditStep | null): string => {
    if (!step) return messages.label('label.history.change-board');
    const [key, about] = PHRASES[step.command];
    if (about === null) return messages.label(key);
    const title =
      step.subject === null
        ? undefined
        : known[about === 'panel' ? 'panels' : 'filters'].get(step.subject);
    return title === undefined
      ? messages.label('label.history.change-board')
      : messages.label(key, { title });
  };

  // What the next render should do with the keyboard, set by a press.
  const pending = useRef<'undo' | 'redo' | 'lost' | 'land' | null>(null);
  useEffect(() => {
    const next = pending.current;
    if (!next) return;
    pending.current = null;
    const usable = (node: HTMLElement | null): node is HTMLElement =>
      node !== null && !node.hasAttribute('disabled');
    const firstOf = (...nodes: (HTMLElement | null)[]) => {
      for (const node of nodes)
        if (usable(node)) {
          node.focus();
          return;
        }
    };
    if (next === 'undo' && !usable(undoRef.current)) firstOf(redoRef.current);
    else if (next === 'redo' && !usable(redoRef.current))
      firstOf(undoRef.current);
    else if (next === 'land')
      firstOf(undoRef.current, redoRef.current, landing.current);
    else if (next === 'lost') {
      const active = document.activeElement;
      if (active === null || active === document.body || !active.isConnected)
        firstOf(undoRef.current, redoRef.current, landing.current);
    }
  });

  const { undo, redo } = dashboard.history;
  const edit = dashboard.edit;
  const take = (way: 'undo' | 'redo', by: 'button' | 'key') => {
    if (!edit || !editing) return;
    const step = way === 'undo' ? edit.undo() : edit.redo();
    if (!step) return;
    say(
      messages.label(
        way === 'undo' ? 'label.history.undone' : 'label.history.redone',
        { what: what(step) },
      ),
    );
    pending.current = by === 'key' ? 'lost' : way;
  };

  return {
    undoLabel: undo
      ? messages.label('label.history.undo-step', { what: what(undo) })
      : messages.label('label.history.undo'),
    redoLabel: redo
      ? messages.label('label.history.redo-step', { what: what(redo) })
      : messages.label('label.history.redo'),
    canUndo: undo !== null,
    canRedo: redo !== null,
    undo: () => take('undo', 'button'),
    redo: () => take('redo', 'button'),
    onKeyDown(event) {
      const way = shortcut(event);
      if (!way || !editing || event.defaultPrevented) return;
      const target = event.target as Node;
      if (!board.current?.contains(target) || typing(target)) return;
      event.preventDefault();
      take(way, 'key');
    },
    land: () => {
      pending.current = 'land';
    },
  };
}

/** The names a board has been seen to use, by panel id and by filter name. */
interface Seen {
  panels: ReadonlyMap<string, string>;
  filters: ReadonlyMap<string, string>;
}

const NOTHING_SEEN: Seen = { panels: new Map(), filters: new Map() };

/** `seen` with what is on screen now laid over it; the same object when nothing is new. */
function caughtUp(
  seen: Seen,
  names: ReadonlyMap<string, string>,
  fields: readonly { name: string; label: string }[],
): Seen {
  const fresh = (
    map: ReadonlyMap<string, string>,
    entries: [string, string][],
  ) =>
    entries.some(([key, value]) => map.get(key) !== value)
      ? new Map([...map, ...entries])
      : map;
  const panels = fresh(seen.panels, [...names]);
  const filters = fresh(
    seen.filters,
    fields.map(field => [field.name, field.label]),
  );
  return panels === seen.panels && filters === seen.filters
    ? seen
    : { panels, filters };
}

/** Which way a key press goes through the history, if it is one of its keys. */
function shortcut(event: KeyboardEvent<HTMLElement>): 'undo' | 'redo' | null {
  if (event.altKey || !(event.metaKey || event.ctrlKey)) return null;
  const key = event.key.toLowerCase();
  if (key === 'z') return event.shiftKey ? 'redo' : 'undo';
  if (key === 'y' && event.ctrlKey && !event.shiftKey) return 'redo';
  return null;
}

/** Whether a key goes to a field of its own, whose undo it is. */
function typing(target: Node): boolean {
  return (
    target instanceof Element &&
    target.closest(
      'input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"], [role="combobox"]',
    ) !== null
  );
}

/** The keys each button answers, said on it (`aria-keyshortcuts`). */
export const UNDO_KEYS = 'Meta+Z Control+Z';
export const REDO_KEYS = 'Meta+Shift+Z Control+Shift+Z Control+Y';
