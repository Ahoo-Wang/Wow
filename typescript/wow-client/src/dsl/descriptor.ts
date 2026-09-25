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

// The capability descriptor `GET {aggregate}/{snapshot|event}/schema`
// answers with: `wow.api.query.QueryModelDescriptor` of Wow 9.2, mirrored
// from `wow-api/src/main/kotlin/me/ahoo/wow/api/query/descriptor/`.
//
// Kotlin leaves a null out of the JSON of every descriptor class annotated
// `@JsonInclude(NON_NULL)`, so those properties are optional here; the
// limits are not annotated and send `null`. A set the OpenAPI document
// describes as a plain string is open: a known union plus `string & {}`.

import type {
  AggregationDatePart,
  AggregationDateUnit,
  DateDiffUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
} from './aggregation/types.js';
import type { DeletionState } from './deletionState.js';
import type { FilterOperator, SearchMode, TimeUnit } from './filter/index.js';

/**
 * The query models Wow names in a descriptor's `model`. The set is open: a
 * server may register a model of its own, so a descriptor's `model` is a
 * {@link QueryModel}.
 */
export const QueryModels = Object.freeze({
  /** The snapshot query model: `{aggregate}/snapshot/*`. */
  SNAPSHOT: 'SNAPSHOT',
  /** The event stream query model: `{aggregate}/event/*`. */
  EVENT_STREAM: 'EVENT_STREAM',
} as const);

/** A query model's name: one of {@link QueryModels}, or one a server added. */
export type QueryModel =
  (typeof QueryModels)[keyof typeof QueryModels] | (string & {});

/**
 * The value types Wow names in a field's `types`. The set is open, like
 * {@link QueryModels}.
 */
export const QueryValueTypes = Object.freeze({
  /** A JSON string. */
  STRING: 'STRING',
  /** A whole number. */
  INTEGER: 'INTEGER',
  /** A number with a fraction. */
  DECIMAL: 'DECIMAL',
  /** `true` or `false`. */
  BOOLEAN: 'BOOLEAN',
  /** A JSON object. */
  OBJECT: 'OBJECT',
} as const);

/** A field's value type: one of {@link QueryValueTypes}, or one a server added. */
export type QueryValueType =
  (typeof QueryValueTypes)[keyof typeof QueryValueTypes] | (string & {});

/** The structural shape of a field's value, sent as its `kind`. */
export enum QueryValueKind {
  /** Wow could not tell. */
  UNKNOWN = 'UNKNOWN',
  /** Always `null`. */
  NULL = 'NULL',
  /** A single string, number or boolean. */
  SCALAR = 'SCALAR',
  /** An object. */
  OBJECT = 'OBJECT',
  /** An array. */
  ARRAY = 'ARRAY',
  /** One of several of the above. */
  UNION = 'UNION',
}

/** The ways a model can be read page by page, listed in `record.paging`. */
export enum PagingMode {
  /** `list` and `listStream`: the first `limit` rows. */
  LIST = 'LIST',
  /** `paged`: numbered pages with a total. */
  PAGED = 'PAGED',
  /** `cursor`: forward-only pages after the last row seen. */
  CURSOR = 'CURSOR',
}

/**
 * The system roles Wow names in a field's `role`. The set is open, like
 * {@link QueryModels}.
 */
export const QueryFieldRoles = Object.freeze({
  /** The record's own identity, `record.identity`. */
  IDENTITY: 'IDENTITY',
  /** The aggregate id. */
  AGGREGATE_ID: 'AGGREGATE_ID',
  /** The tenant id. */
  TENANT_ID: 'TENANT_ID',
  /** The owner id. */
  OWNER_ID: 'OWNER_ID',
  /** The space id. */
  SPACE_ID: 'SPACE_ID',
  /** Whether the aggregate is deleted. */
  DELETED: 'DELETED',
} as const);

/** A system field's role: one of {@link QueryFieldRoles}, or one a server added. */
export type QueryFieldRole =
  (typeof QueryFieldRoles)[keyof typeof QueryFieldRoles] | (string & {});

/**
 * The combination rules Wow names in a descriptor's `constraints`: rules
 * that no single capability shows. The set is open, like {@link QueryModels}.
 */
