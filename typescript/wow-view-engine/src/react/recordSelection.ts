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

import type { RecordKey } from '../model/index.js';
import type { RecordPaging, RecordRow } from '../record/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';

/**
 * Which rows a set of rows is: the question that fetched them and the page
 * of its answer. Two results with the same mark are the same rows asked for
 * again — a refresh — and two with different marks are a different set, even
 * where a key turns up in both.
 *
 * The question is the applied config by identity (`ViewResult.own`): it is a
 * new object exactly when `apply` promotes a draft — a condition, a sort — and
 * the same one across every refresh of it.
 */
export interface RowsMark {
  readonly question: unknown;
  readonly page: number | string | null;
}

/** Where the last plain toggle landed, and among which rows. */
export interface SelectionAnchor {
  readonly key: RecordKey;
  readonly rows: RowsMark;
}

type RecordSnapshot = ReturnType<RecordViewRuntime['getSnapshot']>;

/** The rows on screen and their mark, or `null` before any record result. */
export function rowsOnScreen(
  snapshot: RecordSnapshot,
): { rows: readonly RecordRow[]; mark: RowsMark } | null {
  const result = snapshot.result;
  if (!result || result.data.kind !== 'record') return null;
  const view = result.data.view;
  return {
    rows: view.rows,
    mark: { question: result.own, page: pageOf(view.paging) },
  };
}

/**
 * The page a result is. A cursor result does not say which page it is, only
 * the way on — and that is one per page, which is all a mark needs.
 */
function pageOf(paging: RecordPaging): number | string | null {
  return paging.mode === 'paged' ? paging.index : paging.nextCursor;
}

/**
 * The anchor a range extends from, when it still stands among these rows:
 * set on this very page of this very question, and its row still on it.
 */
export function standingAnchor(
  anchor: SelectionAnchor | null,
  rows: readonly RecordRow[],
  mark: RowsMark,
): RecordKey | null {
  if (
    anchor === null ||
    anchor.rows.question !== mark.question ||
    anchor.rows.page !== mark.page
  )
    return null;
  return rows.some(row => row.key === anchor.key) ? anchor.key : null;
}

/**
 * The selection after `key` is toggled — alone, or with every row between
 * `anchor` and it when there is an anchor to extend from.
 *
 * A range goes the way the pressed row goes: pressing an unselected row
 * selects the range, pressing a selected one unselects it, which is the rule
 * every file manager and mail client keeps. Rows are taken in result order,
 * so a range pressed upwards covers what one pressed downwards would.
 * Selected rows outside the range are left as they are.
 */
export function toggledSelection(
  selection: readonly RecordKey[],
  rows: readonly RecordRow[],
  key: RecordKey,
  anchor: RecordKey | null,
): RecordKey[] {
  const selecting = !selection.includes(key);
  const from = anchor === null ? -1 : rows.findIndex(row => row.key === anchor);
  const to = rows.findIndex(row => row.key === key);
  const range =
    from < 0 || to < 0
      ? [key]
      : rows
          .slice(Math.min(from, to), Math.max(from, to) + 1)
          .map(row => row.key);
  if (!selecting) {
    const leaving = new Set(range);
    return selection.filter(entry => !leaving.has(entry));
  }
  const held = new Set(selection);
  return [...selection, ...range.filter(entry => !held.has(entry))];
}
