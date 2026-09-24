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

QueryField/LogicalField are string type aliases. Builders additionally reject invalid logical paths: segments start with a letter/underscore (optionally @), continue with letters/digits/underscore/hyphen, and allow numeric segments after a dot. RelativeTimeFilterOptions defaults timeUnit to MILLISECONDS, leaves zoneId/datePattern absent; it validates explicit offset zones and Java date-pattern syntax, but does not prove an arbitrary named zone exists on the server. No clock calculation is done in the browser. Invalid values/options throw TypeError before a request. Literal object creation can bypass these runtime builder checks; TypeScript alone is not validation.

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

[typescript/wow-client/src/query/filter.ts:710](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L710)

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

[typescript/wow-client/src/query/filter.ts:634](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L634)

### DeletionState {#api-DeletionState}

```ts
export enum DeletionState {
  ACTIVE = 'ACTIVE',
  DELETED = 'DELETED',
  ALL = 'ALL',
}
```

[typescript/wow-client/src/query/deletionState.ts:19](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/deletionState.ts#L19)

### QueryField {#api-QueryField}

```ts
export type QueryField<FIELDS extends string = string> = FIELDS;
```

[typescript/wow-client/src/query/filter.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L24)

### LogicalField {#api-LogicalField}

```ts
export type LogicalField<FIELDS extends string = string> = QueryField<FIELDS>;
```

[typescript/wow-client/src/query/filter.ts:26](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L26)

### FilterLiteral {#api-FilterLiteral}

```ts
export type FilterLiteral = null | string | number | boolean;
```

[typescript/wow-client/src/query/filter.ts:30](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L30)

### EqualityFilterValue {#api-EqualityFilterValue}

```ts
export type EqualityFilterValue = FilterLiteral;
```

[typescript/wow-client/src/query/filter.ts:34](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L34)

### ComparableFilterLiteral {#api-ComparableFilterLiteral}

```ts
export type ComparableFilterLiteral = Exclude<FilterLiteral, null>;
```

[typescript/wow-client/src/query/filter.ts:38](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L38)

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

[typescript/wow-client/src/query/filter.ts:44](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L44)

### StringComparison {#api-StringComparison}

```ts
export enum StringComparison {
  CASE_SENSITIVE = 'CASE_SENSITIVE',
  CASE_INSENSITIVE = 'CASE_INSENSITIVE',
}
```

[typescript/wow-client/src/query/filter.ts:100](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L100)

### SearchMode {#api-SearchMode}

```ts
export enum SearchMode {
  TERMS = 'TERMS',
  PHRASE = 'PHRASE',
}
```

[typescript/wow-client/src/query/filter.ts:114](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L114)

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

[typescript/wow-client/src/query/filter.ts:130](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L130)

### MatchFilter {#api-MatchFilter}

```ts
export type MatchFilter = {
  op: FilterOperator.MATCH_ALL | FilterOperator.MATCH_NONE;
};
```

[typescript/wow-client/src/query/filter.ts:373](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L373)

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

[typescript/wow-client/src/query/filter.ts:381](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L381)

### MetadataValuesFilter {#api-MetadataValuesFilter}

```ts
export type MetadataValuesFilter = {
  op: FilterOperator.IDS | FilterOperator.AGGREGATE_IDS;
  values: string[];
};
```

[typescript/wow-client/src/query/filter.ts:394](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L394)

### MetadataFilter {#api-MetadataFilter}

```ts
export type MetadataFilter = MetadataValueFilter | MetadataValuesFilter;
```

[typescript/wow-client/src/query/filter.ts:402](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L402)

### LogicalFilter {#api-LogicalFilter}

```ts
export type LogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: FilterExpression<FIELDS>[];
};
```

[typescript/wow-client/src/query/filter.ts:407](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L407)

### ElementLogicalFilter {#api-ElementLogicalFilter}

```ts
export type ElementLogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: ElementFilterExpression<FIELDS>[];
};
```

[typescript/wow-client/src/query/filter.ts:416](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L416)

### EqualityFilter {#api-EqualityFilter}

```ts
export type EqualityFilter<FIELDS extends string = string> = {
  op: FilterOperator.EQ | FilterOperator.NE;
  field: QueryField<FIELDS>;
  value: EqualityFilterValue;
};
```

[typescript/wow-client/src/query/filter.ts:425](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L425)

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

[typescript/wow-client/src/query/filter.ts:434](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L434)

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

[typescript/wow-client/src/query/filter.ts:448](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L448)

### CollectionFilter {#api-CollectionFilter}

```ts
export type CollectionFilter<FIELDS extends string = string> = {
  op: FilterOperator.IN | FilterOperator.NOT_IN | FilterOperator.CONTAINS_ALL;
  field: QueryField<FIELDS>;
  values: ComparableFilterLiteral[];
};
```

[typescript/wow-client/src/query/filter.ts:462](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L462)

### BetweenFilter {#api-BetweenFilter}

```ts
export type BetweenFilter<FIELDS extends string = string> = {
  op: FilterOperator.BETWEEN;
  field: QueryField<FIELDS>;
  lowerBound: ComparableFilterLiteral;
  upperBound: ComparableFilterLiteral;
};
```

[typescript/wow-client/src/query/filter.ts:471](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L471)

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

[typescript/wow-client/src/query/filter.ts:482](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L482)

### DeletionFilter {#api-DeletionFilter}

```ts
export type DeletionFilter = {
  op: FilterOperator.DELETION;
  state: DeletionState;
};
```

[typescript/wow-client/src/query/filter.ts:497](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L497)

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

[typescript/wow-client/src/query/filter.ts:506](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L506)

### SearchFilter {#api-SearchFilter}

```ts
export type SearchFilter<FIELDS extends string = string> = {
  op: FilterOperator.SEARCH;
  query: string;
  fields?: QueryField<FIELDS>[];
  mode?: SearchMode;
};
```

[typescript/wow-client/src/query/filter.ts:519](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L519)

### SearchFilterOptions {#api-SearchFilterOptions}

```ts
export interface SearchFilterOptions<FIELDS extends string = string> {
  fields?: readonly QueryField<FIELDS>[];
  mode?: SearchMode;
}
```

[typescript/wow-client/src/query/filter.ts:532](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L532)

### RelativeTimeFilterOptions {#api-RelativeTimeFilterOptions}

```ts
export interface RelativeTimeFilterOptions {
  zoneId?: string;
  datePattern?: string;
  timeUnit?: TimeUnit;
}
```

[typescript/wow-client/src/query/filter.ts:547](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L547)

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

[typescript/wow-client/src/query/filter.ts:573](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L573)

### BeforeTodayFilter {#api-BeforeTodayFilter}

```ts
export type BeforeTodayFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.BEFORE_TODAY;
    field: QueryField<FIELDS>;
    time: string;
  };
```

[typescript/wow-client/src/query/filter.ts:594](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L594)

### DaysFilter {#api-DaysFilter}

```ts
export type DaysFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.RECENT_DAYS | FilterOperator.EARLIER_DAYS;
    field: QueryField<FIELDS>;
    days: number;
  };
```

[typescript/wow-client/src/query/filter.ts:605](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L605)

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

[typescript/wow-client/src/query/filter.ts:616](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L616)

### FilterCapable {#api-FilterCapable}

```ts
export interface FilterCapable<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
}
```

[typescript/wow-client/src/query/filter.ts:654](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/query/filter.ts#L654)

## Legacy Condition builders and types {#condition-contracts}

Import these from `@ahoo-wang/wow-client/legacy`. They are deprecated and removed in v10.

### isValidateCondition {#api-isValidateCondition}

```ts
export function isValidateCondition(
  condition: Condition | undefined | null,
): condition is Condition;
```

[typescript/wow-client/src/legacy/condition.ts:24](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L24)

### ignoreCaseOptions {#api-ignoreCaseOptions}

```ts
export function ignoreCaseOptions(
  ignoreCase?: boolean,
): ConditionOptions | undefined;
```

[typescript/wow-client/src/legacy/condition.ts:91](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L91)

### dateOptions {#api-dateOptions}

```ts
export function dateOptions(
  datePattern?: string,
  zoneId?: string,
): ConditionOptions | undefined;
```

[typescript/wow-client/src/legacy/condition.ts:109](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L109)

### and {#api-and}

```ts
export function and<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:183](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L183)

### or {#api-or}

```ts
export function or<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:219](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L219)

### nor {#api-nor}

```ts
export function nor<FIELDS extends string = string>(
  ...conditions: Condition<FIELDS>[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:238](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L238)

### id {#api-id}

```ts
export function id<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:254](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L254)

