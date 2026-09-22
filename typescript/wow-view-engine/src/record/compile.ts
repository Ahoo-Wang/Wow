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
  aggregation,
  SortDirection,
  type AggregationMetric,
  type AggregationQuery,
  type CursorQuery,
  type FieldSort,
  type FilterPagedQuery,
  type Projection,
} from '@ahoo-wang/fetcher-wow';
import {
  columnHidden,
  fieldAliasSegment,
  isFieldlessKind,
  type DataViewDefinition,
  type RecordPageTarget,
  type RecordViewConfig,
  type SummaryFunction,
} from '../model/index.js';
import {
  compileFilter,
  type FieldKindRegistry,
  type FilterCompileContext,
} from '../filter/index.js';

/** The first page of either paging mode; Wow numbers pages from 1. */
export const FIRST_PAGE: Readonly<
  Record<'paged' | 'cursor', RecordPageTarget>
> = Object.freeze({
  paged: Object.freeze({ index: 1 }),
  cursor: Object.freeze({ cursor: null }),
});

/**
 * The config's sort, then the row key ascending as the last key.
 *
 * Paging by index over a sort that ties is not stable: a backend may order
 * the tied rows one way for page 2 and another for page 3, so one row shows
 * on both and another on neither — measured against a Wow service ordering
 * failed executions by `eventTime`, where many share the millisecond. Wow
 * adds a unique key only to cursor queries, and not every backend adds one
 * at all, so the engine does: the row key is unique by definition, which
 * makes every sort total. Cursor queries carry it too — one rule, and
 * harmless where the backend already breaks ties. A sort already naming the
 * row key, in either direction, is total already and is left as it is.
 *
 * The key is the query's, never the user's: the config keeps the sort the
 * user chose, and every control reads that.
 */
function compileSort(config: RecordViewConfig, rowKey: string): FieldSort[] {
  const sort = config.sort.map(entry => ({
    field: entry.field,
    direction:
      entry.direction === 'DESC' ? SortDirection.DESC : SortDirection.ASC,
  }));
  return sort.some(entry => entry.field === rowKey)
    ? sort
    : [...sort, { field: rowKey, direction: SortDirection.ASC }];
}

/**
 * The fields a page of this view asks its source for: what the view shows,
 * and what reads a row besides.
 *
 * A Wow snapshot is the whole document, and a document can carry far more
 * than anyone looks at — a failed execution holds its stack trace, several
 * KB of it, on every row. Against the compensation service a page of 100
 * failed executions measured 808 KB and 2.9 s asked for whole, and 46 KB and
 * 1.6 s asked for what its view shows. So a page names its fields:
 *
 * - the row key, which is what a row *is* — selection, the row's React key
 *   and every action read it;
 * - the table's visible columns, and the card's title, image and body when
 *   the definition offers cards. Both layouts, whichever is current: table
 *   or cards is a presentation member (`RECORD_PRESENTATION_MEMBERS`), so
 *   switching draws the same rows from the same query at once, and a card
 *   switched to must not come up blank waiting for a second one;
 * - the sort, so a source that works the next cursor out of the last row
 *   finds its values there — Wow's own backend adds them itself, but
 *   `ViewSource` is a port anyone implements;
 * - every summary but `COUNT`: the page scope is the rows on screen added
 *   up (`pageSummaries`), and it is also what the row falls back to when
 *   the totals' own aggregation fails. `COUNT` counts rows and reads none;
 * - `RecordCapability.rowFields`: what the host's own code reads off a row
 *   — a row or bulk action, a custom cell — that the view need not show.
 *
 * A hidden column is not drawn and not exported, so it is not fetched
 * either: switching it back on edits the config and applies it, and the
 * query that brings the column also brings its values.
 *
 * Only paths the definition declares are asked for — the config comes from
 * a store — and a field-less kind's name is a handle, not a path, so it is
 * left out; the row key is always a path. A path under another one asked
 * for is dropped: the ancestor brings it, and MongoDB refuses the two
 * together as a path collision.
 */
