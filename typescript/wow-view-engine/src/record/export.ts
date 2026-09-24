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

import type { RecordData } from '../model/index.js';
import { recordValue } from './project.js';

/** The least an export needs of a column: where to read it, what to call it. */
export interface ExportColumn {
  /** A Wow query path, read the way a cell reads it. */
  field: string;
  /** What the header row calls this column. */
  label: string;
}

/**
 * How one value is written.
 *
 * It is injected rather than decided here, because how a value *reads* is the
 * UI's answer — an enum by its label, a time in the surface's zone, a number
 * in its field's format — and this layer has no catalogue, no locale and no
 * renderers. The column rides along with the value, so one formatter serves
 * every column of the table.
 */
export type ExportFormat<C extends ExportColumn = ExportColumn> = (
  value: unknown,
  column: C,
) => string;

/**
 * The byte order mark Excel needs to read a UTF-8 CSV as UTF-8.
 *
 * Without it Excel on Windows reads the file in the machine's ANSI code page
 * and every Chinese name in it arrives as mojibake — which is most of what
 * these exports carry.
 */
export const CSV_BOM = '﻿';

/** RFC 4180 ends every record with CRLF, the last one included. */
const CRLF = '\r\n';

/** The characters that oblige a field to be quoted, the quote included. */
const MUST_QUOTE = /["\r\n,]/;

/**
 * What a spreadsheet may read a cell as a formula by: its first character
 * (OWASP, CSV Injection). `=`, `+`, `-` and `@` start a formula or a
 * function; a tab or a carriage return in front hides one from a reader who
 * trims.
 */
const FORMULA_START = /^[=+\-@\t\r]/;

/**
 * A text that is a number and nothing else: a sign, digits, one decimal
 * point, an exponent. No separators, no currency, no spaces — nothing a
 * spreadsheet could read as more than one number.
 */
const PLAIN_NUMBER = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** How a CSV is written. */
export interface CsvOptions {
  /**
   * Whether a cell a spreadsheet could evaluate is written with an
   * apostrophe in front, so it opens as the text it is. On unless set to
   * `false`.
   *
   * A file leaves the page: it is opened in Excel, often by someone other
   * than whoever exported it, and a value somebody typed into a text field
   * — `=HYPERLINK(…)`, `=cmd|' /C calc'!A0` — would run there as a formula
   * (OWASP, CSV Injection). So every cell whose text starts with `=`, `+`,
   * `-`, `@`, a tab or a carriage return gets a leading `'`, the header's
   * labels included, since a definition names them.
   *
   * Two kinds of cell are left alone, because a spreadsheet reads them as a
   * number and never as a formula: one whose value was a number (a negative
   * amount, however its field formats it — `-1,204.50`, `-$12.00`), and one
   * whose text is a plain number (`-12.5` held by a text field). Prefixing
   * either would only make the file differ from the screen.
   *
   * Turn it off only where the file never reaches a spreadsheet — a
   * pipeline that reads the CSV back as data and wants the text as stored.
   */
  neutralizeFormulas?: boolean;
}

/** One cell of a file: the value it was read from, and the text it reads as. */
export interface CsvCell {
  /** What the row held; only its type is looked at: a number is never a formula. */
  value: unknown;
  /** The text written, before any escaping. */
  text: string;
}

/**
 * The rows as a CSV, RFC 4180 with a BOM in front.
 *
 * The header is the labels of the columns handed over, in the order they are
 * handed over — which is the projected order of the visible columns, so the
 * file carries the table's columns rather than the definition's fields.
 *
 * Nothing here looks at what a value *means*: `format` has already decided
 * that, and what is left is writing it (`writeCsv`) — the escaping, and the
 * formulas neutralized unless `options` says otherwise (`CsvOptions`).
 */
export function serializeCsv<C extends ExportColumn>(
  rows: readonly RecordData[],
  columns: readonly C[],
  format: ExportFormat<C>,
  options: CsvOptions = {},
): string {
  return writeCsv(
    columns.map(column => column.label),
    rows.map(row =>
      columns.map(column => {
        const value = recordValue(row, column.field);
        return { value, text: format(value, column) };
      }),
    ),
    options,
  );
}

/**
 * Cells already read, as a CSV: the one writer every export goes through —
 * a record view's rows (`serializeCsv`) and an analysis's groups alike — so
 * the escaping and the formula rule (`CsvOptions.neutralizeFormulas`) are
 * one rule rather than one per export.
 */
export function writeCsv(
  header: readonly string[],
  rows: readonly (readonly CsvCell[])[],
  options: CsvOptions = {},
): string {
  const neutralize = options.neutralizeFormulas !== false;
  const field = (cell: CsvCell) => csvField(cell, neutralize);
  const lines = [header.map(label => field({ value: label, text: label }))];
  for (const row of rows) lines.push(row.map(field));
  return `${CSV_BOM}${lines.map(line => line.join(',')).join(CRLF)}${CRLF}`;
}

/**
 * One field: neutralized where a spreadsheet could evaluate it, then quoted
 * where it has to be.
 *
 * The text is typed as a string, but it comes from a function a host may
 * have written, so it is coerced rather than trusted: `null` written into
 * the file as the word `null` is a value the table never showed.
 */
function csvField({ value, text }: CsvCell, neutralize: boolean): string {
  let written = typeof text === 'string' ? text : String(text ?? '');
  if (neutralize && evaluable(value, written)) written = `'${written}`;
  return MUST_QUOTE.test(written)
    ? `"${written.split('"').join('""')}"`
    : written;
}

/** Whether a spreadsheet opening this cell could evaluate it (`CsvOptions`). */
function evaluable(value: unknown, text: string): boolean {
  if (typeof value === 'number' || typeof value === 'bigint') return false;
  return FORMULA_START.test(text) && !PLAIN_NUMBER.test(text);
}