export const QueryConstraintTypes = Object.freeze({
  /**
   * A cursor's sort always ends with the identity field; the server appends
   * it (the constraint's `appended`) when the sort does not.
   */
  CURSOR_UNIQUE_SORT: 'CURSOR_UNIQUE_SORT',
  /** A count or paged query must not match every record. */
  COUNT_REQUIRES_FILTER: 'COUNT_REQUIRES_FILTER',
  /** `STARTS_WITH` needs a non-empty, case-sensitive prefix. */
  STARTS_WITH_REQUIRES_PREFIX: 'STARTS_WITH_REQUIRES_PREFIX',
  /**
   * A sort names at most one of the constraint's `fields`: they are
   * array-valued and the storage cannot order by two independent arrays.
   * Fields on one array path, one nested in the other, may still combine.
   * A sort that breaks it is refused with `PARALLEL_ARRAY_SORT`.
   */
  PARALLEL_ARRAY_SORT: 'PARALLEL_ARRAY_SORT',
  /**
   * On the constraint's `fields`, a stored `null` or empty array cannot be
   * told from a missing field: `EXISTS` does not match it, `NOT_EXISTS` and
   * `IS_NULL` do, and `IS_EMPTY` also matches a missing or `null` field. A
   * presence filter on such a field answers "has a non-empty value", not
   * "has the key"; say so to a user who writes one.
   */
  NULL_OR_EMPTY_AS_MISSING: 'NULL_OR_EMPTY_AS_MISSING',
  /**
   * Model-wide, without `fields`: `EQ` and `NE` take only a scalar operand,
   * because the storage cannot compare a whole array. Match an array
   * field's elements instead (`IN`, `CONTAINS_ALL`, `ELEMENT_MATCH`); an `EQ` or
   * `NE` with an array operand is refused with `ARRAY_EQUALITY`.
   */
  ARRAY_EQUALITY: 'ARRAY_EQUALITY',
} as const);

/** A constraint's type: one of {@link QueryConstraintTypes}, or one a server added. */
export type QueryConstraintType =
  | (typeof QueryConstraintTypes)[keyof typeof QueryConstraintTypes]
  | (string & {});

/** A field that holds a calendar date. */
export interface TemporalDate {
  /** The discriminator. */
  type: 'TEMPORAL_DATE';
}

/** A field that holds a number of time units since the epoch. */
export interface TemporalEpoch {
  /** The discriminator. */
  type: 'TEMPORAL_EPOCH';
  /** The unit the number counts; milliseconds when absent. */
  timeUnit?: TimeUnit;
}

/** A field that holds a time written as text in a `java.time` pattern. */
export interface TemporalFormatted {
  /** The discriminator. */
  type: 'TEMPORAL_FORMATTED';
  /** The `java.time` pattern, such as `yyyy-MM-dd`. */
  pattern: string;
}

/**
 * What a field's value means beyond its type, sent as its `semantic`: today
 * one of the three temporal kinds, which the relative time filters
 * (`TODAY`, `RECENT_DAYS`, …) and date histograms need.
 */
export type QuerySemanticType =
  TemporalDate | TemporalEpoch | TemporalFormatted;

/** One declared value of an enum field, with its description when it has one. */
export interface EnumValueDescriptor {
  /** The value as it is stored and filtered by. */
  value: unknown;
  /**
   * What the value means, when the model describes it: set by the `enum`
   * entry of the model's declaration file.
   */
  description?: string;
}

/**
 * How much Wow protects a sensitive field's raw value, sent as its
 * `sensitivity.level`. Both levels mask the value in every query result;
 * they differ in what a query may do with the raw value.
 */
export enum SensitivityLevel {
  /**
   * Masked in results. Filters and paged sorts still compare the raw value,
   * unless the server turned that off; grouping, `ANY`, field metrics,
   * arithmetic references and cursor sorts are refused.
   */
  DISPLAY = 'DISPLAY',
  /**
   * Masked in results and never compared: no filter, sort, search, grouping
   * or metric may name it.
   */
  CONFIDENTIAL = 'CONFIDENTIAL',
}

/**
 * Why a field is deprecated. The field can still be queried, but new
 * queries and view definitions should avoid it.
 */
export interface QueryDeprecation {
  /**
   * Why, or what to use instead; absent or `null` when the declaration gave
   * no reason.
   */
  message?: string | null;
}

/** How a sensitive field is protected. */
export interface SensitivityDescriptor {
  /** How much its raw value is protected. */
  level: SensitivityLevel;
  /**
   * Whether filters and paged sorts may still compare its raw value: `false`
   * for {@link SensitivityLevel.CONFIDENTIAL}, and for
   * {@link SensitivityLevel.DISPLAY} when the server turned comparison off.
   * A field that is not comparable lists no filter operators, has
   * `sort.paged` `false` and is left out of `record.search`; naming it in a
   * filter, sort or search is refused with `PROTECTED_COMPARISON`.
   */
  comparable: boolean;
}

