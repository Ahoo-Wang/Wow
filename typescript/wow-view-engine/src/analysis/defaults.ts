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
  FIELD_METRIC_TYPES,
  fieldAliasSegment,
  isSingleStringField,
  without,
  type AggregationFieldCapability,
  type AnalysisDateUnit,
  type AnalysisFunction,
  type AnalysisGroup,
  type AnalysisGroupType,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldMetricType,
  type RuntimeLimits,
} from '../model/index.js';
import { emptyFilter } from '../filter/index.js';
import { fitChartSlots } from './chartSlots.js';
import { analysisScope } from './capability.js';
import { momentMetrics } from './metricFormat.js';

const DEFAULT_LIMIT = 100;

/**
 * The range 「前 N 组」 may take, and the N it stands for when nobody said one.
 *
 * `max` is the lowest of three ceilings — the capability's `maxLimit`, the
 * runtime's `maxAnalysisRows` and Wow's own `AGGREGATION_LIMITS.MAX_LIMIT` —
 * because a declaration may only lower what the layers under it allow; the
 * least is 1, since Wow refuses a limit under it. `fallback` is where a new
 * view starts, and what an emptied 「前 N 组」 field means: the model has no
 * "no limit" (Wow answers at most `MAX_LIMIT` rows whatever is asked), so
 * the honest reading of a blank is the number a view would have started at.
 *
 * One function for the three readers — the default config, the admission
 * rule and the tray's field — so the bounds a field says out loud are the
 * ones Apply is refused by, never a second copy of them.
 */
export interface AnalysisLimitBounds {
  max: number;
  fallback: number;
}

export function limitBounds(
  capability: NonNullable<DataViewDefinition['analysis']>,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): AnalysisLimitBounds {
  const max = Math.min(
    capability.limits?.maxLimit ?? Number.POSITIVE_INFINITY,
    limits.maxAnalysisRows,
    AGGREGATION_LIMITS.MAX_LIMIT,
  );
  return {
    max,
    fallback: Math.min(capability.limits?.defaultLimit ?? DEFAULT_LIMIT, max),
  };
}

/** The percentile a new percentile metric asks for. */
export const DEFAULT_PERCENTILE = 95;

/** The band a new number dimension starts with: one unit wide. */
const DEFAULT_INTERVAL = 1;

/** Alias derived from a field path, kept to the single segment Wow allows. */
export function aliasOf(field: string, suffix: string): string {
  return `${fieldAliasSegment(field)}_${suffix}`;
}

/**
 * The bucket key records with no value of the dimension land in.
 *
 * Without one, Wow drops those records from the whole result rather than
 * leaving them ungrouped: a count of orders by warehouse would silently omit
 * every order that has no warehouse, and nothing on screen would say so. It
 * is a stored key and not a label — it travels to the server and comes back
 * as the bucket's own key — so it is one fixed string in every language, and
 * the wording a reader sees for that bucket is the interface's to decide.
 */
export const DEFAULT_MISSING_KEY = '(empty)';

/**
 * What building a dimension needs to know of its field, and nothing more —
 * so a declared field read with its capability (a fresh config, a split) and
 * the editor's `AnalysisFieldOption` (a field picked on the tray) are the
 * same input to the one builder.
 */
export interface GroupFacts {
  /** The field, named as a config names it. */
  field: string;
  /**
   * Whether one record holds at most one string there
   * (`isSingleStringField`): only then may the records missing it keep a
   * bucket of their own, which is Wow's rule and `validateGroups`'s.
   */
  missingKey: boolean;
  /** The date units the capability offers; a time dimension starts at the first. */
  dateUnits: readonly AnalysisDateUnit[];
}

/**
 * The facts of a declared field. Nothing in a kernel holds a registry, so a
 * field of a kind an application registered itself is read by the built-in
 * rule unless the caller hands its kind in; admission, which does hold one,
 * is the authority.
 */
export function groupFacts(
  field: FieldDefinition,
  dateUnits: readonly AnalysisDateUnit[] = [],
  kind?: { singleString?: boolean },
): GroupFacts {
  return {
    field: field.name,
    missingKey: isSingleStringField(field, kind),
    dateUnits,
  };
}

/**
 * The one builder of a dimension: `type` on a field, whole rather than as a
 * patch. A fresh config, a split from the follow-up menu, a field picked on
 * the tray and a card switched to another type all build through this, so
 * each default is said once:
 *
 * - by value, the sentinel bucket wherever the field can carry one — without
 *   it Wow drops every record missing the value, and a dimension never drops
 *   records without saying so;
 * - by date, at `unit` when the caller recommends one (K4), else the first
 *   unit the capability offers, else a day;
 * - by band, one unit wide.
 */