### ids {#api-ids}

```ts
export function ids<FIELDS extends string = string>(
  value: string[],
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:267](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L267)

### aggregateId {#api-aggregateId}

```ts
export function aggregateId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:280](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L280)

### aggregateIds {#api-aggregateIds}

```ts
export function aggregateIds<FIELDS extends string = string>(
  value: string[],
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:293](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L293)

### tenantId {#api-tenantId}

```ts
export function tenantId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:306](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L306)

### ownerId {#api-ownerId}

```ts
export function ownerId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:319](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L319)

### spaceId {#api-spaceId}

```ts
export function spaceId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:332](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L332)

### deleted {#api-deleted}

```ts
export function deleted<FIELDS extends string = string>(
  value: DeletionState,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:345](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L345)

### active {#api-active}

```ts
export function active<FIELDS extends string = string>(): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:358](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L358)

### all {#api-all}

```ts
export function all<FIELDS extends string = string>(): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:368](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L368)

### eq {#api-eq}

```ts
export function eq<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:382](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L382)

### ne {#api-ne}

```ts
export function ne<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:397](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L397)

### gt {#api-gt}

```ts
export function gt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:412](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L412)

### lt {#api-lt}

```ts
export function lt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:427](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L427)

### gte {#api-gte}

```ts
export function gte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:442](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L442)

### lte {#api-lte}

