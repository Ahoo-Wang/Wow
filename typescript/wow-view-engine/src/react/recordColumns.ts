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
 * rather than inferred from every call site.
 */

import type { RecordColumn } from '../model/index.js';

/**
 * One column entry, carrying only the members it has something to say with.
 *
 * The one place a `RecordColumn` is built, so the rule above is kept once: a
 * member that is nothing — no width, not pinned, not hidden — leaves the key
 * out altogether rather than writing `undefined` under it. The three commands
 * below and the draft's own reading of a stored list all come through here;
 * each of them used to spell the three spreads out, and four spellings of
 * "leave the key out" is four chances for one of them to write
 * `{ pinned: undefined }` and mark a view unsaved for ever.
 */
export function recordColumn(
  field: string,
  members: {
    /** Pixels, or nothing for a column that sizes itself. */
    width?: number | null;
    /** Held against the left edge, or nothing for one that scrolls (D19). */
    pinned?: boolean | null;
    /** Whether the table leaves it undrawn; only `true` is ever written. */
    hidden?: boolean;
  },
): RecordColumn {
  return {
    field,
    ...(members.width == null ? {} : { width: members.width }),
    ...(members.pinned ? { pinned: true } : {}),
    ...(members.hidden ? { hidden: true as const } : {}),
  };
}

/** One column held against the table's left edge, or let go (D19). */
export function repinned(column: RecordColumn, pinned: boolean): RecordColumn {
  return recordColumn(column.field, {
    width: column.width,
    pinned,
    hidden: column.hidden,
  });
}

/** One column at a fixed pixel width, or back to sizing itself. */
export function resized(
  column: RecordColumn,
  width: number | null,
): RecordColumn {
  return recordColumn(column.field, {
    width,
    pinned: column.pinned,
    hidden: column.hidden,
  });
}

/**
 * One column switched on or off. Everything else about it stays: hiding a
 * column clears neither its width nor its pinning, because the entry is
 * kept precisely so that showing it again puts back the column that was
 * there.
 */
export function shown(column: RecordColumn, visible: boolean): RecordColumn {
  return recordColumn(column.field, {
    width: column.width,
    pinned: column.pinned,
    hidden: !visible,
  });
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

/**
 * The table's columns in the order `fields` names them.
 *
 * A name that is not a column is ignored and a column the caller leaves
 * unnamed keeps its place at the end, so a control that knows about part of
 * the table — one area of the column settings — cannot drop the rest of it
 * by saying nothing about it.
 *
 * The columns are all of them, the switched-off ones included: a hidden
 * column has a place in the order, which is what makes switching it back on
 * put it back where it was, so a caller that means to order the whole table
 * names it along with the rest.
 */
export function reordered(
  columns: readonly RecordColumn[],
  fields: readonly string[],
): RecordColumn[] {
  const byField = new Map(columns.map(column => [column.field, column]));
  const named = new Set<string>();
  const ordered = fields.flatMap(field => {
    const column = byField.get(field);
    // A name repeated by the caller would otherwise become a second column
    // of the same field, which `validateRecord` then refuses.
    if (!column || named.has(field)) return [];
    named.add(field);
    return [column];
  });
  return [...ordered, ...columns.filter(column => !named.has(column.field))];
}
