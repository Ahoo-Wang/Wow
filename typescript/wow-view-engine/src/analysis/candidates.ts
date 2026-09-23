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

import {
  AGGREGATION_LIMITS,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  isSingleStringField,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterOperatorName,
  type RecordData,
  type RuntimeLimits,
} from '../model/index.js';
import { operatorsOf, type FieldKindRegistry } from '../filter/index.js';
import { fitChartSlots } from './chartSlots.js';
import { analysisProbeLimit } from './compile.js';

/**
 * How many of a field's values a condition is offered, most frequent first:
 * enough that a field with a few dozen values (a processor, an error code)
 * lists every one, few enough that the list is read rather than scrolled.
 * The definition's own `maxLimit` still caps it.
 */
export const VALUE_CANDIDATE_LIMIT = 50;

/**
 * The operators whose value is one of the field's own values: equal to it,
 * not equal to it, among them, not among them. A substring or a prefix is a
 * piece of text the user writes — offering whole values there would pick a
 * value where a fragment was meant — so those stay typed.
 */
export const VALUE_CANDIDATE_OPERATORS: readonly FilterOperatorName[] = [
  'EQ',
  'NE',
  'IN',
  'NOT_IN',
];

/** One value the data holds, and how many records hold it. */
export interface ValueCandidate {
  value: string;
  count: number;
}

/** What one candidate query answered. */
export interface ValueCandidates {
  /** Most frequent first; ties by value, so the order is stable. */
  values: ValueCandidate[];
  /**
   * Whether these are every value the question matches. False when the
   * source had more than the limit, so a value that is not listed may still
   * exist — the list is the most frequent, and typing narrows it.
   */
  complete: boolean;
}

/** The aliases the candidate query names its two columns by. */
const VALUE = 'value';
const COUNT = 'count';

/**
 * The field a condition's values can be offered for from the data, or
 * `null` when they cannot.
 *
 * Offered when the data can be asked for them and nothing better answers:
 * the definition lets the field be grouped by value (`TERMS`) and records be
 * counted, so the question is one Wow already takes; a record holds one
 * string there, so a group's key is a value an equality can compare against;
 * and the field declares neither `options` (a closed set lists itself, with
 * its labels) nor `remote` (the host searches it). A field inside an array's
 * elements is not a root field and is never offered: its values would be
 * counted over records, not over the elements a predicate tests.
 */
export function valueCandidateField(
  definition: DataViewDefinition,
  name: string,
  kinds: FieldKindRegistry,
): FieldDefinition | null {
  const capability = definition.analysis;
  if (!capability?.count) return null;
  const field = definition.fields.find(entry => entry.name === name);
  if (!field || field.options || field.remote) return null;
  const kind = kinds.get(field.kind);
  if (!kind || !isSingleStringField(field, kind)) return null;
  const groupable = capability.fields.some(
    entry =>
      entry.field === name && entry.groups.includes(AggregationGroupType.TERMS),
  );
  return groupable ? field : null;
}

/**
 * How a typed fragment narrows the candidates at the source, or `null` when
 * the field lets no condition do it and the list is narrowed where it is.
 *
 * A substring is what a user typing into a list means — 「saga」 should find
 * 「QuotationSaga」 — so `CONTAINS` wins where the field offers it; a prefix
 * is the next best. Either compiles as the field's own conditions do,
 * case and all (`stringComparison`), so the source answers what the list
 * would have shown had it held every value.
 */
export function valueCandidateNarrowing(
  field: FieldDefinition,
  kinds: FieldKindRegistry,
): 'CONTAINS' | 'STARTS_WITH' | null {
  const kind = kinds.get(field.kind);
  const offered = kind ? operatorsOf(field, kind) : [];
  if (offered.includes('CONTAINS')) return 'CONTAINS';
  if (offered.includes('STARTS_WITH')) return 'STARTS_WITH';
  return null;
}

