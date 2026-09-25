---
title: 'Filter expressions and legacy conditions'
description: 'Filter expressions and legacy conditions — @ahoo-wang/wow-client'
---

# Filter expressions and legacy conditions

Use `filter.*` to construct the discriminated `FilterExpression` wire format (`op`). Legacy functions such as `eq` and `and`, exported by the `@ahoo-wang/wow-client/legacy` subpath rather than the package root, construct deprecated `Condition` objects (`operator`, `value`, `children`, `options`). They remain public throughout Wow 9.x for Wow 8.10 servers and existing callers, and are removed in v10. Do not rename keys or mix the two trees.

| New builder family                                                                                                            | Input / wire meaning                                                                      |
| ----------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| matchAll(), matchNone()                                                                                                       | Explicit all / none; no arguments.                                                        |
| id/aggregateId/tenantId/ownerId/spaceId(value)                                                                                | String metadata values; ids/aggregateIds accept nonempty readonly string arrays.          |
| and/or/nor(operands)                                                                                                          | Nonempty array, copied to operands; no legacy variadic syntax or empty-all normalization. |
| eq/ne(field, value)                                                                                                           | JSON scalar including null; numbers must be finite.                                       |
| gt/gte/lt/lte(field, value)                                                                                                   | Non-null comparable scalar.                                                               |
| contains/startsWith/endsWith(field, text, comparison?)                                                                        | Default CASE_SENSITIVE; use StringComparison.CASE_INSENSITIVE explicitly.                 |
| isIn/notIn/containsAll(field, values)                                                                                         | Nonempty array of non-null comparable scalars; copied.                                    |
| between(field, lowerBound, upperBound)                                                                                        | Validates scalar bounds, not their order or common semantic type.                         |
| isEmpty/isEmptyString/isNotEmptyString/isNull/isNotNull/exists/notExists(field)                                               | Distinct presence/empty operations; no value argument.                                    |
| deletion(state)                                                                                                               | DeletionState.ACTIVE/DELETED/ALL, validated.                                              |
| elementMatch(field, predicate)                                                                                                | Element-relative expression, recursively excludes root metadata/deletion/search.          |
| search(query, options?)                                                                                                       | Nonblank string, fields defaults [], mode defaults SearchMode.TERMS; PHRASE is explicit.  |
| today/tomorrow/yesterday/thisWeek/nextWeek/lastWeek/thisMonth/nextMonth/lastMonth/thisYear/nextYear/lastYear(field, options?) | Relative calendar filters, resolved by the server.                                        |
| beforeToday(field, time, options?)                                                                                            | Local time HH:mm with optional seconds and up to nine fractional digits.                  |
| recentDays/earlierDays(field, days, options?)                                                                                 | Positive JVM Int, maximum 2147483647.                                                     |

QueryField is a string type alias. Builders additionally reject invalid logical paths: segments start with a letter/underscore (optionally @), continue with letters/digits/underscore/hyphen, and allow numeric segments after a dot. RelativeTimeFilterOptions defaults timeUnit to MILLISECONDS, leaves zoneId/datePattern absent; it validates explicit offset zones and Java date-pattern syntax, but does not prove an arbitrary named zone exists on the server. No clock calculation is done in the browser. Invalid values/options throw TypeError before a request. Literal object creation can bypass these runtime builder checks; TypeScript alone is not validation.

## Legacy compatibility

