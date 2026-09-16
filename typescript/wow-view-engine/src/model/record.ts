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

export interface RecordColumn {
  field: string;
  width?: number;
  pinned?: 'left' | 'right';
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
