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
  getPropertyValue,
  type CursorPage,
  type PagedList,
} from '@ahoo-wang/fetcher-wow';
import {
  columnPin,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type NumberFormat,
  type RecordColumn,
  type RecordColumnPin,
  type RecordData,
  type RecordKey,
  type RecordViewConfig,
  type SummaryFunction,
} from '../model/index.js';
import { summaryAlias } from './compile.js';

/** A column as the table should render it, with its semantics resolved. */
export interface RecordColumnView {
  field: string;
  label: string;
  kind: string;
  /** Renderer key; the kind's default when the field names none. */
  cell: string;
  width?: number;
  pinned?: RecordColumnPin;
  /**
   * True on the column the layout holds against the right edge because it
   * is drawn last (D13) — not because the config pinned it. A host that
   * puts a row-action column after it takes that place, and the table lets
   * this one go (`ui/record/columns.ts`).
   */
  end?: true;
  /**
   * True on the definition's row key: the column that says which record a
   * row is, which is why it leads the left area and is pinned there
   * whatever the config asks.
   *
   * It is on the projected column rather than left to the renderer to work
   * out, because the renderer holds a result and not a definition. The pin
   * cap is the one that has to know (D17-4): every other pin can be let go
   * on a narrow screen, and this one cannot.
   */
  primary?: true;
  sortable: boolean;
  numberFormat?: NumberFormat;
  /** An enum's choices, so a cell can show a value by its label. */
  options?: readonly FieldOption[];
}

export interface RecordRow {
  /** Value of the definition's row key. */
  key: RecordKey;
  data: RecordData;
}

export type RecordPaging =
  | { mode: 'paged'; index: number; total?: number }
  | { mode: 'cursor'; nextCursor: string | null };

export interface RecordView {
  columns: RecordColumnView[];
  rows: RecordRow[];
  paging: RecordPaging;
}

/**
 * One column as the table renders it, with the side it is held on already
 * decided by {@link columnOrder} and {@link pinnedEnd}.
 */
function columnView(
  field: FieldDefinition,
  column: RecordColumn,
  place: { pinned: RecordColumnPin | null; end: boolean; primary: boolean },
): RecordColumnView {
  return {
    field: field.name,
    label: field.label,
    kind: field.kind,
    cell: field.cell ?? field.kind,
    width: column.width,
    pinned: place.pinned ?? undefined,
    ...(place.end ? { end: true } : {}),
    ...(place.primary ? { primary: true } : {}),
    sortable: field.sortable === true,
    numberFormat: field.numberFormat,
    ...(field.options ? { options: field.options } : {}),
  };
}

/** A column as the layout rule reads it: its field, and the side it asks for. */
export interface ColumnPlacement {
  field: string;
  /**
   * What the config asks for, read through `columnPin` so a stored `'top'`
   * arrives as "not pinned" rather than as a side. The row key's `'left'` is
   * the definition's rather than the config's.
   */
  pinned: RecordColumnPin | null;
}

/**
 * The columns in the three areas a table draws them in: what is held on the
 * left, what scrolls, what is held on the right.
 *
 * `sticky` fixes an element where it already is, so a column pinned right
 * that is drawn in the middle simply scrolls away like any other — the
 * pinning is not a promise a stylesheet can keep on its own. Laying the
 * areas out is therefore part of the same rule as pinning them, and the row
 * key leads the left area because it is the column that says which record a
 * row is. The order inside each area is the config's own; the sort is
 * stable, so nothing else moves.
 *
 * It is exported because two other places have to answer "which column does
 * the table draw last" the same way this one does: `validateRecord`, which
 * must not report a pinning that column never gets to keep, and the column
 * settings, which show that pinning fixed.
 */
export function columnOrder<T extends ColumnPlacement>(
  columns: readonly T[],
  rowKey: string,
): T[] {
  const area = (column: T): number =>
    column.field === rowKey
      ? 0
      : column.pinned === 'left'
        ? 1
        : column.pinned === 'right'
          ? 3
          : 2;
  return [...columns].sort((left, right) => area(left) - area(right));
}

/**
 * The column held against the right edge whatever the config says (D13), or
 * `null` when the table draws nothing but the row key.
 *
 * Both ends of a record table are fixed: the row key on the left, because it
 * says which record a row is, and the last column on the right, because a
 * table whose ends drift is a table with no frame. The edge on those two is
 * what a reader sees the frame by, and it is drawn at rest rather than only
 * while something scrolls under it.
 *
 * `columns` is what the table really draws — a field the definition dropped
 * is not one of them — so the answer is the last column on screen and not
 * the last entry of a config that may name columns nobody can render. The
 * host's row-action column is outside this: it is a render slot rather than
 * a projected column, and it is held on the right by `ui/record/columns.ts`
 * already, sitting beyond whichever column this names.
 */
export function pinnedEnd(
  columns: readonly ColumnPlacement[],
  rowKey: string,
): string | null {
  const ordered = columnOrder(columns, rowKey);
  const last = ordered[ordered.length - 1];
  return last === undefined || last.field === rowKey ? null : last.field;
}

function isCursorPage(
  page: PagedList<RecordData> | CursorPage<RecordData>,
): page is CursorPage<RecordData> {
  return 'nextCursor' in page;
}

/**
 * Turns a page of rows into what a table or a card list renders. Both paging
 * modes arrive here, and the mode stays visible in the result so the UI knows
 * whether it has a page number or a cursor to move on with.
 */
