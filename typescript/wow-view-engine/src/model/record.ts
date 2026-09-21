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

import type { SummaryFunction } from './field.js';
import type { ViewConfigBase } from './config.js';

/** One row as returned by the business query source. */
export type RecordData = Record<string, unknown>;

/** The value of a row's `rowKey` field. */
export type RecordKey = string | number;

/** Sort direction as stored in a config: Wow's `SortDirection` values. */
export type SortDirection = 'ASC' | 'DESC';

export type RecordLayout = 'table' | 'card';

/** Which paging protocol the source offers; declared by the definition. */
export type PagingMode = 'paged' | 'cursor';

/**
 * Where to read from. The definition's paging mode decides which member is
 * available, and a cursor of `null` asks for the first page.
 */
export type RecordPageTarget<P extends PagingMode = PagingMode> =
  P extends 'paged' ? { index: number } : { cursor: string | null };

export interface RecordSort {
  field: string;
  direction: SortDirection;
}

/**
 * The side a column is held on. A pinned column does not move with the rest
 * when the table scrolls sideways, and it never leaves its side.
 */
export type RecordColumnPin = 'left' | 'right';

export const RECORD_COLUMN_PINS: readonly RecordColumnPin[] = ['left', 'right'];

/**
 * A stored pinning, or `null` for anything that is not one.
 *
 * Configs arrive from a store, so `pinned` may be `'top'`, `''` or a number.
 * Read through here it becomes a side or nothing; read raw it became a key
 * into a wording table, and `undefined` from that lookup took the settings
 * popover — and the workbench around it — down with it. `validateRecord`
 * reports the bad value separately, so it is fixable rather than silent.
 */
export function columnPin(value: unknown): RecordColumnPin | null {
  return RECORD_COLUMN_PINS.includes(value as RecordColumnPin)
    ? (value as RecordColumnPin)
    : null;
}

/**
 * Whether a stored column is switched off. Anything but `true` shows it.
 *
 * `hidden` is the newer member, so most configs do not carry it at all and
 * a config from a store may carry anything under that name. Read through
 * here, a config written before the member existed reads as all visible,
 * which is what it always was.
 */
export function columnHidden(value: unknown): boolean {
  return value === true;
}

/**
 * One column of the table layout.
 *
 * A column the user switched off keeps its entry — `hidden: true` — rather
 * than leaving the list: the entry *is* its place in the order, so a column
 * put back comes back where it was instead of at the end, and it can be
 * dragged while it is off. `projectRecord` skips it, so it is not drawn, not
 * exported, and not the column the table draws last (D13).
 */
export interface RecordColumn {
  field: string;
  width?: number;
  pinned?: RecordColumnPin;
  /** Present, and only ever `true`, on a column the table does not draw. */
  hidden?: true;
}

export interface RecordSummary {
  field: string;
  fn: SummaryFunction;
}

export interface RecordTableSpec {
  columns: RecordColumn[];
}

export interface RecordCardSpec {
  /** Field shown as the card title. */
  title: string;
  /** Fields shown in the card body. */
  fields: string[];
  image?: string;
  columns?: 1 | 2 | 3 | 4;
}

/**
 * Both layouts are stored together, so switching between table and card keeps
 * the other one's settings; `layout` only records the current choice.
 */
export interface RecordViewConfig extends ViewConfigBase {
  kind: 'record';
  sort: RecordSort[];
  pageSize: number;
  summaries?: RecordSummary[];
  layout: RecordLayout;
  table: RecordTableSpec;
  card: RecordCardSpec;
}
