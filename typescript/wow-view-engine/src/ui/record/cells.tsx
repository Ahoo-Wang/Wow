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
import { cn } from 'cn';
import { isSafeContentUrl } from '../../dashboard/index.js';
import { CopyButton } from '../CopyButton.js';
import {
  badgeEntries,
  cellText,
  displayValue,
  formatNumber,
  type BadgeEntry,
  type DisplayContext,
  type DisplayField,
} from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { LongText, ToneBadge } from '../variants.js';

/**
 * What a renderer knows about the field a value came from — which is what any
 * reading of a value needs, `cellText` in a CSV included, so it is that type
 * under the name this side uses for it.
 */
export type CellField = DisplayField;

/**
 * How wide a `text` cell may grow before it wraps. A table lays out by
 * content, so a paragraph with no ceiling makes one column as wide as its
 * longest note and pushes every other column off the screen; the clamp only
 * limits the lines, and a line has to end somewhere for there to be a second
 * one.
 */
const TEXT_CELL = 'max-w-[var(--fve-record-text-max-w,24rem)]';

/**
 * Where the reading is drawn, which is the one thing the two surfaces
 * disagree about: **how many lines a value may take**.
 *
 * A table is read down a column — the eye runs a straight line to find the
 * row it wants — and a row that is two lines tall wherever a note happens to
 * be long makes that line a staircase. On the twenty-column fixture the rows
 * measured 41, 61 and 77 pixels, three heights in one table, because a note
 * clamped to three lines and a pair of tags wrapped to two. A card has no
 * column to line up with and all the room it wants downwards, so it keeps
 * the three lines.
 *
 * Nothing else differs, which is the point of there being one function: a
 * card's status is the same badge as the column it was folded out of.
 */
export type CellSurface = 'table' | 'card' | 'detail';

/**
 * One value as its field reads it.
 *
 * The table and the cards both come through here, so a card can never
 * disagree with the column it was folded out of. What the field declares
 * decides: `status` and `tags` wear badges, `link` is a guarded external
 * link, `text` is a paragraph as long as the surface allows, `copyable` is
 * the value with the means to take it away, and a field that declares
 * nothing falls through to the kind's own rendering — a date in the
 * surface's zone, a number in its format, a boolean in the catalogue's
 * words.
 */
export function cellValue(
  value: unknown,
  field: CellField,
  messages: MessageFormatters,
  display: DisplayContext,
  surface: CellSurface,
): React.ReactNode {
  if (value === null || value === undefined) return null;

  const badges = badgeEntries(value, field);
  if (badges) return <Badges entries={badges} surface={surface} />;

  const cell = field.cell ?? field.kind;
  // A record's detail reads a long value whole: the message a column cut to
  // one line, the stack trace no column holds. What makes it long is what it
  // is — lines, or more than a line's worth — not what the field declares.
  if (surface === 'detail' && typeof value === 'string' && isLong(value))
    return (
      <span data-slot="cell-long" className="group/copyable relative block">
        <LongText>{value}</LongText>
        <CopyButton value={value} className="absolute top-1 right-1" />
      </span>
    );
  if (cell === 'link' && typeof value === 'string' && isSafeContentUrl(value))
    return (
      <a
        data-slot="cell-link"
        href={value}
        // A record's URLs come from the data, and the data is not this
        // application: the new document must not reach back through
        // `window.opener`, nor arrive carrying where it was opened from.
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary underline underline-offset-4"
      >
        {value}
      </a>
    );
  if (cell === 'text' && typeof value === 'string')
    return (
      <span
        data-slot="cell-text"
        className={cn(
          TEXT_CELL,
          surface === 'table'
            ? // One line in a table, so every row is the same height and a
              // column can be read straight down. `block`, because
              // `max-width` and an ellipsis need a box and a bare `<span>`
              // is not one; the newlines the author typed come out as
              // spaces, which is what a one-line reading of a paragraph is.
              'block truncate'
            : // Three lines on a card, where there is no column to line up
              // with. Clamped rather than truncated, and the author's own
              // newlines kept: a paragraph folded into one line is a
              // different paragraph, and here there is room not to fold it.
              'line-clamp-3 whitespace-pre-wrap',
        )}
        // Either way the whole of it is one hover away.
        title={value}
      >
        {value}
      </span>
    );

  if (cell === 'copyable') {
    // What is copied is what is read: the same one-line reading the CSV and
    // the title attribute take, so the clipboard never comes back with
    // something the screen did not say.
    const text = cellText(value, field, messages, display);
    // Nothing to take away, so nothing to offer. A blank cell with a button
    // in it is a button that copies the empty string.
    if (text === '') return null;
    return (
      <span
        data-slot="cell-copyable"
        // The cell is a group of its own so that a host drawing this
        // reading in its own markup still has something to hover; the row
        // is the other group, and the table provides that one.
        className="group/copyable inline-flex items-center gap-1"
      >
        {text}
        <CopyButton value={text} className="shrink-0" />
      </span>
    );
  }

  // A time, a date or an enum shows as the field says; a number keeps its
  // format and a boolean its wording below.
  const shown = displayValue(value, field, display);
  if (shown !== undefined) return shown;
  if (typeof value === 'number')
    return formatNumber(value, field.numberFormat, display.locale);
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  if (typeof value === 'string') return value;
  if (typeof value === 'bigint') return value.toString();
  return JSON.stringify(value);
}

/**
 * The badges of one cell, side by side.
 *
 * **Side by side and staying there, in a table.** A list of tags wrapped to
 * a second line the moment the column was narrower than the two badges in
 * it, which on the twenty-column fixture was most of them: two tags made a
 * 61px row among 41px ones, and the column that caused it is three
 * characters wide. A column carrying several tags is a column that has to be
 * that wide — the width belongs to the content, not to the row's height.
 * A card wraps, having the room downwards and no column to line up with.
 *
 * Keyed by the value and its place, never by the label: a list may hold the
 * same value twice and two options may be worded alike, and two children
 * under one key is a reconciliation React is free to get wrong.
 */
function Badges({
  entries,
  surface,
}: {
  entries: readonly BadgeEntry[];
  surface: CellSurface;
}) {
  return (
    <span
      className={cn(
        'flex items-center gap-1',
        surface === 'card' && 'flex-wrap',
      )}
    >
      {entries.map((entry, index) => (
        <ToneBadge
          key={`${String(entry.value)}-${index}`}
          tone={entry.tone ?? 'neutral'}
        >
          {entry.label}
        </ToneBadge>
      ))}
    </span>
  );
}

/** Past this many characters a value is read as a paragraph, not a label. */
const LONG_VALUE = 120;

function isLong(value: string): boolean {
  return value.length > LONG_VALUE || value.includes('\n');
}
