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

import type { CursorPage, PagedList } from '@ahoo-wang/wow-client';
import { readPath } from './path.js';
import {
  columnHidden,
  epochUnitOf,
  columnPinned,
  isDateCell,
  isFieldlessKind,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type NumberFormat,
  type RecordCardSpec,
  type RecordColumn,
  type RecordData,
  type RecordKey,
  type RecordViewConfig,
  type SummaryFunction,
  type EpochTimeUnit,
} from '../model/index.js';
import { readInstant } from '../filter/index.js';
import { summaryAlias } from './compile.js';
import { cursorPaging, pagedPaging, type RecordPaging } from './paging.js';

/**
 * Which edge the table holds a column against.
 *
 * It is the layout's answer and never the config's: a user pins a column to
 * the left or not at all (D19), and the right edge belongs to the table's
 * own frame — the column drawn last, or the host's action column where
 * there is one, which the projection cannot see (`ui/record/columns.ts`).
 */
export type ColumnEdge = 'left' | 'right';

/** A column as the table should render it, with its semantics resolved. */
export interface RecordColumnView {
  field: string;
  label: string;
  kind: string;
  /** Renderer key; the kind's default when the field names none. */
  cell: string;
  width?: number;
  /**
   * Which edge holds this column, when one does. `'right'` is the column
   * the layout holds because it is drawn **last** (D13) and never a pin the
   * config asked for — a host that puts a row-action column after it takes
   * that place, and the table lets this one go (`ui/record/columns.ts`).
   */
  pinned?: ColumnEdge;
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
  /** For an array of objects, what each element is read by. */
  elementTitle?: ElementTitleView;
  /** A time kept in epoch seconds; milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
}

/**
 * The element field an array of objects is read by, one element at a time
 * (`FieldDefinition.elementTitle`), resolved to what reading it takes: where
 * it sits within an element, and how it reads.
 *
 * It rides on the column rather than being looked up by the renderer because
 * the renderer holds a result and not a definition — the same reason the
 * column carries its options.
 */
export interface ElementTitleView {
  /** Its name within one element, e.g. `name` in `body[].name`. */
  name: string;
  kind: string;
  /** Renderer key; the kind's default when the field names none. */
  cell: string;
  options?: readonly FieldOption[];
  numberFormat?: NumberFormat;
}

/**
 * The title an array of objects declares, as a cell reads it, or `undefined`
 * when it declares none — admission has already refused a name its elements
 * do not declare, so a missing one here is a definition that chose to count.
 */
export function elementTitleView(
  field: FieldDefinition,
): ElementTitleView | undefined {
  if (field.elementTitle === undefined) return undefined;
  const title = field.elements?.find(
    element => element.name === field.elementTitle,
  );
  if (!title) return undefined;
  return {
    name: title.name,
    kind: title.kind,
    cell: title.cell ?? title.kind,
    ...(title.options ? { options: title.options } : {}),
    ...(title.numberFormat ? { numberFormat: title.numberFormat } : {}),
  };
}

export interface RecordRow {
  /** Value of the definition's row key. */
  key: RecordKey;
  data: RecordData;
}

/**
 * One field of a card. It carries what a column carries about how a value
 * reads — a card is a row folded out, and `RecordCards` hands a host's
 * `renderCell` the same `column` for a value on a card as the table does
 * for the same value — with the parts a card never has (pinning, width,
 * sorting) left out. `kind` and `cell` are optional for a controller built
 * by hand; the projection always names them.
 */
export interface RecordCardField {
  field: string;
  label: string;
  kind?: string;
  cell?: string;
  options?: readonly FieldOption[];
  numberFormat?: NumberFormat;
  /** For an array of objects, what each element is read by. */
  elementTitle?: ElementTitleView;
  /** A time kept in epoch seconds; milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
  /**
   * For an array of objects that declares its elements: each element field
   * that holds a value, read the way this one is, `field` being its name
   * within one element. Only the record detail lays an element out field by
   * field; a cell and a card read an element by its title.
   */
  elements?: RecordCardField[];
}

/**
 * The card layout of the result, resolved against the definition.
 *
 * A card is not a narrow table: the saved config says which field titles it,
 * which fields make up its body and where its image comes from, and none of
 * that is derivable from the table's columns.
 */
export interface RecordCardView {
  /** Field whose value titles each card; the row key when it holds none. */
  title: string;
  /** How the title's values show, when the definition still has the field. */
  titleField?: RecordCardField;
  /** Fields of the card body, in order, with their labels resolved. */
  fields: RecordCardField[];
  /** Field holding an image URL, when the config asks for one. */
  image?: string;
  /** How many cards stand in one row; see `RecordCardSpec.perRow`. */
  perRow?: 1 | 2 | 3 | 4;
}

export interface RecordView {
  columns: RecordColumnView[];
  /** The same result's card layout; both halves are saved side by side. */
  card: RecordCardView;
  rows: RecordRow[];
  paging: RecordPaging;
}

/**
 * A declared field as a value of it reads outside a table — on a card, and
 * in the detail of one record (`detailSections`), which is a card holding
 * every field.
 */
export function cardField(field: FieldDefinition): RecordCardField {
  return {
    field: field.name,
    label: field.label,
    kind: field.kind,
    cell: field.cell ?? field.kind,
    ...(field.options ? { options: field.options } : {}),
    ...(field.numberFormat ? { numberFormat: field.numberFormat } : {}),
    ...elementTitleOf(field),
    ...elementsOf(field),
    ...timeUnitOf(field),
  };
}

function elementsOf(field: FieldDefinition): { elements?: RecordCardField[] } {
  const elements = (field.elements ?? []).filter(
    element => !isFieldlessKind(element.kind),
  );
  return elements.length > 0 ? { elements: elements.map(cardField) } : {};
}

/**
 * The card half of the config against the definition's fields — the same
 * field facts a column reads, read in the same place. A field the definition
 * dropped is left out rather than shown as a blank row; `validateRecord`
 * reports it separately.
 */
function cardView(
  spec: RecordCardSpec,
  byName: ReadonlyMap<string, FieldDefinition>,
): RecordCardView {
  const titleField = byName.get(spec.title);
  return {
    title: spec.title,
    ...(titleField ? { titleField: cardField(titleField) } : {}),
    fields: spec.fields.flatMap(name => {
      const field = byName.get(name);
      return field ? [cardField(field)] : [];
    }),
    ...(spec.image === undefined ? {} : { image: spec.image }),
    ...(spec.perRow === undefined ? {} : { perRow: spec.perRow }),
  };
}

/**
 * One column as the table renders it, with the edge it is held against
 * already decided by {@link columnOrder}.
 */
function columnView(
  field: FieldDefinition,
  column: RecordColumn,
  place: { pinned?: ColumnEdge; primary: boolean },
): RecordColumnView {
  return {
    field: field.name,
    label: field.label,
    kind: field.kind,
    cell: field.cell ?? field.kind,
    width: column.width,
    ...(place.pinned ? { pinned: place.pinned } : {}),
    ...(place.primary ? { primary: true } : {}),
    sortable: field.sortable === true,
    numberFormat: field.numberFormat,
    ...(field.options ? { options: field.options } : {}),
    ...elementTitleOf(field),
    ...timeUnitOf(field),
  };
}

/** The epoch unit a time field counts in, where it is not milliseconds. */
function timeUnitOf(field: FieldDefinition): { timeUnit?: EpochTimeUnit } {
  const timeUnit = epochUnitOf(field);
  return timeUnit ? { timeUnit } : {};
}

function elementTitleOf(field: FieldDefinition): {
  elementTitle?: ElementTitleView;
} {
  const elementTitle = elementTitleView(field);
  return elementTitle ? { elementTitle } : {};
}

/** A column as the layout rule reads it: its field, and whether it is pinned. */
interface ColumnPlacement {
  field: string;
  /**
   * What the config asks for, read through `columnPinned` so a stored
   * `'left'` or `'top'` arrives as "not pinned" rather than as a pinning.
   * The row key's is the definition's rather than the config's.
   */
  pinned: boolean;
}

/**
 * The columns in the two areas a table draws them in: what is held against
 * the left edge, and what scrolls.
 *
 * `sticky` fixes an element where it already is, so a column pinned in the
 * middle of the table simply scrolls away like any other — the pinning is
 * not a promise a stylesheet can keep on its own. Laying the areas out is
 * therefore part of the same rule as pinning them, and the row key leads
 * the held area because it is the column that says which record a row is.
 * The order inside each area is the config's own; the sort is stable, so
 * nothing else moves.
 *
 * There is no area on the right, because there is no pinning on the right
 * (D19): the right edge holds the column that happens to be drawn last, and
 * a column pinned there by hand would have to be moved to the end first —
 * which is an order the user did not ask for.
 */
function columnOrder<T extends ColumnPlacement>(
  columns: readonly T[],
  rowKey: string,
): T[] {
  const area = (column: T): number =>
    column.field === rowKey ? 0 : column.pinned ? 1 : 2;
  return [...columns].sort((left, right) => area(left) - area(right));
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
  // Unknown fields and switched-off ones drop out before the layout runs, so
  // the column the areas end on is the one really drawn last — which is the
  // one the right edge holds, whatever the config asked for. A hidden column
  // keeps its entry in the config, and therefore its place in the order, but
  // the table never draws it and the export never writes it.
  const placed = columnOrder(
    config.table.columns.flatMap(column => {
      const field = columnHidden(column.hidden)
        ? undefined
        : byName.get(column.field);
      return field
        ? [
            {
              field: field.name,
              pinned:
                field.name === rowKey ? true : columnPinned(column.pinned),
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
  const columns = placed.map(place =>
    // The right edge holds the column drawn **last** (D13), and that is the
    // only pinning there is on that side (D19) — so a `'right'` here always
    // means "because it is the end", and the table hands the place over to
    // a host's action column when there is one (`ui/record/columns.ts`).
    // A table whose every column is pinned has no last unheld column and
    // needs none: nothing scrolls out from under a frame.
    columnView(place.definition, place.column, {
      ...(place.pinned
        ? { pinned: 'left' as const }
        : place === end
          ? { pinned: 'right' as const }
          : {}),
      primary: place.field === rowKey,
    }),
  );

  const rows = page.list.map(data => ({
    key: readPath(data, rowKey) as RecordKey,
    data,
  }));

  return {
    columns,
    card: cardView(config.card, byName),
    rows,
    // The size is the one this config ran at: the pager divides by it, and
    // the draft's may already be another one on its way.
    paging: isCursorPage(page)
      ? cursorPaging(page.nextCursor)
      : pagedPaging({
          index: pageIndex,
          size: config.pageSize,
          total: page.total,
          maxWindow: definition.record?.maxWindow,
        }),
  };
}

export interface SummaryCell {
  field: string;
  label: string;
  fn: SummaryFunction;
  /** `null` when the source returned nothing for this cell. */
  value: number | string | null;
  numberFormat?: NumberFormat;
  /** The epoch unit of the moment it holds, with `cell`; see `epochUnitOf`. */
  timeUnit?: EpochTimeUnit;
  /**
   * How the value reads, when the column does not read it as a number: the
   * renderer key of the column it stands under.
   *
   * Set on the earliest and the latest of a column of moments, which are two
   * of that column's own cells rather than numbers about it — so the footer
   * formats them the way the column formats that cell, in the surface's
   * language and zone, and a day written `2026-09-18` is not shown as the
   * 17th somewhere else. `COUNT` over the same column is a number of rows
   * like any other and names no reading; the maths is refused there by
   * admission. So a cell that names no reading is a number.
   */
  cell?: string;
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
 * `readPath` would turn the first into the second.
 */
export function recordValue(data: RecordData, field: string): unknown {
  let value: unknown = data;
  for (const segment of field.split('.')) {
    if (value === null || typeof value !== 'object') return undefined;
    value = (value as Record<string, unknown>)[segment];
  }
  return value;
}

/**
 * How a summary of this field reads, for this function — the renderer key
 * when it is not a number, and nothing when it is.
 *
 * Only `MIN` and `MAX` of a column of moments are dates: they are the
 * earliest and the latest of that column's own cells. A `COUNT` over the
 * same column counts rows, and `SUM` and `AVG` are not admitted there at
 * all ({@link summaryFunctionsOf}).
 */
function summaryReading(
  field: FieldDefinition | undefined,
  fn: SummaryFunction,
): string | undefined {
  if (!field || (fn !== 'MIN' && fn !== 'MAX')) return undefined;
  const cell = field.cell ?? field.kind;
  return isDateCell(cell) ? cell : undefined;
}

/**
 * The earliest or the latest of a column of moments, as the record holds it.
 *
 * The ordering is over instants, read by the date kind's own reader, so a
 * wall-clock day, an ISO instant and an epoch number all sort where they
 * belong instead of being compared as text. What comes back is the winning
 * row's own value and not the number the comparison ran on: the footer
 * formats it the way the column formats that cell, and the day
 * `2026-09-18` would otherwise arrive there as an instant and be shown as
 * the 17th on a clock behind UTC.
 */
function reduceInstants(
  rows: readonly RecordData[],
  field: string,
  fn: 'MIN' | 'MAX',
): number | string | null {
  let best: { at: number; value: number | string } | undefined;
  for (const row of rows) {
    const value = recordValue(row, field);
    // A moment arrives as a string or as a number; anything else is not one
    // and is left out exactly as a non-number is left out of the maths.
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    const instant = readInstant(value);
    if (!instant) continue;
    if (!best || (fn === 'MIN' ? instant.ms < best.at : instant.ms > best.at))
      best = { at: instant.ms, value };
  }
  return best?.value ?? null;
}

function reduceRows(
  rows: readonly RecordData[],
  field: string,
  fn: SummaryFunction,
  reading?: string,
): number | string | null {
  if (fn === 'COUNT') return rows.length;
  if (reading !== undefined && (fn === 'MIN' || fn === 'MAX'))
    return reduceInstants(rows, field, fn);
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
    const reading = summaryReading(field, summary.fn);
    const value =
      source.scope === 'page'
        ? reduceRows(source.rows, summary.field, summary.fn, reading)
        : readAggregated(row, summaryAlias(summary.field, summary.fn), reading);
    return {
      field: summary.field,
      label: field?.label ?? summary.field,
      fn: summary.fn,
      value,
      numberFormat: field?.numberFormat,
      ...(reading === undefined
        ? {}
        : { cell: reading, ...(field ? timeUnitOf(field) : {}) }),
    };
  });

  return { scope: source.scope, cells };
}

/**
 * One cell of the aggregation's answer, read back by its alias.
 *
 * A date's earliest or latest comes back the way its source keeps it — an
 * ISO instant from one, epoch milliseconds from another — so it is taken as
 * it comes, once the date kind's reader confirms it is a moment at all, and
 * shown by the column's own reading. Everything else is a number or it is
 * nothing: a cell the source could not answer has to read as missing rather
 * than as zero.
 */
function readAggregated(
  row: RecordData | undefined,
  alias: string,
  reading?: string,
): number | string | null {
  const value = row?.[alias];
  if (reading !== undefined)
    return (typeof value === 'string' || typeof value === 'number') &&
      readInstant(value) !== undefined
      ? value
      : null;
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
      // The reading travels on the cell, so this scope reduces a date column
      // to a date exactly as the projection did — two scopes reading one
      // column two ways is the drift these two functions sit together to
      // prevent.
      value: reduceRows(data, cell.field, cell.fn, cell.cell),
    })),
  };
}
