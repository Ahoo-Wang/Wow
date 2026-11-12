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
// answers with: `me.ahoo.wow.query.descriptor.QueryModelDescriptor`,
// mirrored from `query/query/src/main/kotlin/me/ahoo/wow/query/descriptor/`;
// a field's descriptors are in `fieldDescriptor.ts`, and the semantics of
// `query/schema/QuerySemanticType.kt` in `semanticTypes.ts`.
//
// Kotlin leaves a null out of the JSON of every descriptor class annotated
// `@JsonInclude(NON_NULL)`, so those properties are optional here; the
// limits are not annotated and send `null`. A set the OpenAPI document
// describes as a plain string is open: a known union plus `string & {}`.

import type { WindowFunction } from './aggregation/derived.js';
import type {
  AggregationDatePart,
  AggregationDateUnit,
} from './aggregation/groups.js';
import type { AggregationMetricType } from './aggregation/metrics.js';
import type { DateDiffUnit } from './aggregation/types.js';
import type { DeletionState } from './deletionState.js';
import type {
  DynamicFieldDescriptor,
  FieldDescriptor,
} from './fieldDescriptor.js';
import type { FilterOperator, SearchMode } from './filter/index.js';

/**
 * A query model's capability descriptor and its version, the `ETag` of
 * `GET …/query`; `notModified` when the version given is still current.
 */
export type DescriptorResult =
  | {
      readonly notModified: false;
      readonly version?: string;
      readonly descriptor: QueryModelDescriptor;
    }
  | { readonly notModified: true; readonly version: string };

/**
 * The query models Wow names in a descriptor's `model`. The set is open: a
 * server may register a model of its own, so a descriptor's `model` is a
 * {@link QueryModel}.
 */
export const QueryModels = Object.freeze({
  /** The snapshot query model: `{aggregate}/snapshot/*`. */
  SNAPSHOT: 'snapshot',
  /** The event stream query model: `{aggregate}/event/*`. */
  EVENT_STREAM: 'events',
  /** A declared dataset, such as a ClickHouse wide table: `dataset/{context}/{dataset}/*`. */
  DATASET: 'dataset',
} as const);

/** A query model's name: one of {@link QueryModels}, or one a server added. */
export type QueryModel =
  (typeof QueryModels)[keyof typeof QueryModels] | (string & {});

/** The ways a model can be read page by page, listed in `record.paging`. */
export enum PagingMode {
  /** `list` and `stream`: the first `limit` rows. */
  LIST = 'list',
  /** `paged`: numbered pages with a total. */
  PAGED = 'paged',
  /** `cursor`: forward-only pages after the last row seen. */
  CURSOR = 'cursor',
}

/**
 * The combination rules Wow names in a descriptor's `constraints`: rules
 * that no single capability shows. The set is open, like {@link QueryModels}.
 */
export const QueryConstraintTypes = Object.freeze({
  /**
   * A cursor's sort always ends with the identity field; the server appends
   * it (the constraint's `appended`) when the sort does not.
   */
  CURSOR_UNIQUE_SORT: 'cursor-unique-sort',
  /** A count or paged query must not match every record. */
  COUNT_REQUIRES_FILTER: 'count-requires-filter',
  /** `STARTS_WITH` needs a non-empty, case-sensitive prefix. */
  STARTS_WITH_REQUIRES_PREFIX: 'starts-with-requires-prefix',
  /**
   * A sort names at most one of the constraint's `fields`: they are
   * array-valued and the storage cannot order by two independent arrays.
   * Fields on one array path, one nested in the other, may still combine.
   * A sort that breaks it is refused with `PARALLEL_ARRAY_SORT`.
   */
  PARALLEL_ARRAY_SORT: 'parallel-array-sort',
  /**
   * On the constraint's `fields`, a stored `null` or empty array cannot be
   * told from a missing field: `EXISTS` does not match it, `NOT_EXISTS` and
   * `IS_NULL` do, and `IS_EMPTY` also matches a missing or `null` field. A
   * presence filter on such a field answers "has a non-empty value", not
   * "has the key"; say so to a user who writes one.
   */
  NULL_OR_EMPTY_AS_MISSING: 'null-or-empty-as-missing',
  /**
   * Model-wide, without `fields`: `EQ` and `NE` take only a scalar operand,
   * because the storage cannot compare a whole array. Match an array
   * field's elements instead (`IN`, `CONTAINS_ALL`, `ELEMENT_MATCH`); an `EQ` or
   * `NE` with an array operand is refused with `ARRAY_EQUALITY`.
   */
  ARRAY_EQUALITY: 'array-equality',
  /**
   * The queries of an events model must name their aggregates: the
   * constraint's `fields` are the one field to name them by, `aggregateId`.
   * The service lists it where events are visible only per aggregate, and
   * refuses a query that names none.
   */
  AGGREGATE_REQUIRED: 'aggregate-required',
} as const);

/** A constraint's type: one of {@link QueryConstraintTypes}, or one a server added. */
export type QueryConstraintType =
  | (typeof QueryConstraintTypes)[keyof typeof QueryConstraintTypes]
  | (string & {});

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
  modes: (SearchMode | (string & {}))[];
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
  paging: (PagingMode | (string & {}))[];
  /** The deletion scope a query without one gets; absent when the model has none. */
  defaultScope?: DeletionState;
  /**
   * Operators that name no field of their own: the system-field ones, such
   * as `ID` or `TENANT_ID`, and `EXPRESSION`, whose expression names its
   * fields, where the server allows expensive operators.
   */
  rootOperators: (FilterOperator | (string & {}))[];
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
  /**
   * The most groups an aggregation may read where the server computes part of
   * it (HAVING, a metric sort, dense fill or windows its storage cannot run),
   * beyond which the query fails; `null` or absent is unlimited. Absent from
   * servers before Wow 9.10.
   */
  maxResidualGroups?: number | null;
}

/**
 * The effective limits of the entry the descriptor was read from: the
 * protocol's and the entry's budget, whichever is smaller. `null` is
 * unlimited.
 */
export interface LimitsDescriptor {
  /** Largest `limit` of a list query. */
  maxListSize: number | null;
  /**
   * The `limit` a list query without one gets. A cursor query without a
   * `size` gets the same default, at most `maxPageSize`.
   */
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
  dateUnits: (AggregationDateUnit | (string & {}))[];
  /** The calendar parts a `DATE_PART` group may group by. */
  dateParts: (AggregationDatePart | (string & {}))[];
  /** The units a `DATE_DIFF` may measure in; empty when `expressions` is `false`. */
  dateDiffUnits: (DateDiffUnit | (string & {}))[];
  /**
   * The field `FIRST` and `LAST` order by when they name no `orderBy`, at
   * the record level: the model's event time. Absent when the model has no
   * event time or the storage offers no `FIRST` / `LAST`. An explicit
   * `orderBy` may be any single-valued field whose `sort.paged` is `true` in
   * the metric's scope.
   */
  firstLastOrderBy?: string;
  /**
   * The window functions a derived metric may compute over the groups; empty
   * when expensive operators are off. Absent from servers before Wow 9.9,
   * which compute no windows.
   */
  windows?: (WindowFunction | (string & {}))[];
  /**
   * Whether a group of a query that expands elements may name a field of the
   * root record (`scope: 'root'`); `false` when expensive operators are off.
   * Absent from servers before Wow 9.9, which group elements by element
   * fields only.
   */
  rootGroups?: boolean;
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
  /** The zone a request that names none is read in: UTC (review R4-Q, Q-10). */
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
