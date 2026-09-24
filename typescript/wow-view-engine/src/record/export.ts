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
 * The rows as a CSV, RFC 4180 with a BOM in front.
 *
 * The header is the labels of the columns handed over, in the order they are
 * handed over — which is the projected order of the visible columns, so the
 * file carries the table's columns rather than the definition's fields.
 *
 * Nothing here looks at what a value *means*: `format` has already decided
 * that, and what is left is the escaping. Values are written as they read and
 * never rewritten — a leading `=` is a value a spreadsheet may offer to
 * evaluate, and an exporter that quietly prefixes an apostrophe hands back a
 * file whose contents are not what the screen said they were.
 */
export function serializeCsv<C extends ExportColumn>(
  rows: readonly RecordData[],
  columns: readonly C[],
  format: ExportFormat<C>,
): string {
  const lines = [columns.map(column => csvField(column.label)).join(',')];
  for (const row of rows)
    lines.push(
      columns
        .map(column => csvField(format(recordValue(row, column.field), column)))
        .join(','),
    );
  return `${CSV_BOM}${lines.join(CRLF)}${CRLF}`;
}

/**
 * One field, quoted where it has to be.
 *
 * `format` is typed to answer with a string, but it is a function a host may
 * have written, so what comes back is coerced rather than trusted: `null`
 * written into the file as the word `null` is a value the table never showed.
 */
function csvField(value: string): string {
  const text = typeof value === 'string' ? value : String(value ?? '');
  return MUST_QUOTE.test(text) ? `"${text.split('"').join('""')}"` : text;
}
