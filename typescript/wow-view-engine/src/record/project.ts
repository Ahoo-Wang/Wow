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

import type { CursorPage, PagedList } from '@ahoo-wang/wow-client/dsl';
import { readPath } from './path.js';
import {
  columnHidden,
  epochUnitOf,
  labelRemoteOf,
  readsAsNames,
  columnPinned,
  currencyPathOf,
  isFieldlessKind,
  numberFormatOf,
  DEFAULT_RUNTIME_LIMITS,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldNumeric,
  type FieldOption,
  type FieldTone,
  type NumberFormat,
  type RecordCardSpec,
  type RecordColumn,
  type RecordData,
  type RecordKey,
  type RecordPageTarget,
  type RecordViewConfig,
  type RuntimeLimits,
  type EpochTimeUnit,
} from '../model/index.js';
import {
  cursorPaging,
  pagedPaging,
  pageWindow,
  type RecordPaging,
} from './paging.js';
import type { SummaryRow } from './summaries.js';

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
  /**
   * What the number is by the source's semantics (`FieldNumeric`), where
   * that — and not the author's `numberFormat` — decides how it reads: a
   * file then holds the number itself, and money its currency beside it.
   */
  numeric?: FieldNumeric;
  /**
   * For money whose currency each record holds: where the row holds it
   * (`currencyPathOf`), so the value reads in its own currency.
   */
  currencyPath?: string;
  /** An enum's choices, so a cell can show a value by its label. */
  options?: readonly FieldOption[];
  /**
   * For a `reference` field: the remote its ids are read as names from,
   * so a cell reads `ViewRuntime.labels` rather than the id.
   */
  remote?: string;
  /**
   * True on a sortable `reference` read as names: the server sorts it by
   * the stored id, not the name a cell shows, so the header says so.
   * Derived from the field, never configured.
   */
  sortsById?: true;
  /** For an array of objects, what each element is read by. */
  elementTitle?: ElementTitleView;
  /** A time kept in epoch seconds; milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
  /**
   * How finely the cell writes a time of day, where the field asks for its
   * seconds (`FieldDefinition.timePrecision`); to the minute when unsaid.
   */
  timePrecision?: 'second';
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
  /**
   * For money whose currency each record holds: where the row holds it
   * (`currencyPathOf`), so the value reads in its own currency.
   */
  currencyPath?: string;
}

/**
 * The title an array of objects declares, as a cell reads it, or `undefined`
 * when it declares none — admission has already refused a name its elements
 * do not declare, so a missing one here is a definition that chose to count.
 */
function elementTitleView(
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
    ...numberFormatPart(title),
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
  /**
   * For a `reference` field: the remote its ids are read as names from,
   * so a cell reads `ViewRuntime.labels` rather than the id.
   */
  remote?: string;
  numberFormat?: NumberFormat;
  /**
   * For money whose currency each record holds: where the row holds it
   * (`currencyPathOf`), so the value reads in its own currency.
   */
  currencyPath?: string;
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

/**
 * One lane of a kanban result: one option of the lane field, or the
 * 「其他」lane of every record whose value is none of them.
 */
export interface RecordLaneView {
  /**
   * Which lane this is, for `loadLane`: `option:` and the option's value as
   * JSON (so `1` and `'1'` are two lanes), or `other`.
   */
  id: string;
  /** The option's value; `null` for the「其他」lane. */
  value: string | number | null;
  /** The option's label; empty for the「其他」lane, which the screen words. */
  label: string;
  /** How the option's badge reads, when the definition says. */
  tone?: FieldTone;
  /** The lane's rows so far — the same objects `RecordProjection.rows` holds. */
  rows: RecordRow[];
  /**
   * Every record the lane holds: the lane aggregation's count, or a paged
   * source's total for the lane. `null` when neither says — the「其他」lane
   * of a cursor source, or any lane of one whose aggregation failed.
   */
  total: number | null;
  /** The page「加载更多」asks for next; `null` when the lane is all here. */
  next: RecordPageTarget | null;
  /** The lane's summaries, when the config asks for any and they came back. */
  summaries: SummaryRow | null;
}

export interface RecordProjection {
  columns: RecordColumnView[];
  /** The same result's card layout; both halves are saved side by side. */
  card: RecordCardView;
  /** Every row on screen; under a kanban, the lanes' rows in lane order. */
  rows: RecordRow[];
  /** Under a kanban, the first lane's: the lanes page on their own. */
  paging: RecordPaging;
  /** The lanes, when the result is a kanban's (`projectKanban`). */
  lanes?: RecordLaneView[];
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
    ...labelRemoteOf(field),
    ...numberFormatPart(field),
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
    ...(field.sortable === true && readsAsNames(field)
      ? { sortsById: true as const }
      : {}),
    numberFormat: numberFormatOf(field),
    ...currencyPart(field),
    ...(field.numberFormat === undefined && field.numeric
      ? { numeric: field.numeric }
      : {}),
    ...(field.options ? { options: field.options } : {}),
    ...labelRemoteOf(field),
    ...elementTitleOf(field),
    ...timeUnitOf(field),
    // The default is the minute, and only a table cell is ever short, so
    // the column carries the one thing that changes it.
    ...(field.timePrecision === 'second'
      ? { timePrecision: 'second' as const }
      : {}),
  };
}

