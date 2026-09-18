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
} from '@ahoo-wang/fetcher-wow';
import {
  fieldAliasSegment,
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

function compileSort(config: RecordViewConfig): FieldSort[] {
  return config.sort.map(sort => ({
    field: sort.field,
    direction:
      sort.direction === 'DESC' ? SortDirection.DESC : SortDirection.ASC,
  }));
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
  const sort = compileSort(config);

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