/** The filter operators a field admits. */
export interface FieldFilterDescriptor {
  /**
   * Each operator admitted on this field when used alone; empty when none,
   * as for a sensitive field that is not comparable.
   */
  operators: FilterOperator[];
}

/** Where a field may appear in a sort. */
export interface FieldSortDescriptor {
  /** In the sort of a list or paged query; never for a field that is not comparable. */
  paged: boolean;
  /** In the sort of a cursor query. */
  cursor: boolean;
}

/** How a field may be used in an aggregation. */
export interface FieldAggregateDescriptor {
  /**
   * The group types it may be grouped by; a time field lists both
   * `DATE_HISTOGRAM` and `DATE_PART`.
   */
  groups: (AggregationGroupType | (string & {}))[];
  /** Whether a terms group on it may set `missingKey`. */
  missingKey: boolean;
  /** The numeric functions (`SUM`, `AVG`, …) it may feed. */
  functions: (AggregationFunction | (string & {}))[];
  /** Whether it may feed a `DISTINCT_COUNT` metric. */
  distinctCount: boolean;
  /** Whether it may feed a `PERCENTILE` metric. */
  percentile: boolean;
  /** Whether it may feed an `ANY` metric. */
  any: boolean;
  /** Whether a `FIRST` or `LAST` metric may read its value. */
  firstLast: boolean;
  /** Whether it may appear in an arithmetic expression. */
  expressionInput: boolean;
  /** Whether a metric's filter may name it. */
  inMetricFilter: boolean;
}

/** One queryable field, by the logical path a query names it with. */
export interface FieldDescriptor {
  /** The logical path, such as `state.items.productId`. */
  path: string;
  /** The system role of a system field; absent on ordinary fields. */
  role?: QueryFieldRole;
  /** The value types it holds. */
  types: QueryValueType[];
  /** The structural shape of its value. */
  kind: QueryValueKind;
  /** Whether it may be `null`. */
  nullable: boolean;
  /** What its value means beyond its type; absent when nothing more. */
  semantic?: QuerySemanticType;
  /** Its declared values; never listed for a protected field. */
  enum?: EnumValueDescriptor[];
  /** Its description, when the model gives one. */
  description?: string;
  /** How it is protected; absent when it is not sensitive. */
  sensitivity?: SensitivityDescriptor;
  /** Whether a projection may select it. */
  project: boolean;
  /** The filter operators it admits. */
  filter: FieldFilterDescriptor;
  /** Where it may be sorted by. */
  sort: FieldSortDescriptor;
  /** How it may be aggregated; absent when it cannot be at all. */
  aggregate?: FieldAggregateDescriptor;
  /**
   * The element (array field) it lives in, by path; absent at the record
   * level. A filter on it goes inside `ELEMENT_MATCH` on that element.
   */
  scope?: string;
  /**
   * Set when the field is deprecated: still queryable, but new queries
   * should avoid it. An object, so it is truthy even without a message.
   */
  deprecated?: QueryDeprecation;
  /**
   * Other paths a query may name this field by, sorted; empty when none.
   * The server replaces an alias with `path` before it checks and runs a
   * query, so results, projections and errors use `path`, and an alias is
   * never listed as a field of its own. A variant's field lists them
   * relative to the element, like its `path`.
   */
  aliases: string[];
}

/** An array field whose elements can be filtered or aggregated one by one. */
export interface ElementDescriptor {
  /** The array field's logical path. */
  path: string;
  /** Whether `ELEMENT_MATCH` may filter its elements. */
  filter: boolean;
  /** Whether an aggregation may run over its elements. */
  aggregate: boolean;
  /**
   * Full-text search on its fields: a `SEARCH` that names them, relative
   * to the element, inside an `ELEMENT_MATCH` on it. Absent when the
   * storage can search none of them, as on MongoDB.
   */
  search?: SearchDescriptor;
}

/**
 * The fields under a map's dynamic keys, written with `{key}` in place of
 * the key: any key matches, except those in `excludedKeys`. Each pattern
 * has one entry, resolved as the server resolves a concrete key: a pattern
 * whose values are arrays is one `ARRAY` entry, its items implicit.
 */
