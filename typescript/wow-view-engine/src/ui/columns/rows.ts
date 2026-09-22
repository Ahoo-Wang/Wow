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

import {
  columnPinned,
  isFieldlessKind,
  summaryFunctionsOf,
  type FieldDefinition,
  type SummaryFunction,
} from '../../model/index.js';

/**
 * The two areas a table draws its columns in, and the settings list them
 * in: what is held against the left edge, and what scrolls.
 *
 * An area *is* the pinning, and a pinning is one yes-or-no (D19).
 * `projectRecord` lays the columns out this way, because `sticky` fixes an
 * element where it already is — a column pinned in the middle of the table
 * scrolls away like any other, and the pinning is not a promise a
 * stylesheet can keep on its own. A row moves between the areas by being
 * pinned, and is dragged only inside the one it is in: an order that
 * crossed an area would be written to the config and then laid out
 * differently by the table.
 *
 * There is no third area, because the table's right edge is not a pinning
 * anybody chose: it holds the host's action column, or — where there is
 * none — whichever column happens to be drawn last (D13). Neither of those
 * is a row in this list.
 */
export type ColumnRegion = 'pinned' | 'scrolling';

/** The areas in the order a table draws them. */
export const REGIONS: readonly ColumnRegion[] = ['pinned', 'scrolling'];

/** One line of the column settings, with everything its controls need. */
export interface ColumnSettingRow {
  /** The field's name. */
  field: string;
  label: string;
  region: ColumnRegion;
  /** Whether the table shows this column. */
  visible: boolean;
  /**
   * Whether the config knows this column, and it therefore has a place in
   * the table's order.
   *
   * It is not `visible`: a column switched off keeps its entry, so it keeps
   * its place and can be dragged while it is off, and a summary-only row
   * (D17-9) is shown and is in no column list at all. What else has no
   * place is a field the definition offers that the config has never
   * mentioned — it joins the end of the list when it is switched on, and
   * until then there is nothing to drag it between.
   */
  placed: boolean;
  /** Whether the config holds this column against the left edge. */
  pinned: boolean;
  /**
   * The row key — the one column whose place is decided for the user rather
   * than by them, and so the one row whose checkbox and pin toggle are
   * shown in the state they are in and disabled: a row without the key is a
   * row nobody can read (D13), and the key leads the held area for the same
   * reason. A control that silently does nothing is worse than one that
   * says it cannot.
   */
  primary: boolean;
  /**
   * Summary functions this column really offers: what the field declares,
   * less what its values cannot answer (`summaryFunctionsOf`). The select
   * has to stop offering exactly where admission starts refusing, or a pick
   * is taken here and then blocks the query and the save.
   */
  functions: readonly SummaryFunction[];
  /**
   * How this column reads its cells — the field's own `cell`, else its
   * kind's. It is what names the functions in the column's vocabulary: the
   * earliest of a date column, the smallest of a number one
   * (`summaryFunctionKey`). Absent on a row that is not a column at all.
   */
  cell?: string;
  /** The function the config summarises this column with, if any. */
  summary: SummaryFunction | null;
  /** Whether this row may be dragged or moved with the arrow keys. */
  movable: boolean;
  /**
   * True for a column the definition no longer offers — a field it dropped,
   * or one whose kind is a handle rather than something a row holds.
   *
   * It is listed precisely because it is broken: `validateRecord` refuses
   * the config over it, which blocks the query and the save, and a row that
   * is not in the list is a column nobody can take out. It carries no
   * controls but its checkbox, which is the repair.
   */
  broken: boolean;
  /**
   * True for a broken row that is not a column at all: nothing but
   * `config.summaries` names the field (D17-9).
   *
   * It is `visible` because the setting it stands for is in force, but it is
   * in no table order and in no column list — unticking it takes the summary
   * out and touches no column.
   */
  summaryOnly: boolean;
}

export interface ColumnSettingInput {
  /** The definition's fields, in its order. */
  fields: readonly FieldDefinition[];
  /** The draft's columns, in the order the table shows them. */
  columns: readonly string[];
  /** The field holding each row's identity, when the definition declares one. */
  rowKey?: string;
  /** Every field `config.summaries` names, in the order it names them. */
  summaryFields: readonly string[];
  pinnedOf(field: string): boolean;
  /** Whether the config has this column switched off. */
  hiddenOf(field: string): boolean;
  summaryOf(field: string): SummaryFunction | null;
}

