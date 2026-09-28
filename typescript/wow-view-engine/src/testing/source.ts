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

import { find } from 'mingo';
import {
  DEFAULT_PAGINATION,
  FilterOperator,
  SortDirection,
  type AggregationQuery,
  type FieldSort,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../model/record.js';
import type { ViewSource } from '../runtime/source.js';
import { summarise } from './aggregate.js';
import { atNow, withDeletionDefault } from './filter.js';
import { valueAt } from './values.js';

/**
 * How `memorySource` keeps and reads its documents. None of it changes an
 * answer.
 */
export interface MemorySourceOptions {
  /**
   * The service's clock, in epoch milliseconds, which `BEFORE_NOW` and
   * `AFTER_NOW` compare against, read once per query and only by a query that
   * holds one of them. Defaults to `Date.now`. A test pins it to the moment
   * its data is built around — the one it pins the page's clock to — so
   * 「晚于现在」 answers the same on every run.
   */
  now?: () => number;
  /**
   * A time column every document holds as epoch milliseconds, the way a Wow
   * snapshot keeps `firstEventTime`. The documents are kept in its order — a
   * query with no sort of its own reads them oldest first — and a range on it
   * in the filter's top-level AND is cut out by binary search before the
   * filter reads anything. For large sets; without it the documents keep the
   * order they came in.
   */
  timeField?: string;
  /**
   * Answer a repeated aggregation from memory, keyed by the query, each
   * caller getting its own copy. Only for documents that never change under
   * the source; off by default, so a test that edits a document (a command
   * it stubs) sees the edit in the next answer.
   */
  remember?: boolean;
}

/** How many distinct aggregations one source remembers before it starts over. */
const MEMO_LIMIT = 512;

/**
 * A `ViewSource` over documents held in memory that answers each query the
 * way a Wow service over MongoDB does — filter, sort, page, projection and
 * aggregation — so a host's test sees the engine's real queries answered by
 * the semantics the engine is built on, not a canned answer.
 *
 * The documents are the snapshots (or event streams) as the service returns
 * them: `aggregateId`, `ownerId` and `deleted` on the envelope, the state
 * under `state`, times as epoch milliseconds. A document with
 * `deleted: true` is left out unless the filter carries a `DELETION`
 * condition. A cursor is an offset, as opaque to the caller as Wow's.
 *
 * What it cannot answer — an operator or shape it has no reading of, such as
 * `ID`, `TENANT_ID`, `SPACE_ID` or the calendar filters (`TODAY`, …) the
 * engine never sends — is refused with an error, so a query a test starts to
 * send fails rather than getting a plausible wrong answer. The documents are
 * read, never written; a test may change them between queries.
 */
export function memorySource(
  documents: readonly RecordData[],
  options: MemorySourceOptions = {},
): ViewSource {
  const table = tableOf(documents, options.timeField);
  const answers = new Map<string, RecordData[]>();
  const clock = options.now;
  return {
    paged: asked =>
      settled(() => {
        const query = atNow(asked, clock);
        const matched = matchedIn(table, query.filter, query.sort);
        const { index, size } = query.pagination ?? DEFAULT_PAGINATION;
        const start = (index - 1) * size;
        return {
          total: matched.length,
          list: matched.slice(start, start + size).map(projected(query)),
        };
      }),
    cursor: asked =>
      settled(() => {
        const query = atNow(asked, clock);
        const matched = matchedIn(table, query.filter, query.sort);
        const start = Number(query.cursor ?? 0);
        const end = start + (query.size ?? matched.length);
        return {
          list: matched.slice(start, end).map(projected(query)),
          nextCursor: end < matched.length ? String(end) : null,
        };
      }),
    aggregate: asked =>
      settled(() => {
        // Keyed by the moment asked about, not by the words 「晚于现在」.
        const query = atNow(asked, clock);
        if (!options.remember) return aggregateOver(table, query);
        const key = JSON.stringify(query);
        let answer = answers.get(key);
        if (!answer) {
          answer = aggregateOver(table, query);
          if (answers.size >= MEMO_LIMIT) answers.clear();
          answers.set(key, answer);
        }
        return structuredClone(answer);
      }),
  };
}

/** `work`'s answer as a service's: a promise, what it throws a rejection. */
function settled<T>(work: () => T): Promise<T> {
  return new Promise(resolve => resolve(work()));
}

function aggregateOver(table: Table, query: AggregationQuery): RecordData[] {
  const filter = query.filter ?? { op: FilterOperator.MATCH_ALL };
  return summarise(matchedIn(table, filter), query);
}

/** The documents a source answers from, with the time column's values beside them. */
interface Table {
  documents: RecordData[];
  timeField?: string;
  times?: Float64Array;
}

function tableOf(documents: readonly RecordData[], timeField?: string): Table {
  if (timeField === undefined) return { documents: [...documents] };
  const timed = documents.map(document => {
    const at = valueAt(document, timeField);
    if (typeof at !== 'number' || !Number.isFinite(at))
      throw new Error(
        `The memory source keeps ${timeField} as epoch milliseconds; a document holds ${String(at)}.`,
      );
    return { document, at };
  });
  // A stable sort: documents of the same instant keep the order they came in.
  timed.sort((left, right) => left.at - right.at);
  return {
    documents: timed.map(({ document }) => document),
    timeField,
    times: Float64Array.from(timed, ({ at }) => at),
  };
}

function matchedIn(
  table: Table,
  filter: FilterExpression,
  sort: readonly FieldSort[] = [],
): RecordData[] {
  const cursor = find(scoped(table, filter), withDeletionDefault(filter));
  return (sort.length > 0 ? cursor.sort(sortSpec(sort)) : cursor).all();
}

function sortSpec(sort: readonly FieldSort[]): Record<string, 1 | -1> {
  return Object.fromEntries(
    sort.map(({ field, direction }) => [
      field,
      direction === SortDirection.DESC ? -1 : 1,
    ]),
  );
}

/**
 * A document as the service answers it: only the paths the query's
 * projection includes, when it has one — MongoDB's own projection, where a
 * path through an array picks that member of every element.
 */
function projected(query: { projection?: { include?: readonly string[] } }) {
  const include = query.projection?.include;
  if (!include || include.length === 0)
    return (document: RecordData) => document;
  const spec = Object.fromEntries(include.map(path => [path, 1]));
  return (document: RecordData): RecordData =>
    find([document], {}, spec).all()[0] ?? {};
}

/**
 * The documents a filter can match, before it reads them: all of them, or —
 * when the filter's top-level AND bounds the time column with numbers — the
 * slice between those bounds, found by binary search. The whole filter still
 * runs over the slice, so the slice only ever holds more than the answer.
 */
function scoped(table: Table, filter: FilterExpression): RecordData[] {
  const { documents, timeField, times } = table;
  if (timeField === undefined || !times) return documents;
  let from = 0;
  let to = documents.length;
  for (const condition of conjuncts(filter)) {
    if (!('field' in condition) || condition.field !== timeField) continue;
    switch (condition.op) {
      case FilterOperator.GT:
      case FilterOperator.GTE:
      case FilterOperator.LT:
      case FilterOperator.LTE:
      case FilterOperator.EQ: {
        const { op, value } = condition;
        if (typeof value !== 'number' || !Number.isFinite(value)) break;
        if (op !== FilterOperator.LT && op !== FilterOperator.LTE)
          from = Math.max(
            from,
            firstAt(times, value, op === FilterOperator.GT),
          );
        if (op !== FilterOperator.GT && op !== FilterOperator.GTE)
          to = Math.min(to, firstAt(times, value, op !== FilterOperator.LT));
        break;
      }
      case FilterOperator.BETWEEN: {
        const { lowerBound, upperBound } = condition;
        if (typeof lowerBound === 'number' && Number.isFinite(lowerBound))
          from = Math.max(from, firstAt(times, lowerBound, false));
        if (typeof upperBound === 'number' && Number.isFinite(upperBound))
          to = Math.min(to, firstAt(times, upperBound, true));
        break;
      }
      default:
        break;
    }
  }
  if (from === 0 && to === documents.length) return documents;
  return from < to ? documents.slice(from, to) : [];
}

/** The conditions every match must meet: an AND's operands, nested ANDs flattened. */
function conjuncts(filter: FilterExpression): FilterExpression[] {
  return filter.op === FilterOperator.AND
    ? filter.operands.flatMap(conjuncts)
    : [filter];
}

/**
 * The index of the first time at or past `value` — strictly past it when
 * `after` — in ascending `times`; `times.length` when there is none.
 */
function firstAt(times: Float64Array, value: number, after: boolean): number {
  let low = 0;
  let high = times.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (after ? times[middle] <= value : times[middle] < value)
      low = middle + 1;
    else high = middle;
  }
  return low;
}