export interface DynamicFieldDescriptor {
  /** The path pattern, such as `state.attributes.{key}`. */
  pattern: string;
  /** The value types they hold. */
  types: QueryValueType[];
  /** The structural shape of their values. */
  kind: QueryValueKind;
  /** The filter operators they admit. */
  filter: FieldFilterDescriptor;
  /**
   * The map's keys that are declared as fields of their own and so are not
   * covered by this pattern; absent when there are none. For
   * `state.attributes.{key}` with `state.attributes.color` declared, it is
   * `['color']`: a filter on `state.attributes.color` takes that field's
   * entry in `fields`, with its own operators, not this pattern's.
   */
  excludedKeys?: string[];
}

/**
 * Full-text search in one scope: the record's (`record.search`) or an
 * element's (`elements[].search`).
 */
export interface SearchDescriptor {
  /**
   * For the record, the modes of a model-wide `SEARCH` (without fields);
   * empty when one of its fields is not comparable, since a search across
   * the model would match that field. For an element, the modes every
   * listed field accepts.
   */
  modes: SearchMode[];
  /**
   * The fields a `SEARCH` may name, by full logical path; never one that is
   * not comparable. For the record, only record-level fields, never one
   * inside an element; for an element, its own fields, which the query
   * writes relative to the element.
   */
  fields: string[];
}

/** What holds for the model's records as a whole. */
export interface RecordDescriptor {
  /** The logical path of the field that identifies a record. */
  identity: string;
  /** The ways the model can be read page by page. */
  paging: PagingMode[];
  /** The deletion scope a query without one gets; absent when the model has none. */
  defaultScope?: DeletionState;
  /**
   * Operators that name no field of their own: the system-field ones, such
   * as `ID` or `TENANT_ID`, and `EXPRESSION`, whose expression names its
   * fields, where the server allows expensive operators.
   */
  rootOperators: FilterOperator[];
  /** Model-wide full-text search; absent when the model offers none. */
  search?: SearchDescriptor;
}

/** The fixed limits of an aggregation query. */
export interface AggregationLimitsDescriptor {
  /** Most groups in `groupBy`. */
  maxGroups: number;
  /** Most metrics. */
  maxMetrics: number;
  /** Most elements an aggregation runs over. */
  maxElements: number;
  /** Largest `limit` on the result rows. */
  maxLimit: number;
  /** Deepest arithmetic expression. */
  maxExpressionDepth: number;
  /** Most nodes in one arithmetic expression. */
  maxExpressionNodes: number;
}

/**
 * The effective limits of the entry the descriptor was read from: the
 * protocol's and the entry's budget, whichever is smaller. `null` is
 * unlimited.
 */
export interface LimitsDescriptor {
  /** Largest `limit` of a list query. */
  maxListSize: number | null;
  /** The `limit` a list query without one gets. */
  defaultListSize: number | null;
  /** Largest page `size` of a paged or cursor query. */
  maxPageSize: number | null;
  /** How far a paged query may reach: page index times size. */
  maxPageWindow: number | null;
  /** Most nodes in one filter. */
  maxFilterNodes: number | null;
  /** Most values in one filter, `IN` lists included. */
  maxFilterValues: number | null;
  /** Most fields in one sort. */
  maxSortFields: number;
  /** The limits of an aggregation query. */
  aggregation: AggregationLimitsDescriptor;
}

/** The metrics a `having` clause may test. */
export interface HavingDescriptor {
  /** The metric types it may name; never `ANY`, `FIRST` or `LAST`. */
  metrics: (AggregationMetricType | (string & {}))[];
}

/** What an aggregation's result rows may be sorted by. */
export interface AnalysisSortDescriptor {
  /** By a group. */
  groups: boolean;
  /** By a metric. */
  metrics: boolean;
}