/** The number of values one candidate query asks for, under every ceiling. */
export function valueCandidateLimit(
  definition: DataViewDefinition,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): number {
  return Math.min(
    VALUE_CANDIDATE_LIMIT,
    definition.analysis?.limits?.maxLimit ?? Number.POSITIVE_INFINITY,
    AGGREGATION_LIMITS.MAX_LIMIT,
    limits.maxAnalysisRows,
  );
}

/**
 * The candidates of `field` as an analysis: records counted by the field's
 * value, the most frequent first, the first `valueCandidateLimit` of them —
 * narrowed to the values holding `query` where the field allows it.
 *
 * It is an ordinary analysis config on purpose, so it is admitted by
 * `validateAnalysis` and compiled by `compileAnalysis` like any other, and a
 * host scope is merged into it by the same `withScopeFilter` — there is no
 * second spelling of a Wow aggregation to keep in step with the first.
 *
 * Its dimension carries no sentinel bucket, unlike the one `groupOfType`
 * builds: a view that counts by value must not drop the records missing it,
 * but a list of values to compare against has no use for them — 「has no
 * value」 is `IS_NULL`, a condition of its own, not a value to pick.
 */
export function valueCandidatesConfig(
  definition: DataViewDefinition,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  query = '',
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): AnalysisViewConfig {
  const text = query.trim();
  const narrowing = text ? valueCandidateNarrowing(field, kinds) : null;
  const group: AnalysisGroup = {
    type: 'TERMS',
    field: field.name,
    alias: VALUE,
  };
  const metric: AnalysisMetric = { type: 'COUNT', alias: COUNT };
  return {
    kind: 'analysis',
    filter: {
      op: 'and',
      children: narrowing
        ? [{ field: field.name, operator: narrowing, value: text }]
        : [],
    },
    filterMode: 'advanced',
    refresh: { interval: null },
    groups: [group],
    metrics: [metric],
    sort: [
      { alias: COUNT, direction: 'DESC' },
      { alias: VALUE, direction: 'ASC' },
    ],
    limit: valueCandidateLimit(definition, limits),
    layout: 'table',
    table: { columns: [] },
    chart: fitChartSlots({ type: 'bar' }, [group], [metric], new Set()),
  };
}

/**
 * The rows a candidate query answered, as candidates.
 *
 * A key that is not a non-empty string is left out: an empty string is not a
 * value an equality condition takes (`IS_EMPTY_STRING` asks for it), and a
 * key of any other type cannot be what a text condition compares with.
 *
 * Whether the list is whole is read off the probe row `compileAnalysis` asks
 * for (`analysisProbeLimit`): it came back, so there are more values than the
 * limit. With no probe — the limit already on a ceiling — a full list is
 * called incomplete, since it may be.
 */
export function readValueCandidates(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): ValueCandidates {
  const probe = analysisProbeLimit(definition, config);
  const complete =
    probe > config.limit
      ? rows.length <= config.limit
      : rows.length < config.limit;
  const values = rows.slice(0, config.limit).flatMap(row => {
    const value = row[VALUE];
    const count = row[COUNT];
    return typeof value === 'string' && value !== ''
      ? [{ value, count: typeof count === 'number' ? count : 0 }]
      : [];
  });
  return { values, complete };
}

/**
 * The candidates in hand narrowed to those holding `query`, the way the
 * source would narrow them: ignoring case unless the field says its case
 * carries meaning, by substring or by prefix as `valueCandidateNarrowing`
 * picks — by substring where the source cannot narrow at all.
 */
export function narrowValueCandidates(
  values: readonly ValueCandidate[],
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  query: string,
): ValueCandidate[] {
  const text = query.trim();
  if (!text) return [...values];
  const sensitive = field.stringComparison === 'CASE_SENSITIVE';
  const fold = (value: string) => (sensitive ? value : value.toLowerCase());
  const wanted = fold(text);
  const prefix = valueCandidateNarrowing(field, kinds) === 'STARTS_WITH';
  return values.filter(({ value }) =>
    prefix ? fold(value).startsWith(wanted) : fold(value).includes(wanted),
  );
}
