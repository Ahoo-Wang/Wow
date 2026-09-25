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

import type * as React from 'react';
import { useId, useLayoutEffect } from 'react';
import type { RecordRow } from '../../record/index.js';
import type { RovingAxis } from '../roving.js';
import { moveStop, settleStop, takeStop } from '../roving.js';

/**
 * Rows a reader opens: a press on the row's own ground, or Enter/Space on
 * the row the keyboard is on.
 *
 * There is no "open" button in the row. One would have to be a column of
 * its own, or crowd the host's commands out of the pinned action column,
 * for something the row already is — the record. A pointer presses the
 * row; a keyboard reaches the rows as one Tab stop (`roving.ts`, as the
 * analysis result's rows do), moves with the arrows, and opens with Enter
 * or Space; a screen reader hears what those keys do from the hint the
 * surface renders once (`hintId`) and every row points at.
 *
 * `null` for `open` gives rows that are not members of anything: no stop,
 * no hint, no cursor — the surface without a detail reads as it did.
 */
export interface OpenRows {
  /** The id of the one hint the surface renders, while rows open. */
  readonly hintId: string | undefined;
  /** The props that make one row open. */
  row(row: RecordRow): OpenRowProps;
}

export interface OpenRowProps {
  'data-openable'?: '';
  /**
   * The row's key, as text: where the detail hands focus back when it
   * closes on a record it was not opened from (a link, `useRecordDetail`'s
   * `open`), and a row on the page holds that record.
   */
  'data-row-key'?: string;
  'aria-describedby'?: string;
  onClick?(event: React.MouseEvent<HTMLElement>): void;
  onFocus?(event: React.FocusEvent<HTMLElement>): void;
  onKeyDown?(event: React.KeyboardEvent<HTMLElement>): void;
}

/** The members of a surface's group, in the order they are drawn. */
const MEMBER = '[data-openable]';

/**
 * `container` is the element the rows live in — the caller's ref, so what
 * this hands back is only what render reads.
 */
export function useOpenRows(
  container: React.RefObject<HTMLElement | null>,
  open: ((row: RecordRow) => void) | undefined,
  ...axes: readonly RovingAxis[]
): OpenRows {
  const hint = useId();
  const members = () => [
    ...(container.current?.querySelectorAll<HTMLElement>(MEMBER) ?? []),
  ];
  // No dependency list: a page that lands is a new set of rows, and the
  // stop has to be on one of *these*.
  useLayoutEffect(() => {
    settleStop(members());
  });
  if (!open) return { hintId: undefined, row: () => ({}) };
  return {
    hintId: hint,
    row: row => ({
      'data-openable': '',
      'data-row-key': String(row.key),
      'aria-describedby': hint,
      onClick: event => pressRow(event, () => open(row)),
      // The stop follows the keyboard: a row focused is the row the
      // group's one stop is on, however focus got there — an arrow, a
      // press, or the detail handing it back when it closes.
      onFocus: event => {
        if (event.target === event.currentTarget)
          takeStop(event.currentTarget, members());
      },
      onKeyDown: event => {
        // A key on something inside the row — Space on its checkbox, Enter
        // on one of its commands — is that thing's.
        if (event.target !== event.currentTarget) return;
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          open(row);
          return;
        }
        moveStop(event, members(), ...axes);
      },
    }),
  };
}

/**
 * Opens a row from a press on its own ground — not on something in it that
 * does something else (a checkbox, a copy button, a link, the row's own
 * commands), and not when the press ended a selection of text, which is a
 * reader copying a value rather than asking for more.
 */
function pressRow(event: React.MouseEvent<HTMLElement>, open: () => void) {
  const target = event.target as Element | null;
  if (target?.closest(INTERACTIVE)) return;
  const selected = window.getSelection?.()?.toString() ?? '';
  if (selected.length > 0) return;
  open();
}

const INTERACTIVE =
  'a, button, input, label, select, textarea, [role="checkbox"], [role="menuitem"], [data-slot="row-actions"]';
