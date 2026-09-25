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

import type { ReactNode } from 'react';
import type { RecordKey } from '../model/index.js';
import type { RecordRow } from '../record/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';

/**
 * Where a host hangs its own business actions on a record workbench.
 *
 * A business action is code — it opens a form, posts a command, navigates —
 * so it is passed in as a render function rather than named by a key in a
 * definition or a config. Nothing here is persisted: a saved view is a way
 * of looking at records, and what may be done to them belongs to the
 * application that mounted the workbench, not to the view the user saved.
 *
 * Every context carries `refresh`, because an action that changes records
 * has to be able to show the change.
 */
export interface RecordActionSlots {
  /** Beside the view's own actions in the header; acts on no row. */
  global?(context: RecordGlobalActionContext): ReactNode;
  /** In the toolbar while rows are selected; acts on all of them. */
  bulk?(context: RecordBulkActionContext): ReactNode;
  /** In each row, or on each card; acts on that one. */
  row?(context: RecordRowActionContext): ReactNode;
}

export interface RecordGlobalActionContext {
  runtime: RecordViewRuntime;
  refresh(): void;
}

export interface RecordBulkActionContext {
  /** The selected rows in result order, so a confirmation can name them. */
  rows: RecordRow[];
  keys: RecordKey[];
  runtime: RecordViewRuntime;
  /** Selection survives a refresh, so an action that consumed it says so. */
  clearSelection(): void;
  /** Picks exactly these rows — what a command leaves for the reader. */
  select(keys: readonly RecordKey[]): void;
  refresh(): void;
}

export interface RecordRowActionContext {
  row: RecordRow;
  runtime: RecordViewRuntime;
  refresh(): void;
}

/**
 * A section the host adds to a record's detail, beside the ones the engine
 * lays out from the definition's field groups (`detailSections`) — what the
 * definition cannot say because it is the application's: a form that acts
 * on the record, a stack trace read the host's way, the record's history as
 * an embedded view (G2).
 *
 * `render` is called only while the section is on screen — the detail open
 * on a record the engine has in hand — so a section that reads something
 * of its own (an `EmbeddedView`) reads it when the reader opens the record,
 * not before. Each section is drawn inside a boundary of its own: one that
 * throws takes itself and not the detail.
 */
export interface RecordDetailSection {
  /** Unique among the host's sections; React's key and `data-section`. */
  id: string;
  /** The section's heading, which also names it for a screen reader. */
  title: string;
  /**
   * Where the section stands among the engine's: `'end'` (the default)
   * after every one of them, `'start'` before the first, `{ after }` right
   * after the field group of that id — at the end when the definition has
   * no such group. Host sections at the same place keep the order given.
   */
  placement?: RecordDetailPlacement;
  render(): ReactNode;
}

export type RecordDetailPlacement = 'start' | 'end' | { after: string };

export interface RecordDetailSectionContext {
  /**
   * The record open in the detail: the page's row until the whole record
   * has come (`complete`), the whole record from then on.
   */
  row: RecordRow;
  /** Whether `row.data` is the whole record rather than the page's row. */
  complete: boolean;
  runtime: RecordViewRuntime;
  /** Runs the view again; the detail reads the record again when it lands. */
  refresh(): void;
}