export function projectRecord(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  page: PagedList<RecordData> | CursorPage<RecordData>,
  pageIndex = 1,
): RecordView {
  const rowKey = definition.record?.rowKey;
  if (!rowKey)
    throw new Error(
      `Definition ${definition.id} declares no record capability`,
    );

  const byName = new Map(definition.fields.map(field => [field.name, field]));
  // Unknown fields drop out before the layout runs, so the column the areas
  // end on is the one really drawn last — which is the one the right edge
  // holds, whatever the config asked for.
  const placed = columnOrder(
    config.table.columns.flatMap(column => {
      const field = byName.get(column.field);
      return field
        ? [
            {
              field: field.name,
              pinned: field.name === rowKey ? 'left' : columnPin(column.pinned),
              column,
              definition: field,
            } satisfies ColumnPlacement & {
              column: RecordColumn;
              definition: FieldDefinition;
            },
          ]
        : [];
    }),
    rowKey,
  );
  const end = placed[placed.length - 1];
  const columns = placed.map(place => {
    const primary = place.field === rowKey;
    return columnView(place.definition, place.column, {
      pinned: primary ? 'left' : place === end ? 'right' : place.pinned,
      end: place === end && !primary,
      primary,
    });
  });

  const rows = page.list.map(data => ({
    key: getPropertyValue<RecordKey>(data, rowKey) as RecordKey,
    data,
  }));

  return {
    columns,
    rows,
    paging: isCursorPage(page)
      ? { mode: 'cursor', nextCursor: page.nextCursor }
      : { mode: 'paged', index: pageIndex, total: page.total },
  };
}

export interface SummaryCell {
  field: string;
  label: string;
  fn: SummaryFunction;
  /** Absent when the source returned nothing for this cell. */
  value: number | null;
  numberFormat?: NumberFormat;
}

export interface SummaryRow {
  /** `page` is computed from the visible rows; `total` from its own query. */
  scope: 'page' | 'total';
  cells: SummaryCell[];
}

/** Where the numbers come from; a page total never needs a round trip. */
export type SummarySource =
  | { scope: 'page'; rows: readonly RecordData[] }
  | { scope: 'total'; result: readonly RecordData[] };

/**
 * The value of `field` in a record. A field is a Wow query path, so
 * `state.status` is a value inside `state` rather than a key named with a dot:
 * a Wow snapshot keeps everything it materialises under `state`.
 *
 * A `null` the record holds comes back as `null`; only a path that is not
 * there is `undefined`. A cell renderer may tell the two apart, and
 * `getPropertyValue` would turn the first into the second.
 */
export function recordValue(data: RecordData, field: string): unknown {
  let value: unknown = data;
  for (const segment of field.split('.')) {
    if (value === null || typeof value !== 'object') return undefined;
    value = (value as Record<string, unknown>)[segment];
  }
  return value;
}

function reduceRows(
  rows: readonly RecordData[],
  field: string,
  fn: SummaryFunction,
): number | null {
  if (fn === 'COUNT') return rows.length;
  const numbers = rows
    .map(row => recordValue(row, field))
    .filter((value): value is number => typeof value === 'number');
  if (numbers.length === 0) return null;
  switch (fn) {
    case 'SUM':
      return numbers.reduce((total, value) => total + value, 0);
    case 'AVG':
      return (
        numbers.reduce((total, value) => total + value, 0) / numbers.length
      );
    case 'MIN':
      return Math.min(...numbers);
    case 'MAX':
      return Math.max(...numbers);
  }
}

/**
 * Page summaries are derived from the rows on screen; range summaries are read
 * back from the aggregation by alias. Mixing the two would misreport AVG, so
 * the scope stays part of the result.
 */
export function projectSummaries(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  source: SummarySource,
): SummaryRow {
  const byName = new Map(definition.fields.map(field => [field.name, field]));
  const row = source.scope === 'total' ? source.result[0] : undefined;

  const cells = (config.summaries ?? []).map(summary => {
    const field = byName.get(summary.field);
    const value =
      source.scope === 'page'
        ? reduceRows(source.rows, summary.field, summary.fn)
        : readNumber(row, summaryAlias(summary.field, summary.fn));
    return {
      field: summary.field,
      label: field?.label ?? summary.field,
      fn: summary.fn,
      value,
      numberFormat: field?.numberFormat,
    };
  });

  return { scope: source.scope, cells };
}

function readNumber(row: RecordData | undefined, alias: string): number | null {
  const value = row?.[alias];
  return typeof value === 'number' ? value : null;
}

/**
 * The same cells read over the rows on screen.
 *
 * A table shows both scopes at once — this page beside everything the
 * conditions match — and only the second one costs a query. The first is the
 * visible rows added up, so it is derived from the cells the executed config
 * already named rather than from the config itself, which a renderer holding
 * a result does not have. Reducing the rows a second time is the same
 * arithmetic `projectSummaries` does for a `page` source, kept here with it
 * so the two scopes can never drift apart.
 */
export function pageSummaries(
  cells: readonly SummaryCell[],
  rows: readonly RecordRow[],
): SummaryRow {
  const data = rows.map(row => row.data);
  return {
    scope: 'page',
    cells: cells.map(cell => ({
      ...cell,
      value: reduceRows(data, cell.field, cell.fn),
    })),
  };
}
