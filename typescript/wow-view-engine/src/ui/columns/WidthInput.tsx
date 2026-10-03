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

import { useEffect, useRef } from 'react';
import { Input } from '../components/input.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { MAX_COLUMN_WIDTH, MIN_COLUMN_WIDTH } from '../record/ColumnResizer.js';

interface WidthInputProps {
  /** The column's name, as the row shows it. */
  label: string;
  /** Its width in pixels; `undefined` while it sizes itself. */
  width: number | undefined;
  disabled?: boolean;
  describedBy?: string;
  /**
   * The line that says what was wrong with what was typed (`invalid`),
   * which the row draws; the box is described by it while it is shown.
   */
  errorId: string;
  /** Whether what is typed is no width, and the row says so. */
  invalid: boolean;
  /** Tells the row what was typed is no width, or is one again. */
  onInvalid(invalid: boolean): void;
  /** A width in pixels, or `null` for back to its content's. */
  onWidth(width: number | null): void;
}

/**
 * A column's width, typed (WCAG 2.2 2.5.7).
 *
 * The header's edge is dragged, and a drag is a path: a pointer that
 * cannot hold a button down and travel — a head pointer, a tremor, a
 * switch — had no way to a width at all, the keyboard's Alt+←/→ being no
 * help to it. A number in the column settings is one press and some typing.
 * It is also the equivalent control that lets the edge stay a narrow strip
 * beside the sort button (2.5.8's exception).
 *
 * The draft is the browser's, as the page box's is (`RecordPagination`):
 * read on Enter and on leaving, never per keystroke, so typing 1, 12, 120
 * is one width and not three. Empty is automatic, and a width under the
 * edge's own floor is raised to it — the same rule a drag keeps — as one
 * past `MAX_COLUMN_WIDTH` is lowered to that. What is not a whole number
 * stays in the box, marked invalid, with the row's line saying what a
 * width is: put back silently, a typo looked like a width that took.
 */
export function WidthInput({
  label,
  width,
  disabled,
  describedBy,
  errorId,
  invalid,
  onInvalid,
  onWidth,
}: WidthInputProps) {
  const messages = useViewMessages();
  const box = useRef<HTMLInputElement>(null);
  const shown = width === undefined ? '' : String(width);

  // What the config holds is what the box says once it lands, whichever way
  // it changed — a drag on the header, a reset, a revert.
  useEffect(() => {
    const node = box.current;
    if (node && node.ownerDocument.activeElement !== node) node.value = shown;
  }, [shown]);

  const commit = () => {
    const node = box.current;
    if (!node) return;
    const typed = node.value.trim();
    const number = Number(typed);
    if (typed !== '' && (!/^\d+$/.test(typed) || !Number.isFinite(number))) {
      onInvalid(true);
      return;
    }
    onInvalid(false);
    if (typed === '') {
      if (width !== undefined) onWidth(null);
      return;
    }
    const next = Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, number));
    node.value = String(next);
    if (next !== width) onWidth(next);
  };

  return (
    <Input
      ref={box}
      data-slot="column-width"
      aria-label={messages.label('label.columns.width', { field: label })}
      aria-describedby={
        [describedBy, invalid ? errorId : undefined]
          .filter(Boolean)
          .join(' ') || undefined
      }
      aria-invalid={invalid || undefined}
      placeholder={messages.label('label.columns.width-auto')}
      inputMode="numeric"
      disabled={disabled}
      className="fve:h-7 fve:w-16 fve:text-right fve:tabular-nums"
      defaultValue={shown}
      onBlur={commit}
      onKeyDown={event => {
        if (event.key !== 'Enter') return;
        event.preventDefault();
        commit();
      }}
    />
  );
}