function currencyPart(field: FieldDefinition): { currencyPath?: string } {
  const currencyPath = currencyPathOf(field);
  return currencyPath === undefined ? {} : { currencyPath };
}

/** How a number of the field is written, where it says. */
function numberFormatPart(field: FieldDefinition): {
  numberFormat?: NumberFormat;
  currencyPath?: string;
} {
  const numberFormat = numberFormatOf(field);
  const currencyPath = currencyPathOf(field);
  return {
    ...(numberFormat ? { numberFormat } : {}),
    ...(currencyPath === undefined ? {} : { currencyPath }),
  };
}

/**
 * The epoch unit a time field counts in, where it is not milliseconds; a
 * summary cell of that field carries it too (`summaries.ts`).
 */
export function timeUnitOf(field: FieldDefinition): {
  timeUnit?: EpochTimeUnit;
} {
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
 * The part of a record view that is drawn from the config alone — the
 * columns and the card — and not from the rows.
 *
 * `projectRecord` builds a view from both; `restyleRecord` builds only this
 * part again, over rows that are already on screen.
 */
function drawnParts(
  definition: DataViewDefinition,
  config: RecordViewConfig,
): Pick<RecordProjection, 'columns' | 'card'> & { rowKey: string } {
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

  return { columns, card: cardView(config.card, byName), rowKey };
}

/**
 * The same rows drawn under another config that asks the source the same
 * question: a column wider, moved or pinned (`restyledOnly` in the runtime
 * decides that it is only that). The rows and the paging stay the ones that
 * landed; the columns and the card are projected again, so a presentation
 * edit is on screen without a round trip to the source.
 */
export function restyleRecord(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  view: RecordProjection,
): RecordProjection {
  const { columns, card } = drawnParts(definition, config);
  return { ...view, columns, card };
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
  limits: Pick<RuntimeLimits, 'maxPageWindow'> = DEFAULT_RUNTIME_LIMITS,
): RecordProjection {
  const { columns, card, rowKey } = drawnParts(definition, config);
  const rows = page.list.map(data => ({
    key: readPath(data, rowKey) as RecordKey,
    data,
  }));

  return {
    columns,
    card,
    rows,
    // The size is the one this config ran at: the pager divides by it, and
    // the draft's may already be another one on its way.
    paging: isCursorPage(page)
      ? cursorPaging(page.nextCursor)
      : pagedPaging({
          index: pageIndex,
          size: config.pageSize,
          total: page.total,
          exact: page.exact,
          maxWindow: pageWindow(definition.record, limits),
        }),
  };
}

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