/**
 * Every column the settings can offer, in the order they are listed.
 *
 * The config's columns come first, in the order the table lays them out,
 * **switched off ones among them**: a column the user hid keeps its entry
 * and therefore its place, so it is listed where it sits and dragged like
 * any other, and switching it back on shows it there rather than at the
 * end. After them come the summaries that are not columns at all (D17-9),
 * and then the fields that could be columns and are not — neither has a
 * place in `table.columns`, so neither has an order to drag, and a field
 * joins the end of the list when it is switched on. Field-less kinds (a
 * search box, a tenant handle) are left out entirely: their name addresses an editor, not
 * something a row holds, so a column on one would be empty for every record
 * ever shown.
 */
export function columnSettingRows(
  input: ColumnSettingInput,
): ColumnSettingRow[] {
  const candidates = input.fields.filter(field => !isFieldlessKind(field.kind));
  const byName = new Map(candidates.map(field => [field.name, field]));
  const seen = new Set<string>();
  // In the order the table shows them, and one row per column: a config
  // that lists a field twice is two columns claiming one identity, and one
  // checkbox takes both of them out.
  const placed = input.columns.flatMap(name => {
    if (seen.has(name)) return [];
    seen.add(name);
    const field = byName.get(name);
    return [field ? row(field, true, input) : broken(name, input)];
  });
  // A summary may name a field that is neither a column nor something the
  // definition still declares. `validateSummaries` refuses the config over
  // it, which blocks the query and the save, and until this row existed
  // nothing on screen could take it back: the settings list columns, and
  // this is not one (D17-9). Listed next to the broken columns, because it
  // is the same kind of leftover and wears the same repair.
  const orphans = [...new Set(input.summaryFields)]
    .filter(name => !seen.has(name) && !byName.has(name))
    .map(name => broken(name, input, true));
  const unplaced = candidates
    .filter(field => !seen.has(field.name))
    .map(field => row(field, false, input));

  // The host's action column is **not** listed. It is a render slot rather
  // than a column the config names, it is always last and always held
  // against the right edge (D13), and none of that is part of "how do I
  // want to look at this" (D19) — so its row carried four controls nobody
  // could press, which is noise rather than an explanation.
  return [...placed, ...orphans, ...unplaced];
}

/**
 * A column the definition no longer offers, listed so it can be taken out.
 *
 * There is no label to show — the field is gone — so it wears its own name,
 * and it carries no control but its checkbox: ordering, pinning and
 * summarising a column that cannot render are all answers to a question
 * nobody asked. Hiding it is the repair, and `setColumns` takes its summary
 * with it.
 *
 * `summaryOnly` is the same row for a field that is not a column either —
 * only a summary names it — where the checkbox removes that summary alone.
 */
function broken(
  field: string,
  input: ColumnSettingInput,
  summaryOnly = false,
): ColumnSettingRow {
  return {
    field,
    label: field,
    region: 'scrolling',
    primary: false,
    // A broken column can be switched off like any other — and unlike any
    // other, switching it off takes it out of the config rather than hiding
    // it, because a column the definition dropped has nowhere to come back
    // to. A summary-only row is not a column at all, so it is shown because
    // the setting it stands for is in force, and it is in no column order:
    // `placed` is what keeps it out of the one the panel writes back.
    visible: summaryOnly || !input.hiddenOf(field),
    placed: !summaryOnly,
    pinned: false,
    functions: [],
    summary: input.summaryOf(field),
    movable: false,
    broken: true,
    summaryOnly,
  };
}

function row(
  field: FieldDefinition,
  placed: boolean,
  input: ColumnSettingInput,
): ColumnSettingRow {
  const visible = placed && !input.hiddenOf(field.name);
  const primary = field.name === input.rowKey;
  // The key shows the pinning it is held by rather than what the config
  // happens to say, so the two never disagree on screen. The rest is read
  // through `columnPinned` even though the controller already normalises
  // it: the area a row is listed in is computed from this, and the panel is
  // the second line of defence for a config that stored `pinned: 'left'`.
  //
  // A field with no place in the config is held nowhere, the key included:
  // the table is not drawing it at all, so a pin toggle reading "pinned"
  // over a row listed among the scrolling ones would be two answers to one
  // question.
  const pinned =
    placed && (primary || columnPinned(input.pinnedOf(field.name)));
  return {
    field: field.name,
    label: field.label,
    // An area is a pinning, and a column switched off keeps the one it
    // had: hiding a column clears nothing, so it is listed in the area it
    // will come back to rather than falling in among the scrolling ones and
    // then jumping sideways the moment it is switched on again. A field with
    // no place in the config has no pinning either, so it is listed among
    // the scrolling ones, which is where it joins the table.
    region: pinned ? 'pinned' : 'scrolling',
    visible,
    placed,
    pinned,
    primary,
    functions: summaryFunctionsOf(field),
    cell: field.cell ?? field.kind,
    summary: input.summaryOf(field.name),
    // A place in the order is what there is to drag, and a switched-off
    // column has one — which is the whole of "a hidden field cannot be
    // ordered" going away.
    movable: placed && !primary,
    broken: false,
    summaryOnly: false,
  };
}

