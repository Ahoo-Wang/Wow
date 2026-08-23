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

import { DeletionState } from './condition';

export type LogicalField<FIELDS extends string = string> = FIELDS;
export type FilterLiteral = null | string | number | boolean;
export type ComparableFilterLiteral = Exclude<FilterLiteral, null>;

export enum FilterOperator {
  MATCH_ALL = 'MATCH_ALL',
  MATCH_NONE = 'MATCH_NONE',
  AND = 'AND',
  OR = 'OR',
  NOR = 'NOR',
  EQ = 'EQ',
  NE = 'NE',
  GT = 'GT',
  GTE = 'GTE',
  LT = 'LT',
  LTE = 'LTE',
  CONTAINS = 'CONTAINS',
  STARTS_WITH = 'STARTS_WITH',
  ENDS_WITH = 'ENDS_WITH',
  IN = 'IN',
  NOT_IN = 'NOT_IN',
  BETWEEN = 'BETWEEN',
  CONTAINS_ALL = 'CONTAINS_ALL',
  IS_EMPTY = 'IS_EMPTY',
  IS_NULL = 'IS_NULL',
  IS_NOT_NULL = 'IS_NOT_NULL',
  EXISTS = 'EXISTS',
  NOT_EXISTS = 'NOT_EXISTS',
  DELETION = 'DELETION',
  ELEMENT_MATCH = 'ELEMENT_MATCH',
  SEARCH = 'SEARCH',
  TODAY = 'TODAY',
  BEFORE_TODAY = 'BEFORE_TODAY',
  TOMORROW = 'TOMORROW',
  THIS_WEEK = 'THIS_WEEK',
  NEXT_WEEK = 'NEXT_WEEK',
  LAST_WEEK = 'LAST_WEEK',
  THIS_MONTH = 'THIS_MONTH',
  LAST_MONTH = 'LAST_MONTH',
  RECENT_DAYS = 'RECENT_DAYS',
  EARLIER_DAYS = 'EARLIER_DAYS',
}

export enum StringComparison {
  CASE_SENSITIVE = 'CASE_SENSITIVE',
  CASE_INSENSITIVE = 'CASE_INSENSITIVE',
}