export function groupOfType(
  facts: GroupFacts,
  type: AnalysisGroupType,
  alias: string,
  unit?: AnalysisDateUnit,
): AnalysisGroup {
  switch (type) {
    case 'DATE_HISTOGRAM':
      return {
        type,
        field: facts.field,
        alias,
        unit: unit ?? facts.dateUnits[0] ?? 'DAY',
      };
    case 'HISTOGRAM':
      return { type, field: facts.field, alias, interval: DEFAULT_INTERVAL };
    case 'TERMS':
      return {
        type,
        field: facts.field,
        alias,
        ...(facts.missingKey ? { missingKey: DEFAULT_MISSING_KEY } : {}),
      };
  }
}

/**
 * The fields a dimension may still be added on: the groupable ones `groups`
 * does not already cut by. Cutting by one field twice is a question nobody
 * asks — the second cut makes exactly the groups the first one did — so the
 * tray's "add dimension" (over the draft) and the follow-up menu's split
 * (over the config that ran) both read this list.
 */
export function groupableFields<
  F extends { field: string; groups: readonly unknown[] },
>(fields: readonly F[], groups: readonly Pick<AnalysisGroup, 'field'>[]): F[] {
  const grouped = new Set(groups.map(group => group.field));
  return fields.filter(
    entry => entry.groups.length > 0 && !grouped.has(entry.field),
  );
}

/**
 * How a metric summarises its field, as one choice: a function for a
 * numeric metric, the metric's own type for the other types that measure a
 * field (`FIELD_METRIC_TYPES`). This is what a tray card's "summary" select
 * shows and takes, so the six ways Wow can measure a field read as one list
 * (D20 汇总方式).
 */
export type SummaryChoice =
  AnalysisFunction | Exclude<FieldMetricType, 'NUMERIC'>;

/** What a field offers to be measured by: its capability, or the editor's option of it. */
export interface MetricFacts {
  field: string;
  functions: readonly AnalysisFunction[];
  distinctCount?: boolean;
  percentile?: boolean;
  any?: boolean;
}

/** Which flag of a field offers each metric type that is not a function. */
const OFFERED_BY = {
  DISTINCT_COUNT: 'distinctCount',
  PERCENTILE: 'percentile',
  ANY: 'any',
} as const satisfies Record<
  Exclude<FieldMetricType, 'NUMERIC'>,
  keyof MetricFacts
>;

/** The choices a field offers, in the order `FIELD_METRIC_TYPES` lists them. */
export function summaryChoices(facts: MetricFacts): SummaryChoice[] {
  return FIELD_METRIC_TYPES.flatMap<SummaryChoice>(type => {
    if (type === 'NUMERIC') return [...facts.functions];
    return facts[OFFERED_BY[type]] === true ? [type] : [];
  });
}

/** The choice a metric was built from, or `null` for one that measures no field. */
export function summaryOf(metric: AnalysisMetric): SummaryChoice | null {
  switch (metric.type) {
    case 'NUMERIC':
      return metric.function;
    case 'DISTINCT_COUNT':
    case 'PERCENTILE':
    case 'ANY':
      return metric.type;
    default:
      return null;
  }
}

/**
 * The one builder of a metric over a field: `choice` on it, whole — a card
 * that changes the summary replaces the metric rather than patching it, and
 * a fresh config's first metric is built the same way.
 */
export function metricOfSummary(
  facts: Pick<MetricFacts, 'field'>,
  choice: SummaryChoice,
  alias: string,
): AnalysisMetric {
  const expression = { type: 'FIELD', field: facts.field } as const;
  switch (choice) {
    case 'DISTINCT_COUNT':
      return { type: 'DISTINCT_COUNT', alias, expression };
    case 'PERCENTILE':
      return {
        type: 'PERCENTILE',
        alias,
        expression,
        percentile: DEFAULT_PERCENTILE,
      };
    case 'ANY':
      return { type: 'ANY', alias, field: facts.field };
    default:
      return { type: 'NUMERIC', alias, function: choice, expression };
  }
}

/**
 * The first metric a set of aggregation capabilities can express, in a
 * fixed order: the count, then the first field that can be summed, counted
 * distinctly, taken a percentile of, or sampled. The same rule seeds a
 * fresh definition and an analysis whose expansion left nothing to measure
 * (`withElements`), over whichever fields that unit holds.
 *
 * `validateDefinition` only accepts a capability that can produce one of
 * these, so a definition that was admitted always has a usable default.
 */
