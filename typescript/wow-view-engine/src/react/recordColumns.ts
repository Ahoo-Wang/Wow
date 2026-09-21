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

/**
 * What the column commands write: one column with one member changed, and
 * the whole list with the switched-off ones marked.
 *
 * **Every column here is rebuilt rather than spread over**, because a config
 * is JSON and `{ pinned: undefined }` is not the same object as one without
 * the key: it survives a `dequal` against the saved baseline as a
 * difference, and a view that was only unpinned back to where it started
 * would stay marked as unsaved for the rest of the session. The same holds
 * of a width back at automatic and of a column switched off and on again.
 *
 * They are pure functions of a column list, which is what lets the rule
 * "hiding a column keeps everything else about it" be read in one place
 * rather than inferred from three call sites.
 */

import type { RecordColumn, RecordColumnPin } from '../model/index.js';

/** One column held on one side of the table, or let go. */
export function repinned(
  column: RecordColumn,
  pinned: RecordColumnPin | null,
): RecordColumn {
  return {
    field: column.field,
    ...(column.width === undefined ? {} : { width: column.width }),
    ...(pinned === null ? {} : { pinned }),
    ...(column.hidden ? { hidden: column.hidden } : {}),
  };
}

/** One column at a fixed pixel width, or back to sizing itself. */
export function resized(
  column: RecordColumn,
  width: number | null,
): RecordColumn {
  return {
    field: column.field,
    ...(width === null ? {} : { width }),
    ...(column.pinned === undefined ? {} : { pinned: column.pinned }),
    ...(column.hidden ? { hidden: column.hidden } : {}),
  };
}

/**
 * One column switched on or off. Everything else about it stays: hiding a
 * column clears neither its width nor its pinning, because the entry is
 * kept precisely so that showing it again puts back the column that was
 * there.
 */
export function shown(column: RecordColumn, visible: boolean): RecordColumn {
  return {
    field: column.field,
    ...(column.width === undefined ? {} : { width: column.width }),
    ...(column.pinned === undefined ? {} : { pinned: column.pinned }),
    ...(visible ? {} : { hidden: true as const }),
  };
}

/**
 * The table's columns after `fields` is what it shows.
 *
 * A column the config already knows is switched **in place** — its entry
 * kept, `hidden` written or deleted — so switching it back on puts it back
 * where it was rather than at the end. Which is why this says nothing about
 * the order: a column that is not in the list has no order to give, so the
 * order is its own command.
 *
 * Two entries leave the list instead, because they have nowhere to come
 * back to: one whose field is not in `offered` — the definition dropped it,
 * or its kind is a handle rather than something a row holds — and a second
 * entry for a column already kept. Both are one row in the column settings
 * and both are configs `validateRecord` refuses (`record.field.unknown`,
 * `record.column.duplicate`), so switching them off is the repair it has
 * always been: hiding one would leave the query and the save blocked by the
 * control that had just run.
 *
 * A named field the config does not know yet joins at the end, once however
 * many times it is named — a column has to start somewhere.
 */
export function withColumnsShown(
  columns: readonly RecordColumn[],
  fields: readonly string[],
  offered: ReadonlySet<string>,
): RecordColumn[] {
  const visible = new Set(fields);
  const kept = new Set<string>();
  const placed = columns.flatMap(column => {
    if (kept.has(column.field)) return [];
    if (!visible.has(column.field) && !offered.has(column.field)) return [];
    kept.add(column.field);
    return [shown(column, visible.has(column.field))];
  });
  return [
    ...placed,
    ...fields.flatMap(field => {
      if (kept.has(field)) return [];
      kept.add(field);
      return [{ field }];
    }),
  ];
}