const LOCAL_TIME_PATTERN =
  /^([01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/;
const LOGICAL_FIELD_PATTERN =
  /^[A-Za-z_][A-Za-z0-9_-]*(\.(?:[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$/;
const OFFSET_ZONE_PATTERN =
  /^(?:UTC|GMT|UT)?[+-](\d{1,2}|\d{4}|\d{6}|\d{2}:\d{2}|\d{2}:\d{2}:\d{2})$/;
const OFFSET_ZONE_CANDIDATE_PATTERN = /^(?:UTC|GMT|UT)?[+-]/;
const DATE_PATTERN_COUNTS: Readonly<
  Record<string, number | readonly number[]>
> = {
  G: 5,
  u: 19,
  y: 19,
  Q: 5,
  q: 5,
  M: 5,
  L: 5,
  D: 3,
  d: 2,
  F: 1,
  E: 5,
  e: 5,
  c: [1, 3, 4, 5],
  a: 1,
  B: [1, 4, 5],
  h: 2,
  H: 2,
  k: 2,
  K: 2,
  m: 2,
  s: 2,
  S: 9,
  A: 19,
  n: 19,
  N: 19,
  V: [2],
  v: [1, 4],
  z: 4,
  O: [1, 4],
  X: 5,
  x: 5,
  Z: 5,
  W: 1,
  w: 2,
  Y: Number.POSITIVE_INFINITY,
  g: 19,
};

function logicalField<FIELDS extends string>(field: FIELDS): FIELDS {
  if (typeof field !== 'string' || !LOGICAL_FIELD_PATTERN.test(field)) {
    throw new TypeError(`Logical field is invalid: [${String(field)}].`);
  }
  return field;
}

function filterLiteral<T extends FilterLiteral>(
  value: T,
  nullable: boolean,
): T {
  const valid =
    value === null
      ? nullable
      : typeof value === 'string' ||
        typeof value === 'boolean' ||
        (typeof value === 'number' && Number.isFinite(value));
  if (!valid) {
    throw new TypeError('Filter value must be a JSON scalar.');
  }
  return value;
}

function requiredString(name: string, value: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${name} must be a string.`);
  }
  return value;
}

function validateStringComparison(comparison: StringComparison): void {
  if (
    comparison !== StringComparison.CASE_SENSITIVE &&
    comparison !== StringComparison.CASE_INSENSITIVE
  ) {
    throw new TypeError(
      `String comparison is invalid: [${String(comparison)}].`,
    );
  }
}

function requireNonEmpty(name: string, values: readonly unknown[]): void {
  if (values.length === 0) {
    throw new TypeError(`${name} cannot be empty.`);
  }
  if (values.some(value => value === null || value === undefined)) {
    throw new TypeError(`${name} cannot contain null.`);
  }
}

function isValidOffsetZone(zoneId: string): boolean {
  const match = OFFSET_ZONE_PATTERN.exec(zoneId);
  if (!match) return false;
  const offset = match[1];
  const parts = offset.includes(':')
    ? offset.split(':')
    : offset.length <= 2
      ? [offset]
      : [offset.slice(0, 2), offset.slice(2, 4), offset.slice(4, 6)];
  const [hours, minutes = 0, seconds = 0] = parts.map(Number);
  return (
    minutes <= 59 &&
    seconds <= 59 &&
    (hours < 18 || (hours === 18 && minutes === 0 && seconds === 0))
  );
}

function validateRelativeTimeOptions({
  zoneId,
  datePattern,
}: RelativeTimeFilterOptions): RelativeTimeFilterOptions {
  if (zoneId !== undefined) {
    if (typeof zoneId !== 'string' || !zoneId.trim()) {
      throw new TypeError('zoneId cannot be blank.');
    }
    if (
      OFFSET_ZONE_CANDIDATE_PATTERN.test(zoneId) &&
      !isValidOffsetZone(zoneId)
    ) {
      throw new TypeError(`zoneId is invalid: [${zoneId}].`);
    }
  }
  if (datePattern !== undefined) {
    validateDatePattern(datePattern);
  }
  return {
    ...(zoneId === undefined ? {} : { zoneId }),
    ...(datePattern === undefined ? {} : { datePattern }),
  };
}

function validateDatePatternLetter(
  pattern: string,
  letter: string,
  count: number,
): void {
  const allowed = DATE_PATTERN_COUNTS[letter];
  const valid =
    typeof allowed === 'number'
      ? count <= allowed
      : allowed?.includes(count) === true;
  if (!valid) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}

function isNumericDatePatternLetter(
  letter: string | undefined,
  count: number,
): boolean {
  return (
    letter !== undefined &&
    ('uyDFdhHkKmsSgAnNWwY'.includes(letter) ||
      (letter === 'c' && count === 1) ||
      ('eMLQq'.includes(letter) && count <= 2))
  );
}

function validateDatePattern(pattern: string): void {
  if (typeof pattern !== 'string' || !pattern.trim()) {
    throw new TypeError('datePattern cannot be blank.');
  }
  let quoted = false;
  let optionalDepth = 0;
  for (let index = 0; index < pattern.length; index++) {
    const character = pattern[index];
    if (character === "'") {
      if (pattern[index + 1] === "'") {
        index++;
      } else {
        quoted = !quoted;
      }
      continue;
    }
    if (quoted) continue;
    if (/[A-Za-z]/.test(character)) {
      let letter = character;
      let end = index + 1;
      while (pattern[end] === letter) end++;
      let count = end - index;
      let padded = false;
      if (letter === 'p') {
        padded = true;
        letter = pattern[end];
        if (!letter || !/[A-Za-z]/.test(letter) || letter === 'p') {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
        const fieldStart = end++;
        while (pattern[end] === letter) end++;
        count = end - fieldStart;
      }
      validateDatePatternLetter(pattern, letter, count);
      if (padded && isNumericDatePatternLetter(letter, count)) {
        const nextLetter = pattern[end];
        let nextEnd = end;
        while (nextEnd < pattern.length && pattern[nextEnd] === nextLetter)
          nextEnd++;
        if (isNumericDatePatternLetter(nextLetter, nextEnd - end)) {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
      }
      index = end - 1;
    } else if (character === '[') {
      optionalDepth++;
    } else if (character === ']') {
      if (optionalDepth === 0)
        throw new TypeError(`datePattern is invalid: [${pattern}].`);
      optionalDepth--;
    } else if ('{}#'.includes(character)) {
      throw new TypeError(`datePattern is invalid: [${pattern}].`);
    }
  }
  if (quoted) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}

function validateDays(operator: FilterOperator, days: number): void {
  if (!Number.isInteger(days) || days < 1 || days > 2_147_483_647) {
    throw new TypeError(`${operator} days must be a positive JVM Int.`);
  }
}

export type MatchFilter = {
  op: FilterOperator.MATCH_ALL | FilterOperator.MATCH_NONE;
};

export type LogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: FilterExpression<FIELDS>[];
};

export type ElementLogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: ElementFilterExpression<FIELDS>[];
};

export type EqualityFilter<FIELDS extends string = string> = {
  op: FilterOperator.EQ | FilterOperator.NE;
  field: LogicalField<FIELDS>;
  value: FilterLiteral;
};

export type ComparisonFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.GT
    | FilterOperator.GTE
    | FilterOperator.LT
    | FilterOperator.LTE;
  field: LogicalField<FIELDS>;
  value: ComparableFilterLiteral;
};

export type StringFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.CONTAINS
    | FilterOperator.STARTS_WITH
    | FilterOperator.ENDS_WITH;
  field: LogicalField<FIELDS>;
  value: string;
  stringComparison?: StringComparison;
};

export type CollectionFilter<FIELDS extends string = string> = {
  op: FilterOperator.IN | FilterOperator.NOT_IN | FilterOperator.CONTAINS_ALL;
  field: LogicalField<FIELDS>;
  values: ComparableFilterLiteral[];
};

export type BetweenFilter<FIELDS extends string = string> = {
  op: FilterOperator.BETWEEN;
  field: LogicalField<FIELDS>;
  lowerBound: ComparableFilterLiteral;
  upperBound: ComparableFilterLiteral;
};

export type FieldPresenceFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.IS_EMPTY
    | FilterOperator.IS_NULL
    | FilterOperator.IS_NOT_NULL
    | FilterOperator.EXISTS
    | FilterOperator.NOT_EXISTS;
  field: LogicalField<FIELDS>;
};

export type DeletionFilter = {
  op: FilterOperator.DELETION;
  state: DeletionState;
};

export type ElementMatchFilter<
  FIELDS extends string = string,
  ELEMENT_FIELDS extends string = string,
> = {
  op: FilterOperator.ELEMENT_MATCH;
  field: LogicalField<FIELDS>;
  predicate: ElementFilterExpression<ELEMENT_FIELDS>;
};

export type SearchFilter<FIELDS extends string = string> = {
  op: FilterOperator.SEARCH;
  query: string;
  fields?: LogicalField<FIELDS>[];
};

export interface RelativeTimeFilterOptions {
  zoneId?: string;
  datePattern?: string;
}

export type CalendarFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op:
      | FilterOperator.TODAY
      | FilterOperator.TOMORROW
      | FilterOperator.THIS_WEEK
      | FilterOperator.NEXT_WEEK
      | FilterOperator.LAST_WEEK
      | FilterOperator.THIS_MONTH
      | FilterOperator.LAST_MONTH;
    field: LogicalField<FIELDS>;
  };

export type BeforeTodayFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.BEFORE_TODAY;
    field: LogicalField<FIELDS>;
    time: string;
  };

export type DaysFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.RECENT_DAYS | FilterOperator.EARLIER_DAYS;
    field: LogicalField<FIELDS>;
    days: number;
  };

export type ElementFilterExpression<FIELDS extends string = string> =
  | MatchFilter
  | ElementLogicalFilter<FIELDS>
  | EqualityFilter<FIELDS>
  | ComparisonFilter<FIELDS>
  | StringFilter<FIELDS>
  | CollectionFilter<FIELDS>
  | BetweenFilter<FIELDS>
  | FieldPresenceFilter<FIELDS>
  | ElementMatchFilter<FIELDS>
  | CalendarFilter<FIELDS>
  | BeforeTodayFilter<FIELDS>
  | DaysFilter<FIELDS>;

export type FilterExpression<FIELDS extends string = string> =
  | MatchFilter
  | LogicalFilter<FIELDS>
  | EqualityFilter<FIELDS>
  | ComparisonFilter<FIELDS>
  | StringFilter<FIELDS>
  | CollectionFilter<FIELDS>
  | BetweenFilter<FIELDS>
  | FieldPresenceFilter<FIELDS>
  | DeletionFilter
  | ElementMatchFilter<FIELDS>
  | SearchFilter<FIELDS>
  | CalendarFilter<FIELDS>
  | BeforeTodayFilter<FIELDS>
  | DaysFilter<FIELDS>;

export interface FilterCapable<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
}

function validateElementPredicate(predicate: FilterExpression): void {
  switch (predicate.op) {
    case FilterOperator.DELETION:
    case FilterOperator.SEARCH:
      throw new TypeError(
        'ELEMENT_MATCH predicate cannot contain DELETION or SEARCH.',
      );
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      requireNonEmpty(`${predicate.op} operands`, predicate.operands);
      predicate.operands.forEach(validateElementPredicate);
      break;
    case FilterOperator.ELEMENT_MATCH:
      validateElementPredicate(predicate.predicate);
      break;
  }
}

function andFilter<FIELDS extends string>(
  ...operands: [
    ElementFilterExpression<FIELDS>,
    ...ElementFilterExpression<FIELDS>[],
  ]
): ElementLogicalFilter<FIELDS>;
function andFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS>;
function andFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS> {
  requireNonEmpty('AND operands', operands);
  return { op: FilterOperator.AND, operands };
}

function orFilter<FIELDS extends string>(
  ...operands: [
    ElementFilterExpression<FIELDS>,
    ...ElementFilterExpression<FIELDS>[],
  ]
): ElementLogicalFilter<FIELDS>;
function orFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS>;
function orFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS> {
  requireNonEmpty('OR operands', operands);
  return { op: FilterOperator.OR, operands };
}

function norFilter<FIELDS extends string>(
  ...operands: [
    ElementFilterExpression<FIELDS>,
    ...ElementFilterExpression<FIELDS>[],
  ]
): ElementLogicalFilter<FIELDS>;
function norFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS>;
function norFilter<FIELDS extends string>(
  ...operands: [FilterExpression<FIELDS>, ...FilterExpression<FIELDS>[]]
): LogicalFilter<FIELDS> {
  requireNonEmpty('NOR operands', operands);
  return { op: FilterOperator.NOR, operands };
}

export const filter = {
  matchAll(): MatchFilter {
    return { op: FilterOperator.MATCH_ALL };
  },
  matchNone(): MatchFilter {
    return { op: FilterOperator.MATCH_NONE };
  },
  and: andFilter,
  or: orFilter,
  nor: norFilter,
  eq<FIELDS extends string>(
    field: FIELDS,
    value: FilterLiteral,
  ): EqualityFilter<FIELDS> {
    return {
      op: FilterOperator.EQ,
      field: logicalField(field),
      value: filterLiteral(value, true),
    };
  },
  ne<FIELDS extends string>(
    field: FIELDS,
    value: FilterLiteral,
  ): EqualityFilter<FIELDS> {
    return {
      op: FilterOperator.NE,
      field: logicalField(field),
      value: filterLiteral(value, true),
    };
  },
  gt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return {
      op: FilterOperator.GT,
      field: logicalField(field),
      value: filterLiteral(value, false),
    };
  },
  gte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return {
      op: FilterOperator.GTE,
      field: logicalField(field),
      value: filterLiteral(value, false),
    };
  },
  lt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return {
      op: FilterOperator.LT,
      field: logicalField(field),
      value: filterLiteral(value, false),
    };
  },
  lte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return {
      op: FilterOperator.LTE,
      field: logicalField(field),
      value: filterLiteral(value, false),
    };
  },
  contains<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.CONTAINS,
      field: logicalField(field),
      value: requiredString('CONTAINS value', value),
      stringComparison,
    };
  },
  startsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.STARTS_WITH,
      field: logicalField(field),
      value: requiredString('STARTS_WITH value', value),
      stringComparison,
    };
  },
  endsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.ENDS_WITH,
      field: logicalField(field),
      value: requiredString('ENDS_WITH value', value),
      stringComparison,
    };
  },
  isIn<FIELDS extends string>(
    field: FIELDS,
    ...values: [ComparableFilterLiteral, ...ComparableFilterLiteral[]]
  ): CollectionFilter<FIELDS> {
    requireNonEmpty('IN values', values);
    values.forEach(value => filterLiteral(value, false));
    return { op: FilterOperator.IN, field: logicalField(field), values };
  },
  notIn<FIELDS extends string>(
    field: FIELDS,
    ...values: [ComparableFilterLiteral, ...ComparableFilterLiteral[]]
  ): CollectionFilter<FIELDS> {
    requireNonEmpty('NOT_IN values', values);
    values.forEach(value => filterLiteral(value, false));
    return { op: FilterOperator.NOT_IN, field: logicalField(field), values };
  },
  containsAll<FIELDS extends string>(
    field: FIELDS,
    ...values: [ComparableFilterLiteral, ...ComparableFilterLiteral[]]
  ): CollectionFilter<FIELDS> {
    requireNonEmpty('CONTAINS_ALL values', values);
    values.forEach(value => filterLiteral(value, false));
    return {
      op: FilterOperator.CONTAINS_ALL,
      field: logicalField(field),
      values,
    };
  },
  between<FIELDS extends string>(
    field: FIELDS,
    lowerBound: ComparableFilterLiteral,
    upperBound: ComparableFilterLiteral,
  ): BetweenFilter<FIELDS> {
    return {
      op: FilterOperator.BETWEEN,
      field: logicalField(field),
      lowerBound: filterLiteral(lowerBound, false),
      upperBound: filterLiteral(upperBound, false),
    };
  },
  isEmpty<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return { op: FilterOperator.IS_EMPTY, field: logicalField(field) };
  },
  isNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return { op: FilterOperator.IS_NULL, field: logicalField(field) };
  },
  isNotNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return { op: FilterOperator.IS_NOT_NULL, field: logicalField(field) };
  },
  exists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return { op: FilterOperator.EXISTS, field: logicalField(field) };
  },
  notExists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return { op: FilterOperator.NOT_EXISTS, field: logicalField(field) };
  },
  deletion(state: DeletionState): DeletionFilter {
    if (
      state !== DeletionState.ACTIVE &&
      state !== DeletionState.DELETED &&
      state !== DeletionState.ALL
    ) {
      throw new TypeError(`Deletion state is invalid: [${String(state)}].`);
    }
    return { op: FilterOperator.DELETION, state };
  },
  elementMatch<FIELDS extends string, ELEMENT_FIELDS extends string>(
    field: FIELDS,
    predicate: ElementFilterExpression<ELEMENT_FIELDS>,
  ): ElementMatchFilter<FIELDS, ELEMENT_FIELDS> {
    validateElementPredicate(predicate);
    return {
      op: FilterOperator.ELEMENT_MATCH,
      field: logicalField(field),
      predicate,
    };
  },
  search<FIELDS extends string>(
    query: string,
    ...fields: LogicalField<FIELDS>[]
  ): SearchFilter<FIELDS> {
    if (typeof query !== 'string' || !query.trim()) {
      throw new TypeError('SEARCH query cannot be blank.');
    }
    return {
      op: FilterOperator.SEARCH,
      query,
      fields: fields.map(logicalField),
    };
  },
  today<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.TODAY,
      field: logicalField(field),
    };
  },
  beforeToday<FIELDS extends string>(
    field: FIELDS,
    time: string,
    options: RelativeTimeFilterOptions = {},
  ): BeforeTodayFilter<FIELDS> {
    if (typeof time !== 'string' || !LOCAL_TIME_PATTERN.test(time)) {
      throw new TypeError('BEFORE_TODAY time is invalid.');
    }
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.BEFORE_TODAY,
      field: logicalField(field),
      time,
    };
  },
  tomorrow<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.TOMORROW,
      field: logicalField(field),
    };
  },
  thisWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.THIS_WEEK,
      field: logicalField(field),
    };
  },
  nextWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.NEXT_WEEK,
      field: logicalField(field),
    };
  },
  lastWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.LAST_WEEK,
      field: logicalField(field),
    };
  },
  thisMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.THIS_MONTH,
      field: logicalField(field),
    };
  },
  lastMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.LAST_MONTH,
      field: logicalField(field),
    };
  },
  recentDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    validateDays(FilterOperator.RECENT_DAYS, days);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.RECENT_DAYS,
      field: logicalField(field),
      days,
    };
  },
  earlierDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    validateDays(FilterOperator.EARLIER_DAYS, days);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.EARLIER_DAYS,
      field: logicalField(field),
      days,
    };
  },
};