export function firstMetric(
  count: boolean,
  fields: readonly AggregationFieldCapability[],
): AnalysisMetric {
  if (count) return { type: 'COUNT', alias: 'count' };

  const numeric = fields.find(entry => entry.functions.length > 0);
  if (numeric) {
    const fn = numeric.functions[0];
    return metricOfSummary(
      numeric,
      fn,
      aliasOf(numeric.field, fn.toLowerCase()),
    );
  }

  const distinct = fields.find(entry => entry.distinctCount);
  if (distinct)
    return metricOfSummary(
      distinct,
      'DISTINCT_COUNT',
      aliasOf(distinct.field, 'distinct'),
    );

  const percentile = fields.find(entry => entry.percentile);
  if (percentile)
    return metricOfSummary(
      percentile,
      'PERCENTILE',
      aliasOf(percentile.field, `p${DEFAULT_PERCENTILE}`),
    );

  const any = fields.find(entry => entry.any);
  if (any) return metricOfSummary(any, 'ANY', aliasOf(any.field, 'any'));

  throw new Error('analysis capability declares no usable metric');
}

/**
 * The dimension a fresh config starts with: the first field the capability
 * groups by value, if any.
 */
function firstGroups(
  definition: DataViewDefinition,
  fields: readonly AggregationFieldCapability[],
): AnalysisGroup[] {
  const terms = fields.find(entry =>
    entry.groups.includes(AggregationGroupType.TERMS),
  );
  if (!terms) return [];
  const field = definition.fields.find(entry => entry.name === terms.field);
  const facts: GroupFacts = field
    ? groupFacts(field)
    : { field: terms.field, missingKey: false, dateUnits: [] };
  return [groupOfType(facts, 'TERMS', aliasOf(terms.field, 'group'))];
}

/**
 * A complete starting config. Grouping is optional, because Wow accepts an
 * ungrouped aggregation and some definitions declare no groupable field.
 *
 * An ungrouped aggregation is one row, so there is nothing to sort: Wow
 * refuses a `sort` without a `groupBy`, and the default must not carry one.
 */
export function defaultAnalysisConfig(
  definition: DataViewDefinition,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): AnalysisViewConfig {
  const capability = definition.analysis;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no analysis capability`,
    );

  // The root's offers as the scope reads them, so a date field's declared
  // sum is not the metric a view starts with (`aggregationFunctionsOf`).
  const scope = analysisScope(definition, capability);
  const metric = firstMetric(capability.count, [
    ...scope.aggregations.values(),
  ]);
  // The latest of a date is read, not drawn: a view that starts on one
  // starts as its table.
  const moments = momentMetrics([metric], scope.fields);
  const groups = firstGroups(definition, capability.fields);
  const limit = limitBounds(capability, limits).fallback;

  return {
    filter: emptyFilter(),
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups,
    metrics: [metric],
    sort: groups.length > 0 ? [{ alias: metric.alias, direction: 'DESC' }] : [],
    limit,
    layout: groups.length > 0 && moments.size === 0 ? 'chart' : 'table',
    table: { columns: [] },
    // Through the same fitting the editor uses, so the first chart a view has
    // and every chart it is switched to are filled in by one rule.
    chart: fitChartSlots(
      { type: groups.length > 0 ? 'bar' : 'metric' },
      groups,
      [metric],
      moments,
    ),
  };
}

/**
 * A name no group or metric is using: `base`'s stem plus the first free
 * number. Numbering by the row count collided as soon as a row was removed,
 * so the first free number it is, however rows were added and removed.
 * Aliases are single-segment in Wow, so a field path becomes one token, and
 * a copy of `amount_2` is `amount_<next>` rather than `amount_2_1`.
 */
export function freeAlias(base: string, taken: readonly string[]): string {
  const stem = base.split('.').join('_').replace(/_\d+$/, '');
  const used = new Set(taken);
  for (let index = 1; ; index += 1) {
    const alias = `${stem}_${index}`;
    if (!used.has(alias)) return alias;
  }
}

/**
 * A second card of the same metric, for a condition of its own (D20 屏 H
 * 「复制并加条件」): a free alias, no display name — two cards called the
 * same thing is the ambiguity a name exists to resolve — the summary kept,
 * and no condition yet. The card opens its conditions for one; an empty
 * group written here would be a draft that refuses itself before the
 * analyst has done anything (2026-09-23 audit), so the condition is written
 * with its first entry. A derived metric carries no condition and is not
 * copied.
 */
export function metricWithCondition(
  source: AnalysisMetric,
  taken: readonly string[],
): AnalysisMetric | undefined {
  if (source.type === 'DERIVED') return undefined;
  return {
    ...without(without(source, 'label'), 'filter'),
    alias: freeAlias(source.alias, taken),
  } as AnalysisMetric;
}
