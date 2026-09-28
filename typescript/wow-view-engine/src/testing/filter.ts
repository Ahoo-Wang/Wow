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

import { Query } from 'mingo';
import type { AnyObject } from 'mingo/types';
import {
  ComparisonOperator,
  DeletionState,
  FilterOperator,
  SearchMode,
  StringComparison,
  TimeUnit,
  type ElementFilterExpression,
  type FilterExpression,
  type NowFilter,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../model/record.js';
import { evaluated } from './expression.js';

type Filter = FilterExpression | ElementFilterExpression;

/**
 * Whether one document matches a filter, as `memorySource` reads it — the
 * question a test asks of a condition of its own, such as the one an old
 * screen used, to compare with what a view matched. Unlike a source, it
 * assumes nothing about deletion: without a `DELETION` condition a deleted
 * document is a candidate too. `now` is the clock `BEFORE_NOW` and
 * `AFTER_NOW` compare against (`Date.now` by default).
 */
export function matches(
  document: RecordData,
  filter: FilterExpression,
  options: { now?: () => number } = {},
): boolean {
  return new Query(criteria(atNow(filter, options.now))).test(document);
}

/** `value <op> against`, for an `EXPRESSION` filter and a `having`. */
export function compares(
  operator: ComparisonOperator,
  value: number,
  against: number,
): boolean {
  switch (operator) {
    case ComparisonOperator.EQ:
      return value === against;
    case ComparisonOperator.NE:
      return value !== against;
    case ComparisonOperator.GT:
      return value > against;
    case ComparisonOperator.GTE:
      return value >= against;
    case ComparisonOperator.LT:
      return value < against;
    case ComparisonOperator.LTE:
      return value <= against;
    default:
      throw new Error(
        `The memory source does not compare with ${String(operator)}.`,
      );
  }
}

/**
 * A Wow source answers only the records that are not deleted unless the
 * query carries a `DELETION` filter of its own — that reading is the
 * source's, not the caller's. A document without a `deleted` flag is not
 * deleted (`$ne: true`), where `wow-mongo` asks `deleted == false` of
 * snapshots that always carry the flag.
 */
export function withDeletionDefault(filter: FilterExpression): AnyObject {
  return mentionsDeletion(filter)
    ? criteria(filter)
    : { $and: [criteria(filter), { deleted: { $ne: true } }] };
}

function mentionsDeletion(filter: Filter): boolean {
  switch (filter.op) {
    case FilterOperator.DELETION:
      return true;
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      return filter.operands.some(mentionsDeletion);
    default:
      return false;
  }
}

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Wow's filter as the MongoDB predicate `wow-mongo` compiles it
 * (`AbstractMongoFilterCompiler`), for `mingo` to evaluate: MongoDB's own
 * treatment of a missing field, an explicit null, array elements and case is
 * then the answer — the semantics matrix Wow holds every backend to
 * (`FilterSemantics`, canonical on MongoDB). What has no translation is
 * refused, so a query the source cannot answer fails rather than answering
 * something plausible and wrong.
 *
 * An `ELEMENT_MATCH` predicate names the element's own fields, relative to
 * it, as `$elemMatch` does.
 */
export function criteria(filter: Filter): AnyObject {
  switch (filter.op) {
    case FilterOperator.MATCH_ALL:
      return {};
    case FilterOperator.MATCH_NONE:
      return { $nor: [{}] };
    // A computed value compared with a number: a record the expression has
    // no value for never matches, as wow-mongo's `$expr` guards it.
    case FilterOperator.EXPRESSION: {
      const { expression, comparison, value } = filter;
      return {
        $where: function (this: unknown) {
          const computed = evaluated(expression, this);
          return computed !== null && compares(comparison, computed, value);
        },
      };
    }
    // The logical and comparison operators carry MongoDB's names in capitals.
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      return {
        [`$${filter.op.toLowerCase()}`]: filter.operands.map(criteria),
      };
    case FilterOperator.EQ:
    case FilterOperator.NE:
    case FilterOperator.GT:
    case FilterOperator.GTE:
    case FilterOperator.LT:
    case FilterOperator.LTE:
      return {
        [filter.field]: { [`$${filter.op.toLowerCase()}`]: filter.value },
      };
    case FilterOperator.BETWEEN:
      return {
        [filter.field]: { $gte: filter.lowerBound, $lte: filter.upperBound },
      };
    case FilterOperator.IN:
      return { [filter.field]: { $in: filter.values } };
    case FilterOperator.NOT_IN:
      return { [filter.field]: { $nin: filter.values } };
    case FilterOperator.CONTAINS_ALL:
      return { [filter.field]: { $all: filter.values } };
    // The snapshot's own envelope, which the metadata operators name without
    // a field.
    case FilterOperator.AGGREGATE_ID:
      return { aggregateId: { $eq: filter.value } };
    case FilterOperator.AGGREGATE_IDS:
      return { aggregateId: { $in: filter.values } };
    case FilterOperator.OWNER_ID:
      return { ownerId: { $eq: filter.value } };
    // Literal matches through an escaped regular expression.
    case FilterOperator.CONTAINS:
    case FilterOperator.STARTS_WITH:
    case FilterOperator.ENDS_WITH: {
      const text = escape(filter.value);
      const pattern =
        filter.op === FilterOperator.STARTS_WITH
          ? `^${text}`
          : filter.op === FilterOperator.ENDS_WITH
            ? `${text}$`
            : text;
      const flags =
        filter.stringComparison === StringComparison.CASE_INSENSITIVE
          ? 'i'
          : '';
      return { [filter.field]: new RegExp(pattern, flags) };
    }
    // `$size: 0`: only an actual empty array — a missing field or a null has
    // no size.
    case FilterOperator.IS_EMPTY:
      return { [filter.field]: { $size: 0 } };
    // `= null` matches null and a missing field; `!= null` neither.
    case FilterOperator.IS_NULL:
      return { [filter.field]: { $eq: null } };
    case FilterOperator.IS_NOT_NULL:
      return { [filter.field]: { $ne: null } };
    // Wow lowers both before a store reads them (`FilterOperatorSpec`):
    // `= ""`, and `!= null AND != ""` — a missing or null field is not a
    // non-empty string.
    case FilterOperator.IS_EMPTY_STRING:
      return { [filter.field]: { $eq: '' } };
    case FilterOperator.IS_NOT_EMPTY_STRING:
      return {
        $and: [
          { [filter.field]: { $ne: null } },
          { [filter.field]: { $ne: '' } },
        ],
      };
    // Whether the path is there at all: a field holding null exists.
    case FilterOperator.EXISTS:
      return { [filter.field]: { $exists: true } };
    case FilterOperator.NOT_EXISTS:
      return { [filter.field]: { $exists: false } };
    case FilterOperator.DELETION:
      return filter.state === DeletionState.ALL
        ? {}
        : { deleted: { $eq: filter.state === DeletionState.DELETED } };
    // Full text over the fields the search names, without regard to case: a
    // phrase is the words together, in order; terms are any one of them.
    // MongoDB's text index decides relevance and stemming; this is the
    // literal reading of it.
    case FilterOperator.SEARCH: {
      if (!filter.fields?.length)
        throw new Error('The memory source searches named fields only.');
      const words =
        filter.mode === SearchMode.PHRASE
          ? [filter.query.trim()]
          : filter.query.trim().split(/\s+/);
      return {
        $or: filter.fields.flatMap(field =>
          words.map(word => ({
            [String(field)]: new RegExp(escape(word), 'i'),
          })),
        ),
      };
    }
    case FilterOperator.ELEMENT_MATCH:
      return { [filter.field]: { $elemMatch: criteria(filter.predicate) } };
    default:
      throw new Error(
        `The memory source does not evaluate ${(filter as { op: string }).op}.`,
      );
  }
}

/**
 * A query with every `BEFORE_NOW` / `AFTER_NOW` in it — at the root, in a
 * logical or element match, in a metric's or an element's filter — lowered
 * the way Wow's `FilterNormalizer` lowers them: to `LT` / `GT` against
 * `now + offset`, encoded as the field keeps its time. The clock is read
 * once, and only when the query holds one.
 */
export function atNow<Q>(query: Q, clock: () => number = Date.now): Q {
  let now: number | undefined;
  const lower = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(lower);
    if (node === null || typeof node !== 'object' || node instanceof RegExp)
      return node;
    const op = (node as { op?: unknown }).op;
    if (op === FilterOperator.BEFORE_NOW || op === FilterOperator.AFTER_NOW) {
      now ??= clock();
      return nowComparison(node as NowFilter, now);
    }
    return Object.fromEntries(
      Object.entries(node).map(([key, value]) => [key, lower(value)]),
    );
  };
  return lower(query) as Q;
}

