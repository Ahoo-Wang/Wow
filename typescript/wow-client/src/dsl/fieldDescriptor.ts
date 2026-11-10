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

import type { AggregationGroupType } from './aggregation/groups.js';
import type { AggregationFunction } from './aggregation/metrics.js';
import type { FilterOperator } from './filter/index.js';
import type { QuerySemanticType } from './semanticTypes.js';

/**
 * The value types Wow names in a field's `types`. The set is open, like
 * {@link QueryModels}.
 */
export const QueryValueTypes = Object.freeze({
  /** A JSON string. */
  STRING: 'string',
  /** A whole number. */
  INTEGER: 'integer',
  /** A number with a fraction. */
  DECIMAL: 'decimal',
  /** `true` or `false`. */
  BOOLEAN: 'boolean',
  /** A JSON object. */
  OBJECT: 'object',
} as const);

/** A field's value type: one of {@link QueryValueTypes}, or one a server added. */
export type QueryValueType =
  (typeof QueryValueTypes)[keyof typeof QueryValueTypes] | (string & {});

/** The structural shape of a field's value, sent as its `kind`. */
export enum QueryValueKind {
  /** Wow could not tell. */
  UNKNOWN = 'unknown',
  /** Always `null`. */
  NULL = 'null',
  /** A single string, number or boolean. */
  SCALAR = 'scalar',
  /** An object. */
  OBJECT = 'object',
  /** An array. */
  ARRAY = 'array',
  /** One of several of the above. */
  UNION = 'union',
}

/**
 * The system roles Wow names in a field's `role`: the system fields a root
 * operator targets, and the model's times. The set is open, like
 * {@link QueryModels}.
 */
export const QueryFieldRoles = Object.freeze({
  /** The record's own identity, `record.identity`. */
  IDENTITY: 'identity',
  /** The aggregate id. */
  AGGREGATE_ID: 'aggregate-id',
  /** The tenant id. */
  TENANT_ID: 'tenant-id',
  /** The owner id. */
  OWNER_ID: 'owner-id',
  /** The space id. */
  SPACE_ID: 'space-id',
  /** Whether the aggregate is deleted. */
  DELETED: 'deleted',
  /**
   * When the record's events happened: a snapshot's last event
   * (`eventTime`), an event stream's events (`createTime`). `FIRST` and
   * `LAST` order by it when they name no `orderBy`. Wow 9.2 and later.
   */
  EVENT_TIME: 'event-time',
  /**
   * When a snapshot's aggregate got its first event, i.e. was created
   * (`firstEventTime`). Wow 9.2 and later.
   */
  FIRST_EVENT_TIME: 'first-event-time',
} as const);

/** A system field's role: one of {@link QueryFieldRoles}, or one a server added. */
export type QueryFieldRole =
  (typeof QueryFieldRoles)[keyof typeof QueryFieldRoles] | (string & {});

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
  DISPLAY = 'display',
  /**
   * Masked in results and never compared: no filter, sort, search, grouping
   * or metric may name it.
   */
  CONFIDENTIAL = 'confidential',
}

/**
 * Why a field is deprecated. The field can still be queried, but new
 * queries and view definitions should avoid it.
 */
export interface QueryDeprecation {
  /** Why, or what to use instead; absent when the declaration gave no reason. */
  message?: string;
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
  /**
   * Whether an index backs the sort, as the storage says: `false` marks a
   * sort it runs without one, which may be slow on a large model; absent
   * when the storage does not say.
   */
  indexed?: boolean;
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
  /** Whether their values may be `null`, as a field's `nullable` says. */
  nullable: boolean;
  /**
   * What their values mean beyond their type, as a field's `semantic`
   * says; absent when nothing more.
   */
  semantic?: QuerySemanticType;
  /** The filter operators they admit. */
  filter: FieldFilterDescriptor;
  /** Where a concrete key may be sorted by. */
  sort: FieldSortDescriptor;
  /** How a concrete key may be aggregated; absent when it cannot be at all. */
  aggregate?: FieldAggregateDescriptor;
  /**
   * The map's keys that are declared as fields of their own and so are not
   * covered by this pattern; absent when there are none. For
   * `state.attributes.{key}` with `state.attributes.color` declared, it is
   * `['color']`: a filter on `state.attributes.color` takes that field's
   * entry in `fields`, with its own operators, not this pattern's.
   */
  excludedKeys?: string[];
}
