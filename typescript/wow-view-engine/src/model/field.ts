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

import type { StringComparison } from '@ahoo-wang/fetcher-wow';
import type { FilterOperatorName } from './filter.js';

/**
 * Identifies a `FieldKind`: the package registers the built-in ones and an
 * application may register its own, so this stays an open string union.
 */
export type FieldKindId = BuiltinFieldKindId | (string & {});

export type BuiltinFieldKindId =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  | 'reference'
  | MetadataFieldKindId;

/**
 * Kinds backed by Wow's metadata filters rather than by a document field.
 *
 * Whether one of these is a legitimate condition depends on who is looking: a
 * user narrows to their own documents by owner or to a workspace by space, a
 * platform operator narrows to a tenant. That judgement belongs to the
 * definition, which is code and ships per application, so the engine offers
 * the kinds and lets each definition decide which of them a view may use.
 */
export type MetadataFieldKindId =
  'documentId' | 'aggregateId' | 'tenantId' | 'ownerId' | 'spaceId';

export const METADATA_FIELD_KIND_IDS: readonly MetadataFieldKindId[] = [
  'documentId',
  'aggregateId',
  'tenantId',
  'ownerId',
  'spaceId',
];

export const BUILTIN_FIELD_KIND_IDS: readonly BuiltinFieldKindId[] = [
  'string',
  'number',
  'boolean',
  'date',
  'datetime',
  'enum',
  'reference',
  ...METADATA_FIELD_KIND_IDS,
];

/**
 * Wow's query field syntax: segments separated by dots, each an identifier
 * optionally prefixed with `@`, or an array index.
 *
 * A name outside it reaches the compiler as a `TypeError` rather than as an
 * Issue, so every layer that accepts a field name from data checks it first.
 */
export const FIELD_NAME_PATTERN =
  /^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$/;

export function isFieldName(name: string): boolean {
  return FIELD_NAME_PATTERN.test(name);
}

/** A selectable value: static for `enum`, resolved for `reference`. */
export interface FieldOption {
  value: string | number;
  label: string;
  group?: string;
  disabled?: boolean;
}

/** Row-level aggregations a record view may show under a column. */
export type SummaryFunction = 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COUNT';

export const SUMMARY_FUNCTIONS: readonly SummaryFunction[] = [
  'SUM',
  'AVG',
  'MIN',
  'MAX',
  'COUNT',
];

/** Formatting shared by cells, summaries and chart axes. */
export type NumberFormat = Intl.NumberFormatOptions & { locale?: string };

/**
 * One queryable field of a definition. Declared in code, so it may use the
 * Wow operator enum directly; stored configs use the literal form instead.
 */
export interface FieldDefinition {
  /** Wow query field path, e.g. `warehouse` or `address.city`. */
  name: string;
  label: string;
  kind: FieldKindId;
  /** Narrows the kind's default operator set. */
  operators?: FilterOperatorName[];
  /** Static candidates for `enum`. */
  options?: FieldOption[];
  /** Remote candidate source key for `reference`, resolved by the engine. */
  remote?: string;
  sortable?: boolean;
  /** Grouping label in the add-filter picker. */
  group?: string;
  numberFormat?: NumberFormat;
  /** Summary functions this field allows. */
  summary?: SummaryFunction[];
  /** Cell renderer key; defaults to the kind's renderer. */
  cell?: string;
  /** Filter editor key; defaults to the one implied by kind, operator and value. */
  editor?: string;
  /**
   * How `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` compare text on this field.
   *
   * The default is case-insensitive, because somebody filtering a list by
   * typing `beijing` means to find `Beijing`. A field where case carries
   * meaning — a product code, a signature, anything the user is matching
   * exactly rather than searching — pins itself to `CASE_SENSITIVE` here.
   * Wow attaches this to text matching only: `EQ` and `IN` have no such
   * option, so neither does this.
   */
  stringComparison?: StringComparisonName;
}

/** Wow's `StringComparison`, as the literal a definition writes. */
export type StringComparisonName = `${StringComparison}`;

export const DEFAULT_STRING_COMPARISON: StringComparisonName =
  'CASE_INSENSITIVE';

export const STRING_COMPARISONS: readonly StringComparisonName[] = [
  'CASE_SENSITIVE',
  'CASE_INSENSITIVE',
];
