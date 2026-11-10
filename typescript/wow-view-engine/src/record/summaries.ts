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
 * The summary row under a record table or a card list: the cells a config
 * asks for, over the rows on screen (`page`) or read back from their own
 * aggregation (`total`).
 *
 * The two scopes are worked out here together so they cannot drift: a page
 * reduces the visible rows with the same arithmetic the projection runs for a
 * `page` source (`pageSummaries`, `projectSummaries`).
 */

import type { AggregationFunction } from '@ahoo-wang/wow-client/dsl';
import { readPath } from './path.js';
import {
  isDateCell,
  currencyPathOf,
  numberFormatOf,
  AggregationFunctions,
  AggregationMetricTypes,
  type CurrencyReading,
  type DataViewDefinition,
  type EpochTimeUnit,
  type FieldDefinition,
  type NumberFormat,
  type RecordData,
  type RecordViewConfig,
  type SummaryFunction,
} from '../model/index.js';
import { companionReading, currencyOfRows } from '../model/currency.js';
import { readInstant } from '../filter/index.js';
import { recordValue, timeUnitOf, type RecordRow } from './project.js';
import { summaryAlias } from './summaryAlias.js';
import { summaryCurrency } from './summaryCurrency.js';

export interface SummaryCell {
  field: string;
  label: string;
  fn: SummaryFunction;
  /** `null` when the source returned nothing for this cell. */
  value: number | string | null;
  numberFormat?: NumberFormat;
  /**
   * For money whose currency each record holds: where a row holds it, so
   * the page's own total is read off the rows (`pageSummaries`).
   */
  currencyPath?: string;
  /**
   * For such money, the currency the total is in — or that it is in
   * several, when the value is no amount and is `null` (`CurrencyReading`).
   * Absent where it could not be told.
   */
  currency?: CurrencyReading;
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
  if (
    !field ||
    (fn !== AggregationFunctions.MIN && fn !== AggregationFunctions.MAX)
  )
    return undefined;
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
  fn: `${AggregationFunction.MIN}` | `${AggregationFunction.MAX}`,
): number | string | null {
  let best: { at: number; value: number | string } | undefined;
  for (const row of rows) {
    const value = recordValue(row, field);
    // A moment arrives as a string or as a number; anything else is not one
    // and is left out exactly as a non-number is left out of the maths.
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    const instant = readInstant(value);
    if (!instant) continue;
    if (
      !best ||
      (fn === AggregationFunctions.MIN
        ? instant.ms < best.at
        : instant.ms > best.at)
    )
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
  if (fn === AggregationMetricTypes.COUNT) return rows.length;
  if (
    reading !== undefined &&
    (fn === AggregationFunctions.MIN || fn === AggregationFunctions.MAX)
  )
    return reduceInstants(rows, field, fn);
  const numbers = rows
    .map(row => recordValue(row, field))
    .filter((value): value is number => typeof value === 'number');
  if (numbers.length === 0) return null;
  switch (fn) {
    case AggregationFunctions.SUM:
      return numbers.reduce((total, value) => total + value, 0);
    case AggregationFunctions.AVG:
      return (
        numbers.reduce((total, value) => total + value, 0) / numbers.length
      );
    case AggregationFunctions.MIN:
      return Math.min(...numbers);
    case AggregationFunctions.MAX:
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
    const currencyPath =
      field && summary.fn !== AggregationMetricTypes.COUNT
        ? currencyPathOf(field)
        : undefined;
    const companion = summaryCurrency(definition, summary);
    const currency =
      currencyPath === undefined
        ? undefined
        : source.scope === 'page'
          ? rowsCurrency(source.rows, summary.field, currencyPath)
          : companion && companionReading(row, companion);
    return {
      field: summary.field,
      label: field?.label ?? summary.field,
      fn: summary.fn,
      // A total of unlike amounts is no amount (`CurrencyReading`).
      value: currency?.type === 'mixed' ? null : value,
      numberFormat: numberFormatOf(field),
      ...(currencyPath === undefined ? {} : { currencyPath }),
      ...(currency ? { currency } : {}),
      ...(reading === undefined
        ? {}
        : { cell: reading, ...(field ? timeUnitOf(field) : {}) }),
    };
  });

  return { scope: source.scope, cells };
}

/** The currency of a column's amounts over these rows (`currencyOfRows`). */
function rowsCurrency(
  rows: readonly RecordData[],
  field: string,
  currencyPath: string,
): CurrencyReading | undefined {
  return currencyOfRows(
    rows,
    row => readPath(row, field),
    row => readPath(row, currencyPath),
  );
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
    cells: cells.map(cell => {
      // The reading travels on the cell, so this scope reduces a date column
      // to a date exactly as the projection did — two scopes reading one
      // column two ways is the drift these two functions sit together to
      // prevent.
      const value = reduceRows(data, cell.field, cell.fn, cell.cell);
      const currency =
        cell.currencyPath === undefined
          ? undefined
          : rowsCurrency(data, cell.field, cell.currencyPath);
      const page: SummaryCell = {
        ...cell,
        value: currency?.type === 'mixed' ? null : value,
      };
      delete page.currency;
      return currency ? { ...page, currency } : page;
    }),
  };
}