Everything in this section and under [Legacy Condition builders and types](#condition-contracts) is imported from `@ahoo-wang/wow-client/legacy`; `DeletionState` stays on the root. Condition's fields are optional and value is any. `isValidateCondition` is a truthiness check. `and(...conditions)` removes absent/all children and flattens nested AND; no conditions produces all(). `or` drops absent entries and returns all() for no valid entries; `nor` returns all() for an empty argument list and otherwise preserves its children. They are not interchangeable with filter.or/filter.nor. Legacy `isIn(field, ...values)` is variadic, while `ids(values)` accepts a string array. `active()` is the legacy deletion selector; new code uses filter.deletion(DeletionState.ACTIVE). `ignoreCaseOptions` and `dateOptions` return undefined when no options were supplied. `raw` and `match` are backend-specific legacy escape hatches; no new raw filter builder exists. Operator, LOGICAL_OPERATORS, EMPTY_VALUE_OPERATORS and OperatorLocale serve the legacy UI contract. All builders only produce data; server schema, authorization and storage semantics remain server responsibilities.

## Complete example

```ts
import {
  filter,
  StringComparison,
  DeletionState,
} from '@ahoo-wang/wow-client';
export const activeUsers = filter.and([
  filter.deletion(DeletionState.ACTIVE),
  filter.contains('state.name', 'ada', StringComparison.CASE_INSENSITIVE),
  filter.between('state.age', 18, 65),
  filter.elementMatch('state.roles', filter.eq('name', 'editor')),
]);
console.assert(activeUsers.operands.length === 4);
let rejected = false;
try {
  filter.isIn('state.role', []);
} catch {
  rejected = true;
}
console.assert(rejected);
```

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. Locate inherited and related types through the [symbol index](./symbols). Runtime defaults and failure behavior are described above.

## FilterExpression builders and types {#filter-contracts}

### filter {#api-filter}

::: details Expand all fields and members

```ts
declare const filter: {
  matchAll(): MatchFilter;
  matchNone(): MatchFilter;
  id(value: string): MetadataValueFilter;
  ids(values: readonly string[]): MetadataValuesFilter;
  aggregateId(value: string): MetadataValueFilter;
  aggregateIds(values: readonly string[]): MetadataValuesFilter;
  tenantId(value: string): MetadataValueFilter;
  ownerId(value: string): MetadataValueFilter;
  spaceId(value: string): MetadataValueFilter;
  and: {
    <FIELDS extends string>(
      operands: readonly ElementFilterExpression<FIELDS>[],
    ): ElementLogicalFilter<FIELDS>;
    <FIELDS extends string>(
      operands: readonly FilterExpression<FIELDS>[],
    ): LogicalFilter<FIELDS>;
  };
  or: {
    <FIELDS extends string>(
      operands: readonly ElementFilterExpression<FIELDS>[],
    ): ElementLogicalFilter<FIELDS>;
    <FIELDS extends string>(
      operands: readonly FilterExpression<FIELDS>[],
    ): LogicalFilter<FIELDS>;
  };
  nor: {
    <FIELDS extends string>(
      operands: readonly ElementFilterExpression<FIELDS>[],
    ): ElementLogicalFilter<FIELDS>;
    <FIELDS extends string>(
      operands: readonly FilterExpression<FIELDS>[],
    ): LogicalFilter<FIELDS>;
  };
  eq<FIELDS extends string>(
    field: FIELDS,
    value: EqualityFilterValue,
  ): EqualityFilter<FIELDS>;
  ne<FIELDS extends string>(
    field: FIELDS,
    value: EqualityFilterValue,
  ): EqualityFilter<FIELDS>;
  gt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS>;
  gte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS>;
  lt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS>;
  lte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS>;
  contains<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison?: StringComparison,
  ): StringFilter<FIELDS>;
  startsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison?: StringComparison,
  ): StringFilter<FIELDS>;
  endsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison?: StringComparison,
  ): StringFilter<FIELDS>;
  isIn<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS>;
  notIn<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS>;
  containsAll<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS>;
  between<FIELDS extends string>(
    field: FIELDS,
    lowerBound: ComparableFilterLiteral,
    upperBound: ComparableFilterLiteral,
  ): BetweenFilter<FIELDS>;
  isEmpty<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS>;
  isEmptyString<FIELDS extends string>(
    field: FIELDS,
  ): FieldPresenceFilter<FIELDS>;
  isNotEmptyString<FIELDS extends string>(
    field: FIELDS,
  ): FieldPresenceFilter<FIELDS>;
  isNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS>;
  isNotNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS>;
  exists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS>;
  notExists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS>;
  deletion(state: DeletionState): DeletionFilter;
  elementMatch<FIELDS extends string, ELEMENT_FIELDS extends string>(
    field: FIELDS,
    predicate: ElementFilterExpression<ELEMENT_FIELDS>,
  ): ElementMatchFilter<FIELDS, ELEMENT_FIELDS>;
  search<FIELDS extends string>(
    query: string,
    options?: SearchFilterOptions<FIELDS>,
  ): SearchFilter<FIELDS>;
  today<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  beforeToday<FIELDS extends string>(
    field: FIELDS,
    time: string,
    options?: RelativeTimeFilterOptions,
  ): BeforeTodayFilter<FIELDS>;
  tomorrow<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  thisWeek<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  nextWeek<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  lastWeek<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  thisMonth<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  lastMonth<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  yesterday<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  nextMonth<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  lastYear<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  thisYear<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  nextYear<FIELDS extends string>(
    field: FIELDS,
    options?: RelativeTimeFilterOptions,
  ): CalendarFilter<FIELDS>;
  recentDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options?: RelativeTimeFilterOptions,
  ): DaysFilter<FIELDS>;
  earlierDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options?: RelativeTimeFilterOptions,
  ): DaysFilter<FIELDS>;
};
```

:::

[typescript/wow-client/src/dsl/filter/builders.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/builders.ts)

### FilterExpression {#api-FilterExpression}

```ts
export type FilterExpression<FIELDS extends string = string> =
  | MatchFilter
  | MetadataFilter
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
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### DeletionState {#api-DeletionState}

```ts
export enum DeletionState {
  ACTIVE = 'ACTIVE',
  DELETED = 'DELETED',
  ALL = 'ALL',
}
```

[typescript/wow-client/src/dsl/deletionState.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/deletionState.ts)

### QueryField {#api-QueryField}

```ts
export type QueryField<FIELDS extends string = string> = FIELDS;
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### FilterLiteral {#api-FilterLiteral}

```ts
export type FilterLiteral = null | string | number | boolean;
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### EqualityFilterValue {#api-EqualityFilterValue}

```ts
export type EqualityFilterValue = FilterLiteral;
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### ComparableFilterLiteral {#api-ComparableFilterLiteral}

```ts
export type ComparableFilterLiteral = Exclude<FilterLiteral, null>;
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### FilterOperator {#api-FilterOperator}

::: details Expand all fields and members

```ts
export enum FilterOperator {
  MATCH_ALL = 'MATCH_ALL',
  MATCH_NONE = 'MATCH_NONE',
  ID = 'ID',
  IDS = 'IDS',
  AGGREGATE_ID = 'AGGREGATE_ID',
  AGGREGATE_IDS = 'AGGREGATE_IDS',
  TENANT_ID = 'TENANT_ID',
  OWNER_ID = 'OWNER_ID',
  SPACE_ID = 'SPACE_ID',
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
  IS_EMPTY_STRING = 'IS_EMPTY_STRING',
  IS_NOT_EMPTY_STRING = 'IS_NOT_EMPTY_STRING',
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
  YESTERDAY = 'YESTERDAY',
  NEXT_MONTH = 'NEXT_MONTH',
  LAST_YEAR = 'LAST_YEAR',
  THIS_YEAR = 'THIS_YEAR',
  NEXT_YEAR = 'NEXT_YEAR',
  RECENT_DAYS = 'RECENT_DAYS',
  EARLIER_DAYS = 'EARLIER_DAYS',
}
```

:::

[typescript/wow-client/src/dsl/filter/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/operator.ts)

### StringComparison {#api-StringComparison}

```ts
export enum StringComparison {
  CASE_SENSITIVE = 'CASE_SENSITIVE',
  CASE_INSENSITIVE = 'CASE_INSENSITIVE',
}
```

[typescript/wow-client/src/dsl/filter/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/operator.ts)

### SearchMode {#api-SearchMode}

```ts
export enum SearchMode {
  TERMS = 'TERMS',
  PHRASE = 'PHRASE',
}
```

[typescript/wow-client/src/dsl/filter/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/operator.ts)

### TimeUnit {#api-TimeUnit}

```ts
export enum TimeUnit {
  NANOSECONDS = 'NANOSECONDS',
  MICROSECONDS = 'MICROSECONDS',
  MILLISECONDS = 'MILLISECONDS',
  SECONDS = 'SECONDS',
  MINUTES = 'MINUTES',
  HOURS = 'HOURS',
  DAYS = 'DAYS',
}
```

[typescript/wow-client/src/dsl/filter/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/operator.ts)

### MatchFilter {#api-MatchFilter}

```ts
export type MatchFilter = {
  op: FilterOperator.MATCH_ALL | FilterOperator.MATCH_NONE;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### MetadataValueFilter {#api-MetadataValueFilter}

```ts
export type MetadataValueFilter = {
  op:
    | FilterOperator.ID
    | FilterOperator.AGGREGATE_ID
    | FilterOperator.TENANT_ID
    | FilterOperator.OWNER_ID
    | FilterOperator.SPACE_ID;
  value: string;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### MetadataValuesFilter {#api-MetadataValuesFilter}

```ts
export type MetadataValuesFilter = {
  op: FilterOperator.IDS | FilterOperator.AGGREGATE_IDS;
  values: string[];
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### MetadataFilter {#api-MetadataFilter}

```ts
export type MetadataFilter = MetadataValueFilter | MetadataValuesFilter;
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### LogicalFilter {#api-LogicalFilter}

```ts
export type LogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: FilterExpression<FIELDS>[];
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### ElementLogicalFilter {#api-ElementLogicalFilter}

```ts
export type ElementLogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: ElementFilterExpression<FIELDS>[];
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### EqualityFilter {#api-EqualityFilter}

```ts
export type EqualityFilter<FIELDS extends string = string> = {
  op: FilterOperator.EQ | FilterOperator.NE;
  field: QueryField<FIELDS>;
  value: EqualityFilterValue;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### ComparisonFilter {#api-ComparisonFilter}

```ts
export type ComparisonFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.GT
    | FilterOperator.GTE
    | FilterOperator.LT
    | FilterOperator.LTE;
  field: QueryField<FIELDS>;
  value: ComparableFilterLiteral;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### StringFilter {#api-StringFilter}

```ts
export type StringFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.CONTAINS
    | FilterOperator.STARTS_WITH
    | FilterOperator.ENDS_WITH;
  field: QueryField<FIELDS>;
  value: string;
  stringComparison?: StringComparison;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### CollectionFilter {#api-CollectionFilter}

```ts
export type CollectionFilter<FIELDS extends string = string> = {
  op: FilterOperator.IN | FilterOperator.NOT_IN | FilterOperator.CONTAINS_ALL;
  field: QueryField<FIELDS>;
  values: ComparableFilterLiteral[];
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### BetweenFilter {#api-BetweenFilter}

```ts
export type BetweenFilter<FIELDS extends string = string> = {
  op: FilterOperator.BETWEEN;
  field: QueryField<FIELDS>;
  lowerBound: ComparableFilterLiteral;
  upperBound: ComparableFilterLiteral;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### FieldPresenceFilter {#api-FieldPresenceFilter}

```ts
export type FieldPresenceFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.IS_EMPTY
    | FilterOperator.IS_EMPTY_STRING
    | FilterOperator.IS_NOT_EMPTY_STRING
    | FilterOperator.IS_NULL
    | FilterOperator.IS_NOT_NULL
    | FilterOperator.EXISTS
    | FilterOperator.NOT_EXISTS;
  field: QueryField<FIELDS>;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### DeletionFilter {#api-DeletionFilter}

```ts
export type DeletionFilter = {
  op: FilterOperator.DELETION;
  state: DeletionState;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### ElementMatchFilter {#api-ElementMatchFilter}

```ts
export type ElementMatchFilter<
  FIELDS extends string = string,
  ELEMENT_FIELDS extends string = string,
> = {
  op: FilterOperator.ELEMENT_MATCH;
  field: QueryField<FIELDS>;
  predicate: ElementFilterExpression<ELEMENT_FIELDS>;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### SearchFilter {#api-SearchFilter}

```ts
export type SearchFilter<FIELDS extends string = string> = {
  op: FilterOperator.SEARCH;
  query: string;
  fields?: QueryField<FIELDS>[];
  mode?: SearchMode;
};
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### SearchFilterOptions {#api-SearchFilterOptions}

```ts
export interface SearchFilterOptions<FIELDS extends string = string> {
  fields?: readonly QueryField<FIELDS>[];
  mode?: SearchMode;
}
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### RelativeTimeFilterOptions {#api-RelativeTimeFilterOptions}

```ts
export interface RelativeTimeFilterOptions {
  zoneId?: string;
  datePattern?: string;
  timeUnit?: TimeUnit;
}
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### CalendarFilter {#api-CalendarFilter}

```ts
export type CalendarFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op:
      | FilterOperator.TODAY
      | FilterOperator.TOMORROW
      | FilterOperator.THIS_WEEK
      | FilterOperator.NEXT_WEEK
      | FilterOperator.LAST_WEEK
      | FilterOperator.THIS_MONTH
      | FilterOperator.LAST_MONTH
      | FilterOperator.YESTERDAY
      | FilterOperator.NEXT_MONTH
      | FilterOperator.LAST_YEAR
      | FilterOperator.THIS_YEAR
      | FilterOperator.NEXT_YEAR;
    field: QueryField<FIELDS>;
  };
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### BeforeTodayFilter {#api-BeforeTodayFilter}

```ts
export type BeforeTodayFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.BEFORE_TODAY;
    field: QueryField<FIELDS>;
    time: string;
  };
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### DaysFilter {#api-DaysFilter}

```ts
export type DaysFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.RECENT_DAYS | FilterOperator.EARLIER_DAYS;
    field: QueryField<FIELDS>;
    days: number;
  };
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### ElementFilterExpression {#api-ElementFilterExpression}

```ts
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
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

### FilterCapable {#api-FilterCapable}

```ts
export interface FilterCapable<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/dsl/filter/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/dsl/filter/types.ts)

## Legacy Condition builders and types {#condition-contracts}

Import these from `@ahoo-wang/wow-client/legacy`. They are deprecated and removed in v10.

### isValidateCondition {#api-isValidateCondition}

```ts
export function isValidateCondition(
  condition: Condition | undefined | null,
): condition is Condition;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ignoreCaseOptions {#api-ignoreCaseOptions}

```ts
export function ignoreCaseOptions(
  ignoreCase?: boolean,
): ConditionOptions | undefined;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### dateOptions {#api-dateOptions}

```ts
export function dateOptions(
  datePattern?: string,
  zoneId?: string,
): ConditionOptions | undefined;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### and {#api-and}

```ts
export function and<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### or {#api-or}

```ts
export function or<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### nor {#api-nor}

```ts
export function nor<FIELDS extends string = string>(
  ...conditions: Condition<FIELDS>[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### id {#api-id}

```ts
export function id<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ids {#api-ids}

```ts
export function ids<FIELDS extends string = string>(
  value: string[],
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### aggregateId {#api-aggregateId}

```ts
export function aggregateId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### aggregateIds {#api-aggregateIds}

```ts
export function aggregateIds<FIELDS extends string = string>(
  value: string[],
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### tenantId {#api-tenantId}

```ts
export function tenantId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ownerId {#api-ownerId}

```ts
export function ownerId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### spaceId {#api-spaceId}

```ts
export function spaceId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### deleted {#api-deleted}

```ts
export function deleted<FIELDS extends string = string>(
  value: DeletionState,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### active {#api-active}

```ts
export function active<FIELDS extends string = string>(): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### all {#api-all}

```ts
export function all<FIELDS extends string = string>(): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### eq {#api-eq}

```ts
export function eq<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ne {#api-ne}

```ts
export function ne<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### gt {#api-gt}

```ts
export function gt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### lt {#api-lt}

```ts
export function lt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### gte {#api-gte}

```ts
export function gte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### lte {#api-lte}

```ts
export function lte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### contains {#api-contains}

```ts
export function contains<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### isIn {#api-isIn}

```ts
export function isIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### notIn {#api-notIn}

```ts
export function notIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### between {#api-between}

```ts
export function between<FIELDS extends string = string>(
  field: FIELDS,
  start: any,
  end: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### allIn {#api-allIn}

```ts
export function allIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### startsWith {#api-startsWith}

```ts
export function startsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### match {#api-match}

```ts
export function match<FIELDS extends string = string>(
  field: FIELDS,
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### endsWith {#api-endsWith}

```ts
export function endsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### elemMatch {#api-elemMatch}

```ts
export function elemMatch<FIELDS extends string = string>(
  field: FIELDS,
  value: Condition<FIELDS>,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### isNull {#api-isNull}

```ts
export function isNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### notNull {#api-notNull}

```ts
export function notNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### isTrue {#api-isTrue}

```ts
export function isTrue<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### isFalse {#api-isFalse}

```ts
export function isFalse<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### exists {#api-exists}

```ts
export function exists<FIELDS extends string = string>(
  field: FIELDS,
  exists?: boolean,
): Condition<FIELDS>;
```

Implementation defaults: `exists = true`.

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### today {#api-today}

```ts
export function today<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### beforeToday {#api-beforeToday}

```ts
export function beforeToday<FIELDS extends string = string>(
  field: FIELDS,
  time: any,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### tomorrow {#api-tomorrow}

```ts
export function tomorrow<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### thisWeek {#api-thisWeek}

```ts
export function thisWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### nextWeek {#api-nextWeek}

```ts
export function nextWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### lastWeek {#api-lastWeek}

```ts
export function lastWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### thisMonth {#api-thisMonth}

```ts
export function thisMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### lastMonth {#api-lastMonth}

```ts
export function lastMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### recentDays {#api-recentDays}

```ts
export function recentDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### earlierDays {#api-earlierDays}

```ts
export function earlierDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### raw {#api-raw}

```ts
export function raw<FIELDS extends string = string>(
  raw: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ConditionOptionKey {#api-ConditionOptionKey}

```ts
export class ConditionOptionKey {
  static readonly IGNORE_CASE_OPTION_KEY = 'ignoreCase';
  static readonly ZONE_ID_OPTION_KEY = 'zoneId';
  static readonly DATE_PATTERN_OPTION_KEY = 'datePattern';
}
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ConditionOptions {#api-ConditionOptions}

```ts
export interface ConditionOptions {
  ignoreCase?: boolean;
  datePattern?: string;
  zoneId?: string;
  [key: string]: any;
}
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### Condition {#api-Condition}

```ts
export interface Condition<FIELDS extends string = string> {
  field?: FIELDS;
  operator?: Operator;
  value?: any;
  children?: Condition<FIELDS>[];
  options?: ConditionOptions;
}
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### ConditionCapable {#api-ConditionCapable}

```ts
export interface ConditionCapable<FIELDS extends string = string> {
  condition: Condition<FIELDS>;
}
```

[typescript/wow-client/src/legacy/condition.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts)

### Operator {#api-Operator}

::: details Expand all fields and members

```ts
export enum Operator {
  AND = 'AND',
  OR = 'OR',
  NOR = 'NOR',
  ID = 'ID',
  IDS = 'IDS',
  AGGREGATE_ID = 'AGGREGATE_ID',
  AGGREGATE_IDS = 'AGGREGATE_IDS',
  TENANT_ID = 'TENANT_ID',
  OWNER_ID = 'OWNER_ID',
  SPACE_ID = 'SPACE_ID',
  DELETED = 'DELETED',
  ALL = 'ALL',
  EQ = 'EQ',
  NE = 'NE',
  GT = 'GT',
  LT = 'LT',
  GTE = 'GTE',
  LTE = 'LTE',
  CONTAINS = 'CONTAINS',
  IN = 'IN',
  NOT_IN = 'NOT_IN',
  BETWEEN = 'BETWEEN',
  ALL_IN = 'ALL_IN',
  STARTS_WITH = 'STARTS_WITH',
  ENDS_WITH = 'ENDS_WITH',
  ELEM_MATCH = 'ELEM_MATCH',
  NULL = 'NULL',
  NOT_NULL = 'NOT_NULL',
  TRUE = 'TRUE',
  FALSE = 'FALSE',
  EXISTS = 'EXISTS',
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
  MATCH = 'MATCH',
  RAW = 'RAW',
}
```

:::

[typescript/wow-client/src/legacy/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts)

### LOGICAL_OPERATORS {#api-LOGICAL_OPERATORS}

```ts
declare const LOGICAL_OPERATORS: Set<Operator>;
```

[typescript/wow-client/src/legacy/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts)

### EMPTY_VALUE_OPERATORS {#api-EMPTY_VALUE_OPERATORS}

```ts
declare const EMPTY_VALUE_OPERATORS: Set<Operator>;
```

[typescript/wow-client/src/legacy/operator.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts)

### OperatorLocale {#api-OperatorLocale}

```ts
export type OperatorLocale = {
  [K in Operator]: string;
};
```

[typescript/wow-client/src/legacy/locale/operatorLocale.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/operatorLocale.ts)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)