/** The aggregation features the model offers. */
export interface AnalysisDescriptor {
  /** The metric types it admits. */
  metrics: (AggregationMetricType | (string & {}))[];
  /**
   * The metric types among `metrics` whose results this backend estimates
   * rather than computes exactly, such as `PERCENTILE` on MongoDB, or
   * `DISTINCT_COUNT` and `PERCENTILE` on Elasticsearch; empty when every
   * result is exact. The set is open, like `metrics`: label a result of a
   * listed type as approximate.
   */
  approximate: (AggregationMetricType | (string & {}))[];
  /**
   * Whether computed expressions (arithmetic and `DATE_DIFF`) may feed
   * metrics and TERMS / HISTOGRAM groups, and be compared by an `EXPRESSION`
   * filter. A `DATE_DIFF` operand is any field whose `aggregate.groups`
   * lists `DATE_HISTOGRAM`.
   */
  expressions: boolean;
  /** What `having` may test. */
  having: HavingDescriptor;
  /** What result rows may be sorted by. */
  sort: AnalysisSortDescriptor;
  /**
   * Whether a date histogram or a date part group may fill its empty
   * buckets (`dense`).
   */
  dense: boolean;
  /** The calendar units a `DATE_HISTOGRAM` group may bucket by. */
  dateUnits: AggregationDateUnit[];
  /** The calendar parts a `DATE_PART` group may group by. */
  dateParts: AggregationDatePart[];
  /** The units a `DATE_DIFF` may measure in; empty when `expressions` is `false`. */
  dateDiffUnits: DateDiffUnit[];
  /**
   * The field `FIRST` and `LAST` order by when they name no `orderBy`, at
   * the record level: the model's event time. Absent when the model has no
   * event time or the storage offers no `FIRST` / `LAST`. An explicit
   * `orderBy` may be any single-valued field whose `sort.paged` is `true` in
   * the metric's scope.
   */
  firstLastOrderBy?: string;
}

/** A rule about combinations that no single capability shows. */
export interface ConstraintDescriptor {
  /** Which rule; see {@link QueryConstraintTypes}. */
  type: QueryConstraintType;
  /** For `CURSOR_UNIQUE_SORT`, the field the server appends to the sort. */
  appended?: string;
  /**
   * The fields the rule is about, when it names several: for
   * `PARALLEL_ARRAY_SORT`, the array fields a sort may name only one of;
   * for `NULL_OR_EMPTY_AS_MISSING`, the fields whose `null` or empty value
   * reads as missing.
   */
  fields?: string[];
}

/**
 * One variant of an element: the value of its discriminator and the fields
 * that variant has. For an event stream, one event type and its payload
 * fields.
 */
export interface VariantDescriptor {
  /** The discriminator's value, such as the event's `bodyType`. */
  value: string;
  /**
   * The variant's fields, by paths relative to the element, such as
   * `body.added.productId` inside `body`. Each field states the variant's
   * own types; its operators, sorts and aggregation are those of the shared
   * logical path, which admission checks. A field's `scope` is relative to
   * the element too, and absent for a field directly in it.
   */
  fields: FieldDescriptor[];
  /** What the variant means, when the model describes it. */
  description?: string;
}

/**
 * An element of every record whose fields differ by variant: an event
 * stream record's `body` holds events whose payload fields depend on
 * `bodyType`. A condition on one variant's field goes inside an
 * `ELEMENT_MATCH` on `element`, together with a condition on the
 * `discriminator`, so both hold for the same element.
 */
export interface VariantsDescriptor {
  /** The element's logical path, such as `body`. */
  element: string;
  /** The element-relative field that names each element's variant, such as `bodyType`. */
  discriminator: string;
  /** Each variant, sorted by `value`. */
  values: VariantDescriptor[];
}

/**
 * How a query model can be queried over HTTP: the answer
 * `GET {aggregate}/snapshot/schema` and `GET {aggregate}/event/schema` give.
 * Read it with `QueryDescriptorClient`.
 *
 * The contract: every operator, sort, paging mode, group and function it
 * lists is admitted when used on its own, and anything it does not list is
 * rejected; `constraints` names the rules about combinations. A value, a
 * scope or a policy can still reject a query, with a
 * `QueryViolation`. It exposes no storage names and does not depend on
 * the caller, so it can be cached: `version` is a hash of its content and is
 * also the response's ETag.
 */
export interface QueryModelDescriptor {
  /** The query model it describes. */
  model: QueryModel;
  /** `sha256:` and the hash of its content; also the ETag, quoted. */
  version: string;
  /** The server's default time zone, used when a request names none. */
  timeZone: string;
  /** What holds for the records as a whole. */
  record: RecordDescriptor;
  /** The entry's effective limits. */
  limits: LimitsDescriptor;
  /** The aggregation features. */
  analysis: AnalysisDescriptor;
  /** Every queryable field, those inside elements included (see `scope`). */
  fields: FieldDescriptor[];
  /** The array fields whose elements can be queried one by one. */
  elements: ElementDescriptor[];
  /** Fields under dynamic map keys. */
  dynamic: DynamicFieldDescriptor[];
  /** The rules about combinations. */
  constraints: ConstraintDescriptor[];
  /**
   * The variants of an element whose fields differ by a discriminator, such
   * as an event stream's event types; absent when the model has none, as a
   * snapshot model never does.
   */
  variants?: VariantsDescriptor;
}
