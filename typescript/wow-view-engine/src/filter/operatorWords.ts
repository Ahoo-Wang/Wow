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
 * The English words of the filter's wire values: what a summary's `text`
 * says where a condition stores `gte`, `active` or `hour`. A wire value is
 * the protocol's spelling, not a word, so no `text` prints one; the English
 * catalogue reads its `label.operator.*` and `label.deletion.*` entries from
 * these tables, so the fallback and the screen say the same thing.
 */

import type { DeletionState } from '@ahoo-wang/wow-client/dsl';
import {
  ComparisonOperators,
  DateDiffUnits,
  DeletionStates,
  FilterOperators,
  type DateDiffUnitName,
  type FilterOperatorName,
} from '../model/index.js';
import type { ComparisonOperatorName } from './values.js';

/**
 * Every operator as the phrase a condition reads as — field, operator,
 * value — so `Amount at least 100` is a sentence. The record names the set
 * in full: a gap is a compile error, so a word cannot fall behind the enum.
 */
export const OPERATOR_TEXT: Readonly<Record<FilterOperatorName, string>> = {
  [FilterOperators.MATCH_ALL]: 'matches every record',
  [FilterOperators.MATCH_NONE]: 'matches no record',
  [FilterOperators.ID]: 'is',
  [FilterOperators.IDS]: 'is any of',
  [FilterOperators.AGGREGATE_ID]: 'is',
  [FilterOperators.AGGREGATE_IDS]: 'is any of',
  [FilterOperators.TENANT_ID]: 'is',
  [FilterOperators.OWNER_ID]: 'is',
  [FilterOperators.SPACE_ID]: 'is',
  [FilterOperators.AND]: 'and',
  [FilterOperators.OR]: 'or',
  [FilterOperators.NOR]: 'nor',
  [FilterOperators.EQ]: 'is',
  [FilterOperators.NE]: 'is not',
  // A comparison is read off the value beside it, so these say which side of
  // it the answer lies on rather than naming the symbol: "at least" and "at
  // most" carry the inclusive edge that `gte`/`lte` only spelled.
  [FilterOperators.GT]: 'more than',
  [FilterOperators.GTE]: 'at least',
  [FilterOperators.LT]: 'less than',
  [FilterOperators.LTE]: 'at most',
  [FilterOperators.CONTAINS]: 'contains',
  [FilterOperators.STARTS_WITH]: 'starts with',
  [FilterOperators.ENDS_WITH]: 'ends with',
  [FilterOperators.IN]: 'is any of',
  [FilterOperators.NOT_IN]: 'is none of',
  [FilterOperators.BETWEEN]: 'between',
  [FilterOperators.CONTAINS_ALL]: 'has all of',
  [FilterOperators.IS_EMPTY]: 'has no entries',
  [FilterOperators.IS_EMPTY_STRING]: 'is blank',
  [FilterOperators.IS_NOT_EMPTY_STRING]: 'is not blank',
  [FilterOperators.IS_NULL]: 'is empty',
  [FilterOperators.IS_NOT_NULL]: 'is not empty',
  [FilterOperators.EXISTS]: 'exists',
  [FilterOperators.NOT_EXISTS]: 'does not exist',
  [FilterOperators.DELETION]: 'is',
  [FilterOperators.ELEMENT_MATCH]: 'has an entry where',
  [FilterOperators.SEARCH]: 'contains',
  [FilterOperators.TODAY]: 'today',
  [FilterOperators.BEFORE_TODAY]: 'before today',
  [FilterOperators.TOMORROW]: 'tomorrow',
  [FilterOperators.THIS_WEEK]: 'this week',
  [FilterOperators.NEXT_WEEK]: 'next week',
  [FilterOperators.LAST_WEEK]: 'last week',
  [FilterOperators.THIS_MONTH]: 'this month',
  [FilterOperators.LAST_MONTH]: 'last month',
  [FilterOperators.YESTERDAY]: 'yesterday',
  [FilterOperators.NEXT_MONTH]: 'next month',
  [FilterOperators.LAST_YEAR]: 'last year',
  [FilterOperators.THIS_YEAR]: 'this year',
  [FilterOperators.NEXT_YEAR]: 'next year',
  [FilterOperators.RECENT_DAYS]: 'recent days',
  [FilterOperators.EARLIER_DAYS]: 'earlier days',
  [FilterOperators.BEFORE_NOW]: 'before now',
  [FilterOperators.AFTER_NOW]: 'after now',
  // On a time field, EXPRESSION is a time since another moment.
  [FilterOperators.EXPRESSION]: 'time since',
};

/** A comparison as its sign, the same in every language. */
export const COMPARISON_SIGN: Readonly<Record<ComparisonOperatorName, string>> =
  {
    [ComparisonOperators.GT]: '>',
    [ComparisonOperators.GTE]: '≥',
    [ComparisonOperators.LT]: '<',
    [ComparisonOperators.LTE]: '≤',
    [ComparisonOperators.EQ]: '=',
    [ComparisonOperators.NE]: '≠',
  };

/** The three answers of a deletion condition, as the reader chose them. */
export const DELETION_TEXT: Readonly<Record<`${DeletionState}`, string>> = {
  [DeletionStates.ACTIVE]: 'Not deleted',
  [DeletionStates.DELETED]: 'Deleted only',
  [DeletionStates.ALL]: 'Deleted included',
};

/** A time between two moments' unit, counted as English counts it. */
export const DATE_DIFF_UNIT_TEXT: Readonly<
  Record<DateDiffUnitName, { readonly one: string; readonly other: string }>
> = {
  [DateDiffUnits.SECOND]: { one: 'second', other: 'seconds' },
  [DateDiffUnits.MINUTE]: { one: 'minute', other: 'minutes' },
  [DateDiffUnits.HOUR]: { one: 'hour', other: 'hours' },
  [DateDiffUnits.DAY]: { one: 'day', other: 'days' },
};

/** 「48 hours」, 「1 day」: an amount of a unit. */
export function amountText(amount: number, unit: DateDiffUnitName): string {
  const words = DATE_DIFF_UNIT_TEXT[unit];
  return `${amount} ${amount === 1 ? words.one : words.other}`;
}