/** How many of each of Wow's time units one millisecond is. */
const PER_MILLISECOND: Record<TimeUnit, number> = {
  [TimeUnit.NANOSECONDS]: 1_000_000,
  [TimeUnit.MICROSECONDS]: 1_000,
  [TimeUnit.MILLISECONDS]: 1,
  [TimeUnit.SECONDS]: 1 / 1_000,
  [TimeUnit.MINUTES]: 1 / 60_000,
  [TimeUnit.HOURS]: 1 / 3_600_000,
  [TimeUnit.DAYS]: 1 / 86_400_000,
};

/**
 * `field < now + offset` or `field > now + offset`, strictly, the moment
 * counted in the filter's `timeUnit` and truncated as `TimeUnit.convert`
 * truncates it. A record without the field matches neither.
 */
function nowComparison(filter: NowFilter, now: number): FilterExpression {
  if (filter.datePattern !== undefined)
    throw new Error(
      'The memory source keeps times as epoch numbers, not as formatted text.',
    );
  const moment = now + durationMs(filter.offset ?? 'PT0S');
  const unit = filter.timeUnit ?? TimeUnit.MILLISECONDS;
  return {
    op:
      filter.op === FilterOperator.BEFORE_NOW
        ? FilterOperator.LT
        : FilterOperator.GT,
    field: filter.field,
    value: Math.trunc(moment * PER_MILLISECOND[unit]),
  };
}

/** `java.time.Duration.parse`'s grammar: days, hours, minutes, seconds. */
const DURATION =
  /^([-+]?)P(?:([-+]?\d+)D)?(?:T(?=[-+]?\d)(?:([-+]?\d+)H)?(?:([-+]?\d+)M)?(?:([-+]?\d+)(?:[.,](\d{0,9}))?S)?)?$/i;

/**
 * An ISO-8601 duration as `java.time.Duration.parse` reads it — each part
 * optionally signed, the whole optionally negated — in milliseconds.
 */
function durationMs(text: string): number {
  const match = DURATION.exec(text);
  if (!match || match.slice(2, 6).every(part => part === undefined))
    throw new Error(`The memory source does not read the duration ${text}.`);
  const [, sign, days, hours, minutes, seconds, fraction] = match;
  const fractionMs =
    Number(`0.${fraction ?? '0'}`) *
    1_000 *
    (seconds?.startsWith('-') ? -1 : 1);
  const ms =
    Number(days ?? 0) * 86_400_000 +
    Number(hours ?? 0) * 3_600_000 +
    Number(minutes ?? 0) * 60_000 +
    Number(seconds ?? 0) * 1_000 +
    fractionMs;
  return sign === '-' ? -ms : ms;
}
