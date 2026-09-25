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

import type { SearchMode, StringComparison } from '@ahoo-wang/wow-client';
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
  | 'documentId'
  | 'aggregateId'
  | 'tenantId'
  | 'ownerId'
  | 'spaceId'
  /** Whether soft-deleted records are shown: Wow's `DELETION` filter (D17-2). */
  | 'deletion';

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
  'deletion',
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

/**
 * Kinds whose value in one record is a single string.
 *
 * Wow allows a `TERMS` sentinel bucket (`missingKey`) on single-valued string
 * fields only — nullable ones being the case it exists for — and refuses
 * multi-valued, numeric and boolean fields at schema validation. `reference`
 * is deliberately absent: its ids come from a remote source and may be
 * numbers, and the kind cannot promise otherwise.
 *
 * This list is what `isSingleStringField` answers with when no registered
 * kind is at hand; a registered one answers for itself through
 * `FieldKind.singleString`, exactly as `isFieldlessKind` works.
 */
export const SINGLE_STRING_FIELD_KIND_IDS: readonly FieldKindId[] = [
  'string',
  'enum',
];

/**
 * Whether one record holds at most one string in this field.
 *
 * Two answers make it up, because two declarations can make it false. The
 * kind says what shape its values have; the field's own candidates say what
 * those values are, and a closed set of numeric codes is a numeric field
 * whatever its kind is called — `enum` over `[1, 2]` is stored as numbers,
 * and a sentinel bucket over it is refused by Wow rather than by us.
 */
export function isSingleStringField(
  field: FieldDefinition,
  registered?: { singleString?: boolean },
): boolean {
  const declared = registered
    ? registered.singleString === true
    : SINGLE_STRING_FIELD_KIND_IDS.includes(field.kind);
  if (!declared) return false;
  return (field.options ?? []).every(
    option => typeof option.value === 'string',
  );
}

