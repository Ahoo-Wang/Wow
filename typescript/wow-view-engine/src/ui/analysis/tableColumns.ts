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

import type { AnalysisColumnView } from '../../analysis/index.js';
import { useState } from 'react';
import { isDateCell } from '../../model/index.js';
import type { RecordColumnView } from '../../record/index.js';
import { bandText } from '../band.js';
import { displayValue, valueText, type DisplayContext } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/**
 * What an analysis column is read as, in the record table's words: the
 * renderer key its cells take. A metric that is not one of its field's values
 * is a number whatever it was computed from, so it says `number` — which is
 * what `isNumeric` reads to put a column's digits, and its header, on the
 * right edge. A dimension, and a metric that reads as its field (the latest
 * of a date), keep the field's own reading — except a time bucket, which
 * reads as the day it starts whatever the field holds its time as.
 */
export function readingOf(column: AnalysisColumnView): string {
  if (column.dateUnit !== undefined) return 'date';
  return column.cell ?? (column.role === 'metric' ? 'number' : 'string');
}

/**
 * One value as the result table's cell reads it: a number band as the band
 * it is, a group key or an ANY as its field's values show, and the rest —
 * anything the field's kind has nothing to say about — as a value, a number
 * in its column's format.
 *
 * The table's cells and the exported file (`analysisFile`) both read through
 * it, so the file says what the screen said (D25 Q28).
 */
export function analysisCellText(
  value: unknown,
  column: AnalysisColumnView,
  messages: MessageFormatters,
  display: DisplayContext,
): string {
  return (
    bandText(value, column, messages, display) ??
    displayValue(value, column, display) ??
    valueText(value, messages, column.numberFormat, display.locale)
  );
}

/**
 * Whether a column's values are identifiers: the field reads as a value to
 * take away character for character (`cell: 'copyable'`), which is how the
 * record view decides the monospace of an id.
 */
export function isIdentifier(column: AnalysisColumnView): boolean {
  return column.cell === 'copyable';
}

/**
 * The least a column of each reading is given, in pixels, whatever its first
 * answer held: a moment needs a date and a time of day even when the first
 * rows were all midnight; anything else room for a short word.
 */
const FLOOR = { number: 96, moment: 160, text: 112 };

/**
 * The most a column's first answer may ask for. Past it a value is cut with
 * an ellipsis, whole one hover away — a name of sixty characters is not a
 * reason for every other column to leave the screen.
 */
const CONTENT_MAX = 400;

/** The header: its size (`--text-ui`), what it draws beside its name, cap. */
const HEADER_EM = 13;
const HEADER_CHROME = 44;
const HEADER_MAX = 320;

/** A cell: its size (`text-sm`), the id face's, and its own padding. */
const CELL_EM = 14;
const IDENTIFIER_SCALE = 0.9;
const CELL_CHROME = 18;

/**
 * The width a column is drawn at when the table declares none: the room its
 * header and **the values it was first drawn with** ask for, estimated from
 * their characters — a full em for a CJK one, 0.6em for anything narrower —
 * with a floor by reading and a ceiling.
 *
 * Estimated rather than measured: a measurement is a second render, and one
 * more thing that changes. It is taken once per column, and held
 * (`useHeldWidths`): the answer a column is first seen with sizes it, and a
 * run that brings back a longer name or a bigger total does not move it.
 */
export function columnWidthOf(
  column: AnalysisColumnView,
  title: string,
  values: readonly string[],
): number {
  if (column.width !== undefined) return column.width;
  const reading = readingOf(column);
  const floor =
    reading === 'number'
      ? FLOOR.number
      : isDateCell(reading)
        ? FLOOR.moment
        : FLOOR.text;
  const header = Math.min(
    Math.ceil(ems(title) * HEADER_EM) + HEADER_CHROME,
    HEADER_MAX,
  );
  const size = CELL_EM * (isIdentifier(column) ? IDENTIFIER_SCALE : 1);
  const widest = Math.max(0, ...values.map(ems));
  const content = Math.min(Math.ceil(widest * size) + CELL_CHROME, CONTENT_MAX);
  return Math.max(floor, header, content);
}

/**
 * Each column's width, held from the first answer it was drawn with.
 *
 * An automatic table layout sizes a column by its widest cell *every time*,
 * so a run that brought back a longer name or a bigger total moved every
 * column after it, and the column the eye was following was somewhere else
 * after each question. Here a column is sized once — from its header and the
 * values it first appeared with, so it fits what it was opened on — and then
 * keeps that width for as long as it is the same column: same alias, same
 * title, same declared width. A new column, or a renamed one, is sized from
 * the answer it arrives with; one that left is forgotten.
 *
 * The widths are state kept from an earlier render, so they are set while
 * rendering when a column is new — React's own pattern for state that
 * follows a prop — and never in an effect, which would draw one frame at the
 * automatic width first.
 */
export function useHeldWidths(
  columns: readonly {
    column: AnalysisColumnView;
    title: string;
    values: () => readonly string[];
  }[],
): readonly number[] {
  const [held, setHeld] = useState<ReadonlyMap<string, number>>(NO_WIDTHS);
  const sized = columns.map(({ column, title, values }) => {
    const key = `${column.alias}\u0000${title}\u0000${column.width}`;
    return [
      key,
      held.get(key) ?? columnWidthOf(column, title, values()),
    ] as const;
  });
  const same =
    held.size === sized.length && sized.every(([key]) => held.has(key));
  if (!same) setHeld(new Map(sized));
  return sized.map(([, width]) => width);
}

const NO_WIDTHS: ReadonlyMap<string, number> = new Map();

/**
 * The analysis column as the record table's header takes it, so the header
 * is `SortableHeader` itself — the sort button, its marks and place, the
 * tooltip over a cut name, the one Tab stop — rather than a second copy of
 * it. The alias is the field: it is what the sort names.
 */
export function headerColumnOf(
  column: AnalysisColumnView,
  title: string,
  width: number,
  sortable: boolean,
): RecordColumnView {
  return {
    field: column.alias,
    label: title,
    kind: column.kind ?? readingOf(column),
    cell: readingOf(column),
    width,
    sortable,
  };
}

function ems(text: string): number {
  let total = 0;
  for (const char of text) total += char.charCodeAt(0) >= 0x2e80 ? 1 : 0.6;
  return total;
}
