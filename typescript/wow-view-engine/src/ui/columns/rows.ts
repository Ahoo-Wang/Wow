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
  columnPin,
  isFieldlessKind,
  type FieldDefinition,
  type SummaryFunction,
  type RecordColumnPin,
} from '../../model/index.js';
import { pinnedEnd, type ColumnPlacement } from '../../record/index.js';

/**
 * The three areas a table draws its columns in, and the settings list them
 * in: what is held on the left, what scrolls, what is held on the right.
 *
 * An area *is* the pinning. `projectRecord` lays the columns out this way,
 * because `sticky` fixes an element where it already is — a column pinned
 * right that is drawn in the middle scrolls away like any other, and the
 * pinning is not a promise a stylesheet can keep on its own. A row moves
 * between areas by being pinned, and is dragged only inside the one it is
 * in: an order that crossed an area would be written to the config and then
 * laid out differently by the table.
 */
export type ColumnRegion = 'left' | 'middle' | 'right';

/** The areas in the order a table draws them. */
export const REGIONS: readonly ColumnRegion[] = ['left', 'middle', 'right'];

/**
 * The action column's stand-in.
 *
 * It is not a field and it is not in the config — the host hands over a
 * render function — but it is a column on screen, so the settings show where
 * it sits rather than pretending the table ends at the last field. `\0`
 * cannot occur in a field name (`FIELD_NAME_PATTERN`), so the sentinel can
 * never collide with one.
 */
export const ACTIONS_ROW = '\u0000actions';

/** One line of the column settings, with everything its controls need. */
export interface ColumnSettingRow {
  /** The field's name, or {@link ACTIONS_ROW} for the row that stands in for the action column. */
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
  pinned: RecordColumnPin | null;
  /**
   * True for the columns whose place is decided for the user rather than by
   * them: the row key, the column the table draws last, and the action
   * column. Their controls are shown in the state they are in and disabled,
   * because a control that silently does nothing is worse than one that says
   * it cannot.
   */
  fixed: boolean;
  /**
   * The row key. It is `fixed` too, but it is the one column the settings
   * never offer to hide: a row without it is a row nobody can read (D13).
   */
  primary: boolean;
  /** Summary functions the field declares; empty when it offers none. */
  functions: readonly SummaryFunction[];
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
  /** Whether the table carries the host's action column. */
  actions: boolean;
  /** Every field `config.summaries` names, in the order it names them. */
  summaryFields: readonly string[];
  pinnedOf(field: string): RecordColumnPin | null;
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
  // The column the table holds against its right edge, found the way the
  // projection finds it so the panel shows the pinning the table will
  // actually draw. Over the columns that can be rendered, de-duplicated,
  // which is exactly the list `projectRecord` lays out.
  const end = lastColumn(input, byName);
  const seen = new Set<string>();
  // In the order the table shows them, and one row per column: a config
  // that lists a field twice is two columns claiming one identity, and one
  // checkbox takes both of them out.
  const placed = input.columns.flatMap(name => {
    if (seen.has(name)) return [];
    seen.add(name);
    const field = byName.get(name);
    return [field ? row(field, true, input, end) : broken(name, input)];
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
    .map(field => row(field, false, input, end));

  const rows = [...placed, ...orphans, ...unplaced];
  if (!input.actions) return rows;
  return [
    ...rows,
    {
      field: ACTIONS_ROW,
      label: '',
      region: 'right',
      visible: true,
      placed: false,
      pinned: 'right',
      fixed: true,
      primary: false,
      functions: [],
      summary: null,
      movable: false,
      broken: false,
      summaryOnly: false,
    },
  ];
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
    region: 'middle',
    primary: false,
    // A broken column can be switched off like any other — and unlike any
    // other, switching it off takes it out of the config rather than hiding
    // it, because a column the definition dropped has nowhere to come back
    // to. A summary-only row is not a column at all, so it is shown because
    // the setting it stands for is in force, and it is in no column order:
    // `placed` is what keeps it out of the one the panel writes back.
    visible: summaryOnly || !input.hiddenOf(field),
    placed: !summaryOnly,
    pinned: null,
    fixed: false,
    functions: [],
    summary: input.summaryOf(field),
    movable: false,
    broken: true,
    summaryOnly,
  };
}

/**
 * The column the table draws last, which the projection holds on the right
 * whatever the config says.
 *
 * It is computed over the same list `projectRecord` lays out — the drawn
 * columns, each without a duplicate, the ones the definition no longer
 * offers and the ones the config switched off left out — so the panel and
 * the table can never name a different column as the last one. D13's "last
 * drawn column" is the last **visible** one: a hidden column is drawn
 * nowhere, so it is never the end the table holds.
 */
function lastColumn(
  input: ColumnSettingInput,
  byName: ReadonlyMap<string, FieldDefinition>,
): string | null {
  // With a row-action column the host's slot is the end, and it is listed
  // as fixed on its own row; no data column is held for being last then.
  if (input.actions) return null;
  const seen = new Set<string>();
  const drawn = input.columns.flatMap(name => {
    if (seen.has(name) || !byName.has(name) || input.hiddenOf(name)) return [];
    seen.add(name);
    return [
      {
        field: name,
        pinned:
          name === input.rowKey ? 'left' : columnPin(input.pinnedOf(name)),
      } satisfies ColumnPlacement,
    ];
  });
  return pinnedEnd(drawn, input.rowKey ?? '');
}

function row(
  field: FieldDefinition,
  placed: boolean,
  input: ColumnSettingInput,
  end: string | null,
): ColumnSettingRow {
  const visible = placed && !input.hiddenOf(field.name);
  const fixed = field.name === input.rowKey || field.name === end;
  // A fixed column shows the side it is held on rather than what the config
  // happens to say, so the two never disagree on screen. Read through
  // `columnPin` even though the controller already normalises it: the area
  // a row is listed in is computed from this, and an area that is neither
  // of the three is a row that appears nowhere at all.
  const pinned = fixed
    ? field.name === input.rowKey
      ? 'left'
      : 'right'
    : columnPin(input.pinnedOf(field.name));
  return {
    field: field.name,
    label: field.label,
    // An area is a pinning, and a column switched off keeps the one it
    // had: hiding a column clears nothing, so it is listed in the area it
    // will come back to rather than falling into the middle and then
    // jumping sideways the moment it is switched on again. A field with no
    // place in the config has no pinning either, so it is listed in the
    // middle, which is where it joins the table.
    region: placed ? (pinned ?? 'middle') : 'middle',
    visible,
    placed,
    pinned,
    fixed,
    primary: field.name === input.rowKey,
    functions: field.summary ?? [],
    summary: input.summaryOf(field.name),
    // A place in the order is what there is to drag, and a switched-off
    // column has one — which is the whole of "a hidden field cannot be
    // ordered" going away.
    movable: placed && !fixed,
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
 * out — while the host's action column is.
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
 * The move happens inside the row's own area, and the answer covers every
 * column the config knows — the switched-off ones among them — with the
 * areas in the order the table draws them. That is the order
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
    .filter(
      entry =>
        entry.region === region && entry.placed && entry.field !== ACTIONS_ROW,
    )
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

/** The pin state after one press: unpinned, then left, then right again. */
export function nextPin(
  pinned: RecordColumnPin | null,
): RecordColumnPin | null {
  if (pinned === null) return 'left';
  return pinned === 'left' ? 'right' : null;
}