export const METADATA_FIELD_KIND_IDS: readonly MetadataFieldKindId[] = [
  'documentId',
  'aggregateId',
  'tenantId',
  'ownerId',
  'spaceId',
  'deletion',
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

/**
 * What a badge says about the value it wraps, beyond naming it.
 *
 * Closed, and named after the meaning rather than after a colour: which of a
 * definition's own statuses is good news is business knowledge the renderer
 * cannot guess, but which colour good news wears is the theme's, and a
 * definition that could write `#22c55e` here would be painting into it. Each
 * tone maps to a token the surface already carries, so a host that restyles
 * `--fve-success` restyles every badge wearing it.
 */
export type FieldTone = 'neutral' | 'success' | 'warning' | 'danger';

export const FIELD_TONES: readonly FieldTone[] = [
  'neutral',
  'success',
  'warning',
  'danger',
];

/** A selectable value: static for `enum`, resolved for `reference`. */
export interface FieldOption {
  value: string | number;
  label: string;
  group?: string;
  disabled?: boolean;
  /** How a badge for this choice reads; neutral when the option names none. */
  tone?: FieldTone;
}

/**
 * How a cell reads.
 *
 * Closed, because `/ui` has no renderer registry: `RecordTable` switches over
 * this union, so a key nothing switches on would silently fall through to the
 * default rendering — a URL as a string of characters, an array as a comma
 * join. Definition admission refuses an unknown one instead, which is the
 * same rule every capability follows: what the engine offers is what a
 * definition may ask for.
 *
 * Six of them are the kinds' own renderings, writable here so a field may
 * borrow one: a number holding a millisecond instant reads as a date under
 * `cell: 'date'`. The other five are readings no kind implies — one badge, a
 * badge per entry, an external link, a paragraph, and a value with the means
 * to take it away.
 */
export type FieldCellId =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  | 'status'
  | 'tags'
  | 'link'
  | 'text'
  | 'copyable';

export const FIELD_CELL_IDS: readonly FieldCellId[] = [
  'string',
  'number',
  'boolean',
  'date',
  'datetime',
  'enum',
  'status',
  'tags',
  'link',
  'text',
  'copyable',
];

/**
 * Whether cells under this reading are moments rather than numbers or words.
 *
 * It asks about the **reading** and not about the kind, because the reading
 * is what the column shows: `cell: 'date'` over a number holding an epoch
 * instant draws a column of dates, and the summary under it has to be one
 * too. It takes a plain string, since a reading travels as one — a projected
 * column's `cell`, a summary cell's — and an application may register a kind
 * whose id this list has never heard of.
 */
export function isDateCell(cell: string | undefined): boolean {
  return cell === 'date' || cell === 'datetime';
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

/**
 * The summaries a column of moments may carry.
 *
 * A date has an earliest, a latest and a count; it has no sum and no
 * average. Adding two instants answers nothing at all, and the mean of two
 * order times is a moment no order was placed at — a number the aggregation
 * would happily return and that no reader could use. So the maths is left
 * out of the set rather than shown and then explained.
 */
export const DATE_SUMMARY_FUNCTIONS: readonly SummaryFunction[] = [
  'MIN',
  'MAX',
  'COUNT',
];

/**
 * The summaries a field really offers: what it declares, less what its own
 * values cannot answer.
 *
 * One rule, read in both places that need it — admission, which refuses a
 * summary outside the set (`record.summary.unsupported`), and the column
 * settings' select, which must stop offering exactly where admission starts
 * refusing. A definition declaring `SUM` on a date field is a mistake in
 * code, so it is refused rather than obeyed; declaring `MIN` and `MAX` there
 * is how a column gets its earliest and its latest.
 */
export function summaryFunctionsOf(
  field: FieldDefinition,
): readonly SummaryFunction[] {
  const declared = field.summary ?? [];
  return isDateCell(field.cell ?? field.kind)
    ? declared.filter(fn => DATE_SUMMARY_FUNCTIONS.includes(fn))
    : declared;
}

/**
 * How a time field keeps its time, which is how a condition on it has to
 * write one — Wow's own `QuerySemanticType` for time, named as Wow names it.
 *
 * - `epoch` is Wow's `TEMPORAL_EPOCH`: an integer count of `timeUnit` since
 *   the epoch, milliseconds when unsaid. Every time a Wow snapshot carries —
 *   `eventTime`, `firstEventTime`, an aggregate's `@QueryTemporal` longs — is
 *   one, and Wow's schema validation refuses anything but an integer there.
 * - `date` is Wow's `TEMPORAL_DATE`: the store's own date type, which a query
 *   names in ISO 8601 text.
 *
 * Wow's third, `TEMPORAL_FORMATTED`, a string under a pattern, is not
 * modelled: no definition has needed one, and a bound written under the
 * wrong pattern would be compared as text and match the wrong rows quietly.
 */
export type FieldTemporal = EpochTemporal | DateTemporal;

export interface EpochTemporal {
  type: 'epoch';
  /** Wow's `timeUnit`; milliseconds when unsaid, as Wow's own default is. */
  timeUnit?: EpochTimeUnit;
}

export interface DateTemporal {
  type: 'date';
}

/** The units of `TimeUnit` an epoch time is kept in, as Wow spells them. */
export type EpochTimeUnit = 'MILLISECONDS' | 'SECONDS';

export const EPOCH_TIME_UNITS: readonly EpochTimeUnit[] = [
  'MILLISECONDS',
  'SECONDS',
];

export const TEMPORAL_TYPES: readonly FieldTemporal['type'][] = [
  'epoch',
  'date',
];

/** The kinds whose conditions write a time, and so may declare `temporal`. */
export const TEMPORAL_FIELD_KIND_IDS: readonly FieldKindId[] = [
  'date',
  'datetime',
];

/**
 * What a time field that declares nothing is: epoch milliseconds.
 *
 * The default is the engine's audience rather than the fixtures' habit. A
 * Wow snapshot keeps every time as epoch milliseconds, and a definition
 * written from a Wow query schema should come out right without the author
 * having to remember one more member on each of them; a store that keeps a
 * native date is the case that says so.
 */
export const DEFAULT_TEMPORAL: Required<EpochTemporal> = {
  type: 'epoch',
  timeUnit: 'MILLISECONDS',
};

/** How this field keeps its time: its declaration, or the default. */
export function temporalOf(field: FieldDefinition): FieldTemporal {
  return field.temporal ?? DEFAULT_TEMPORAL;
}

/**
 * The unit this field's epoch numbers count in, where it is not the
 * default milliseconds — `undefined` for milliseconds and for a field that
 * keeps no epoch at all. What reads a value as a time (`readInstant`) takes
 * it, so a cell, a footer and an earliest agree with the condition that
 * wrote the time in that unit.
 */
export function epochUnitOf(
  field: Pick<FieldDefinition, 'kind' | 'cell' | 'temporal'>,
): EpochTimeUnit | undefined {
  const temporal = field.temporal;
  return temporal?.type === 'epoch' && temporal.timeUnit === 'SECONDS'
    ? 'SECONDS'
    : undefined;
}

/**
 * The aggregate functions an analysis may take of a field of moments: its
 * earliest and its latest. `DATE_SUMMARY_FUNCTIONS` without the count, which
 * an analysis spells as a metric of its own; the same reason leaves the sum,
 * the average, the deviation and the variance out.
 */
export const DATE_AGGREGATION_FUNCTIONS = ['MIN', 'MAX'] as const;

/**
 * The aggregate functions a field really offers an analysis: what its
 * capability declares, less what its own values cannot answer — the
 * analysis side of `summaryFunctionsOf`, read by the analysis scope, so
 * admission, the tray's summary select and a fresh config's first metric all
 * stop at the same place. A field the scope cannot find keeps what it
 * declares; admission reports the field itself.
 */
export function aggregationFunctionsOf<F extends string>(
  field: Pick<FieldDefinition, 'kind' | 'cell'> | undefined,
  declared: readonly F[],
): F[] {
  if (!field || !isDateCell(field.cell ?? field.kind)) return [...declared];
  return declared.filter(fn =>
    (DATE_AGGREGATION_FUNCTIONS as readonly string[]).includes(fn),
  );
}

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
  numberFormat?: NumberFormat;
  /** Summary functions this field allows. */
  summary?: SummaryFunction[];
  /**
   * Whether a page may ask its source for this field. Left out, it may; a
   * source whose descriptor says it cannot be projected answers `false`
   * here, and a column of it reads empty rather than the page being refused.
   */
  projectable?: boolean;
  /** How the cell reads; defaults to the kind's own renderer. */
  cell?: FieldCellId;
  /**
   * For a `date` or `datetime` field, how the store keeps its time, and so
   * how a condition's bounds are written: epoch milliseconds when unsaid
   * (`DEFAULT_TEMPORAL`). Copy it from the field's `semanticType` in the Wow
   * query schema.
   */
  temporal?: FieldTemporal;
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
   * For an array of objects, the element field that names one element — the
   * name it has within `elements`. A cell reads the array as its elements,
   * each by this field and the way this field reads: an enum's label and
   * tone as a badge, a string as its text.
   *
   * Called a title because it is one, in the same sense as a card's title
   * (`RecordCardSpec.title`): the one value that says which thing this is.
   * The engine cannot pick it — an event history is read by `name`, an
   * order line by `sku`, and which member says what an element *is* is
   * business knowledge — and without it an array of objects reads as how
   * many elements it holds, never as the JSON of them. Admission refuses a
   * name `elements` does not declare, and an element field that holds no
   * value of its own (a handle, or a further array of objects).
   */
  elementTitle?: string;
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
