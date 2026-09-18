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

import type { SearchMode, StringComparison } from '@ahoo-wang/fetcher-wow';
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
  | 'array'
  | 'elementMatch'
  | 'search'
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

/**
 * Kinds whose `name` is a handle for the editor and the label rather than a
 * path into a document. They compile to a filter that names no field, so the
 * presence questions — which do carry one — are not theirs to answer.
 */
export const FIELDLESS_FIELD_KIND_IDS: readonly FieldKindId[] = [
  'documentId',
  'aggregateId',
  'tenantId',
  'ownerId',
  'spaceId',
  'search',
];

/**
 * Whether a kind's `name` is a handle rather than a path into a document.
 *
 * Three things follow from it and each one is a place the two were confused:
 * the presence operators do not apply, the field is not a record column, and
 * Wow refuses such a filter inside an element predicate — it calls them root
 * filters, and its list is exactly this one.
 *
 * The registered kind answers first when the caller has one: a custom kind
 * that compiles to a root filter declares `fieldless` on itself, and an id
 * list here could never have heard of it. The list stays the answer for a
 * kind nobody has resolved — a record column asked about by id alone.
 */
export function isFieldlessKind(
  kind: FieldKindId,
  registered?: { fieldless?: boolean },
): boolean {
  if (registered?.fieldless) return true;
  return FIELDLESS_FIELD_KIND_IDS.includes(kind);
}

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
  'array',
  'elementMatch',
  'search',
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

/**
 * A field path as one alias segment, injectively.
 *
 * Wow takes a single-segment alias only, so a path has to lose its dots. A
 * plain `.`→`_` made `a.b` and `a_b` the same alias, and whichever cell came
 * second overwrote the first — silently, because both are legal aliases and
 * the server answers with one value under one name. Doubling an existing `_`
 * first keeps the mapping reversible: a lone `_` is always a `.` that was.
 */
export function fieldAliasSegment(field: string): string {
  return field.replace(/_/g, '__').replace(/\./g, '_');
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
  /** The `DataViewDefinition.fieldGroups` entry this field is listed under. */
  group?: string;
  numberFormat?: NumberFormat;
  /** Summary functions this field allows. */
  summary?: SummaryFunction[];
  /** Cell renderer key; defaults to the kind's renderer. */
  cell?: string;
  /** Filter editor key; defaults to the one implied by kind, operator and value. */
  editor?: string;
  /**
   * For an array of objects, what each of its elements holds.
   *
   * It belongs to the field because it describes that field: `items` is the
   * array, and these are what it contains. Declared anywhere else it would be
   * linked back by a path string, which can name a field that does not exist —
   * a whole class of dangling reference that simply cannot be written here.
   *
   * An element's names are its own scope and may repeat a root field's,
   * because every reference to one is written `field.element`.
   */
  elements?: FieldDefinition[];
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
  /**
   * For a `search` field, which fields the query looks in. Omitted, it looks
   * wherever the backend indexes — which is what a search box usually means.
   */
  searchFields?: string[];
  /**
   * For a `search` field, whether the query is a set of words or one phrase.
   * Like `stringComparison`, how matching works is a property of the field;
   * the value is only what the user typed.
   */
  searchMode?: SearchModeName;
}

/** Wow's `SearchMode`, as the literal a definition writes. */
export type SearchModeName = `${SearchMode}`;

export const SEARCH_MODES: readonly SearchModeName[] = ['TERMS', 'PHRASE'];

export const DEFAULT_SEARCH_MODE: SearchModeName = 'TERMS';

/** Wow's `StringComparison`, as the literal a definition writes. */
export type StringComparisonName = `${StringComparison}`;

export const DEFAULT_STRING_COMPARISON: StringComparisonName =
  'CASE_INSENSITIVE';

export const STRING_COMPARISONS: readonly StringComparisonName[] = [
  'CASE_SENSITIVE',
  'CASE_INSENSITIVE',
];