```ts
export function lte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:457](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L457)

### contains {#api-contains}

```ts
export function contains<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:473](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L473)

### isIn {#api-isIn}

```ts
export function isIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:491](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L491)

### notIn {#api-notIn}

```ts
export function notIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:506](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L506)

### between {#api-between}

```ts
export function between<FIELDS extends string = string>(
  field: FIELDS,
  start: any,
  end: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:522](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L522)

### allIn {#api-allIn}

```ts
export function allIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:538](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L538)

### startsWith {#api-startsWith}

```ts
export function startsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:554](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L554)

### match {#api-match}

```ts
export function match<FIELDS extends string = string>(
  field: FIELDS,
  value: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:572](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L572)

### endsWith {#api-endsWith}

```ts
export function endsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:588](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L588)

### elemMatch {#api-elemMatch}

```ts
export function elemMatch<FIELDS extends string = string>(
  field: FIELDS,
  value: Condition<FIELDS>,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:606](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L606)

### isNull {#api-isNull}

```ts
export function isNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:620](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L620)

### notNull {#api-notNull}

```ts
export function notNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:633](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L633)

### isTrue {#api-isTrue}

```ts
export function isTrue<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:646](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L646)

### isFalse {#api-isFalse}

```ts
export function isFalse<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:659](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L659)

### exists {#api-exists}

```ts
export function exists<FIELDS extends string = string>(
  field: FIELDS,
  exists?: boolean,
): Condition<FIELDS>;
```

Implementation defaults: `exists = true`.

[typescript/wow-client/src/legacy/condition.ts:673](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L673)

### today {#api-today}

```ts
export function today<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:689](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L689)

### beforeToday {#api-beforeToday}

```ts
export function beforeToday<FIELDS extends string = string>(
  field: FIELDS,
  time: any,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:708](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L708)

### tomorrow {#api-tomorrow}

```ts
export function tomorrow<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:727](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L727)

### thisWeek {#api-thisWeek}

```ts
export function thisWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:745](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L745)

### nextWeek {#api-nextWeek}

```ts
export function nextWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:763](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L763)

### lastWeek {#api-lastWeek}

```ts
export function lastWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:781](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L781)

### thisMonth {#api-thisMonth}

```ts
export function thisMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:799](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L799)

### lastMonth {#api-lastMonth}

```ts
export function lastMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:817](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L817)

### recentDays {#api-recentDays}

```ts
export function recentDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:836](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L836)

### earlierDays {#api-earlierDays}

```ts
export function earlierDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:856](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L856)

### raw {#api-raw}

```ts
export function raw<FIELDS extends string = string>(
  raw: any,
): Condition<FIELDS>;
```

[typescript/wow-client/src/legacy/condition.ts:877](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L877)

### ConditionOptionKey {#api-ConditionOptionKey}

```ts
export class ConditionOptionKey {
  static readonly IGNORE_CASE_OPTION_KEY = 'ignoreCase';
  static readonly ZONE_ID_OPTION_KEY = 'zoneId';
  static readonly DATE_PATTERN_OPTION_KEY = 'datePattern';
}
```

[typescript/wow-client/src/legacy/condition.ts:37](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L37)

### ConditionOptions {#api-ConditionOptions}

```ts
export interface ConditionOptions {
  ignoreCase?: boolean;
  datePattern?: string;
  zoneId?: string;
  [key: string]: any;
}
```

[typescript/wow-client/src/legacy/condition.ts:62](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L62)

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

[typescript/wow-client/src/legacy/condition.ts:133](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L133)

### ConditionCapable {#api-ConditionCapable}

```ts
export interface ConditionCapable<FIELDS extends string = string> {
  condition: Condition<FIELDS>;
}
```

[typescript/wow-client/src/legacy/condition.ts:165](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/condition.ts#L165)

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

[typescript/wow-client/src/legacy/operator.ts:15](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts#L15)

### LOGICAL_OPERATORS {#api-LOGICAL_OPERATORS}

```ts
declare const LOGICAL_OPERATORS: Set<Operator>;
```

[typescript/wow-client/src/legacy/operator.ts:257](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts#L257)

### EMPTY_VALUE_OPERATORS {#api-EMPTY_VALUE_OPERATORS}

```ts
declare const EMPTY_VALUE_OPERATORS: Set<Operator>;
```

[typescript/wow-client/src/legacy/operator.ts:264](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/operator.ts#L264)

### OperatorLocale {#api-OperatorLocale}

```ts
export type OperatorLocale = {
  [K in Operator]: string;
};
```

[typescript/wow-client/src/legacy/locale/operatorLocale.ts:17](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-client/src/legacy/locale/operatorLocale.ts#L17)

## Related topics

[Client configuration and metadata](./configuration) · [Commands and wait results](./commands) · [Snapshot queries](./snapshot-queries) · [Projection, sorting and pagination](./query-options) · [Cursor queries](./cursor-queries) · [Aggregation builders](./aggregations) · [Events and historical state](./events-and-history) · [Identity and resource attribution](./identity-and-attribution)