/** The rows of one area, in the order they are shown. */
export function regionRows(
  rows: readonly ColumnSettingRow[],
  region: ColumnRegion,
): ColumnSettingRow[] {
  return rows.filter(entry => entry.region === region);
}

/** The fields that may be dragged in one area, in their current order. */
export function movableFields(
  rows: readonly ColumnSettingRow[],
  region?: ColumnRegion,
): string[] {
  return rows
    .filter(
      entry =>
        entry.movable && (region === undefined || entry.region === region),
    )
    .map(entry => entry.field);
}

/** The area a field is listed in, or `null` when it is not listed. */
export function regionOf(
  rows: readonly ColumnSettingRow[],
  field: string,
): ColumnRegion | null {
  return rows.find(entry => entry.field === field)?.region ?? null;
}

/** The columns the table actually draws, in the order it draws them. */
function rendered(rows: readonly ColumnSettingRow[]): ColumnSettingRow[] {
  return rows.filter(entry => entry.visible && !entry.broken);
}

/**
 * How many columns the table draws, which is what a position is counted
 * against. A broken column is not one of them — `projectRecord` leaves it
 * out — and neither is the host's action column, which is a render slot
 * this list does not carry a row for (D19).
 */
export function renderedCount(rows: readonly ColumnSettingRow[]): number {
  return rendered(rows).length;
}

/** Where a field sits among the columns the table draws, counted from 1. */
export function renderedIndex(
  rows: readonly ColumnSettingRow[],
  field: string,
): number {
  return rendered(rows).findIndex(entry => entry.field === field) + 1;
}

/**
 * The whole table's column order after one move, or `null` when the move
 * changes nothing — the end of an area, an unknown field, a drop on the row
 * it started from.
 *
 * The move happens inside the row's own area — a column joins the other one
 * by being pinned, never by being carried there (`columnDrop`) — and the
 * answer covers every column the config knows, the switched-off ones among
 * them, with the areas in the order the table draws them. That is the order
 * `projectRecord` lays out, so what is saved and what is drawn are the same
 * list rather than two that agree by luck, and a hidden column comes out of
 * it with the place it will come back to. A row that cannot be dragged —
 * the row key, a broken column — keeps its slot while the movable ones move
 * around it.
 */
export function reorderColumns(
  rows: readonly ColumnSettingRow[],
  field: string,
  toIndex: number,
): string[] | null {
  const region = regionOf(rows, field);
  if (region === null) return null;
  const movable = movableFields(rows, region);
  const from = movable.indexOf(field);
  if (from < 0 || toIndex < 0 || toIndex >= movable.length || toIndex === from)
    return null;
  const rest = movable.filter((_name, index) => index !== from);
  const moved = [...rest.slice(0, toIndex), field, ...rest.slice(toIndex)];
  let at = 0;
  return REGIONS.flatMap(area => {
    const placed = placedOf(rows, area);
    return area === region
      ? placed.map(name => (movable.includes(name) ? moved[at++] : name))
      : placed;
  });
}

/**
 * The config columns of one area, in the order they are listed — the
 * switched-off ones among them, because a hidden column has a place in
 * `table.columns` and the order written back has to keep it there.
 *
 * What is *not* one of them is a row that is in no column list: a
 * summary-only row (D17-9) and a field the config has never mentioned are
 * both listed and neither is placed, and letting either into an order that
 * is written straight back would turn it into a column the user never
 * added.
 */
function placedOf(
  rows: readonly ColumnSettingRow[],
  region: ColumnRegion,
): string[] {
  return rows
    .filter(entry => entry.region === region && entry.placed)
    .map(entry => entry.field);
}

/** Where a field sits among the movable rows of its own area, or -1. */
export function movableIndex(
  rows: readonly ColumnSettingRow[],
  field: string,
): number {
  const region = regionOf(rows, field);
  return region === null ? -1 : movableFields(rows, region).indexOf(field);
}