export function recordProjection(
  definition: DataViewDefinition,
  config: RecordViewConfig,
): Projection {
  const capability = definition.record;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no record capability`,
    );
  const paths = new Set(
    definition.fields
      .filter(field => !isFieldlessKind(field.kind))
      .map(field => field.name),
  );
  const asked: string[] = [capability.rowKey];
  for (const column of config.table.columns)
    if (!columnHidden(column.hidden)) asked.push(column.field);
  if (capability.layouts.includes('card')) {
    asked.push(config.card.title);
    if (config.card.image !== undefined) asked.push(config.card.image);
    asked.push(...config.card.fields);
  }
  for (const sort of config.sort) asked.push(sort.field);
  for (const summary of config.summaries ?? [])
    if (summary.fn !== 'COUNT') asked.push(summary.field);
  asked.push(...(capability.rowFields ?? []));

  const include = [
    ...new Set(
      asked.filter(field => field === capability.rowKey || paths.has(field)),
    ),
  ];
  return {
    include: include.filter(
      field => !include.some(other => field.startsWith(`${other}.`)),
    ),
  };
}

/**
 * Compiles the applied config into the query its source understands. Which of
 * the two it is comes from the definition, not from the config: paging is a
 * property of the backing query API.
 */
export function compileRecord(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
  page: RecordPageTarget,
): FilterPagedQuery | CursorQuery {
  const capability = definition.record;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no record capability`,
    );
  const compiled = compileFilter(
    definition.fields,
    config.filter,
    kinds,
    context,
  );
  const sort = compileSort(config, capability.rowKey);
  const projection = recordProjection(definition, config);

  // The mode decides, and the target must agree with it. Reading the mode off
  // the target instead would let a cursor target quietly turn a paged source
  // into a cursor query, which is a programming error rather than something a
  // config can say — `RecordPageTarget` is typed by the declared mode.
  if (capability.paging === 'cursor') {
    if (!('cursor' in page))
      throw new Error(
        `Definition ${definition.id} pages by cursor, but the page target names an index`,
      );
    return {
      filter: compiled,
      projection,
      sort,
      size: config.pageSize,
      cursor: page.cursor,
    };
  }
  if (!('index' in page))
    throw new Error(
      `Definition ${definition.id} pages by index, but the page target names a cursor`,
    );
  return {
    filter: compiled,
    projection,
    sort,
    pagination: { index: page.index, size: config.pageSize },
  };
}

const SUMMARY_METRIC: Readonly<
  Record<SummaryFunction, (field: string, alias: string) => AggregationMetric>
> = Object.freeze({
  COUNT: (_field, alias) => aggregation.count(alias),
  SUM: (field, alias) => aggregation.sum(aggregation.field(field), alias),
  AVG: (field, alias) => aggregation.avg(aggregation.field(field), alias),
  MIN: (field, alias) => aggregation.min(aggregation.field(field), alias),
  MAX: (field, alias) => aggregation.max(aggregation.field(field), alias),
});

/** Alias of one summary cell; the projection reads the result back by it. */
export function summaryAlias(field: string, fn: SummaryFunction): string {
  // Aliases are single-segment in Wow, so a field path becomes one token.
  return `${fieldAliasSegment(field)}_${fn.toLowerCase()}`;
}

/**
 * Totals over the whole filtered range, as one ungrouped aggregation. Page
 * totals need no query: they are computed from the rows already on screen.
 */
export function compileSummaries(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  kinds: FieldKindRegistry,
  context: FilterCompileContext,
): AggregationQuery | null {
  const summaries = config.summaries ?? [];
  if (summaries.length === 0) return null;

  const metrics = summaries.map(summary =>
    SUMMARY_METRIC[summary.fn](
      summary.field,
      summaryAlias(summary.field, summary.fn),
    ),
  );
  return {
    filter: compileFilter(definition.fields, config.filter, kinds, context),
    metrics: metrics as [AggregationMetric, ...AggregationMetric[]],
  };
}
