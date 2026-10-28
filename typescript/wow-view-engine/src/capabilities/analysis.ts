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
  type FieldAggregateDescriptor,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client/dsl';
import {
  dateDiffUnitsOf,
  datePartsOf,
  AggregationMetricTypes,
  type AggregationFieldCapability,
  type AnalysisMetric,
  type AnalysisCapability,
  type AnalysisElementCapability,
  type AnalysisLimits,
  type Issue,
  type IssuePath,
  AggregationGroupTypes,
} from '../model/index.js';
import { listParam } from '../model/issue.js';
import { issue } from '../filter/index.js';
import { warn } from './fields.js';
import { describedField, type DescribedField } from './match.js';

/**
 * The analysis capability as the descriptor admits it (capabilities.md
 * 4.4): each field's groups, date units and functions cut to what its
 * path and the model feed, a
 * metric type the model does not offer taken off every field, an element
 * the model cannot aggregate over taken away, and the aggregation sizes
 * lowered to the entry's.
 *
 * `undefined` when not one metric is left to start from. That is a
 * deployment offering less, not a definition written wrong — admission's
 * `definition.analysis.no-metric` is the error for that — so it is a
 * warning, and the definition runs on without analyses.
 */
export function narrowAnalysis(
  capability: AnalysisCapability,
  descriptor: QueryModelDescriptor,
  findings: Issue[],
): AnalysisCapability | undefined {
  const offered = descriptor.analysis;
  const context: AggregationContext = {
    metrics: offered.metrics,
    dateUnits: offered.dateUnits,
    dateParts: offered.dateParts,
    findings,
  };

  const next: AnalysisCapability = {
    ...capability,
    count:
      capability.count &&
      offered.metrics.includes(AggregationMetricTypes.COUNT),
    fields: narrowAggregations(
      capability.fields,
      ['analysis', 'fields'],
      path => aggregateOf(describedField(descriptor, path)),
      undefined,
      context,
    ),
  };
  if (capability.count && !next.count)
    findings.push(warn(issue('capability.analysis.count', ['analysis'])));

  if (capability.elements)
    next.elements = narrowElements(capability.elements, descriptor, context);
  if (capability.expressions && !offered.expressions) {
    next.expressions = false;
    findings.push(warn(issue('capability.analysis.expressions', ['analysis'])));
  }
  // A time between two moments is measured in the units both offer (#3539);
  // the entry lists none where computed expressions are off.
  if (next.expressions) {
    const units = dateDiffUnitsOf(capability);
    next.dateDiffUnits = units.filter(unit =>
      (offered.dateDiffUnits as readonly string[]).includes(unit),
    );
  }
  if (capability.having && offered.having.metrics.length === 0) {
    next.having = false;
    findings.push(warn(issue('capability.analysis.having', ['analysis'])));
  } else if (capability.having) {
    // 「只保留」 compares only the metric types the source compares.
    const compared = metricTypes(offered.having.metrics);
    next.havingMetrics = (capability.havingMetrics ?? METRIC_TYPES).filter(
      type => compared.includes(type),
    );
  }
  if (capability.metricSort !== false && !offered.sort.metrics) {
    next.metricSort = false;
    findings.push(warn(issue('capability.analysis.metric-sort', ['analysis'])));
  }
  if (capability.dense !== false && !offered.dense) {
    next.dense = false;
    findings.push(warn(issue('capability.analysis.dense', ['analysis'])));
  }
  // What the source estimates is its to say, not the definition's: taken
  // as it is (#3489).
  next.approximate = metricTypes(offered.approximate);
  // The field a root FIRST / LAST is ordered by when it names none: the
  // model's event time, which only the descriptor knows (#3532).
  if (offered.firstLastOrderBy !== undefined)
    next.firstLastOrderBy = offered.firstLastOrderBy;
  else delete next.firstLastOrderBy;
  // Which windows Wow computes over every group is the source's to say too
  // (D87): a server before 9.9 sends none.
  if (offered.windows && offered.windows.length > 0)
    next.windows = [...offered.windows];
  else delete next.windows;
  // Whether a dimension may group the elements by a root field is the
  // source's to say as well: a server before 9.9 does not.
  if (offered.rootGroups === true && capability.rootGroups !== false)
    next.rootGroups = true;
  else delete next.rootGroups;
  next.limits = lowered(capability.limits, descriptor);

  if (!constructible(next)) {
    findings.push(warn(issue('capability.analysis.unavailable', ['analysis'])));
    return undefined;
  }
  return next;
}

/**
 * What a path feeds an analysis: nothing where its value is sensitive — a
 * masked value never groups and never feeds a metric, a metric's condition
 * or a formula, whatever the definition declares (#3744 review), as the
 * source refuses it.
 */
function aggregateOf(
  described: DescribedField | null,
): FieldAggregateDescriptor | undefined {
  return described?.sensitive ? undefined : described?.aggregate;
}

/** Every metric type there is, as a capability names them. */
const METRIC_TYPES: readonly AnalysisMetric['type'][] = [
  AggregationMetricTypes.COUNT,
  AggregationMetricTypes.NUMERIC,
  AggregationMetricTypes.ANY,
  AggregationMetricTypes.DISTINCT_COUNT,
  AggregationMetricTypes.PERCENTILE,
  AggregationMetricTypes.FIRST,
  AggregationMetricTypes.LAST,
  AggregationMetricTypes.DERIVED,
];

/** The types of `listed` a capability can name; a server's own are left out. */
function metricTypes(listed: readonly string[]): AnalysisMetric['type'][] {
  return METRIC_TYPES.filter(type => listed.includes(type));
}

interface AggregationContext {
  metrics: readonly string[];
  /** The units a date histogram may bucket by (#3489). */
  dateUnits: readonly string[];
  /** The calendar parts a `DATE_PART` group may take (#3524). */
  dateParts: readonly string[];
  findings: Issue[];
}

function narrowElements(
  elements: readonly AnalysisElementCapability[],
  descriptor: QueryModelDescriptor,
  context: AggregationContext,
): AnalysisElementCapability[] {
  const kept: AnalysisElementCapability[] = [];
  elements.forEach((element, index) => {
    const at: IssuePath = ['analysis', 'elements', index];
    const described = descriptor.elements.find(
      entry => entry.path === element.path,
    );
    if (!described?.aggregate) {
      context.findings.push(
        warn(
          issue('capability.analysis.element-unavailable', at, {
            path: element.path,
          }),
        ),
      );
      return;
    }
    kept.push({
      ...element,
      aggregations: narrowAggregations(
        element.aggregations,
        [...at, 'aggregations'],
        field =>
          aggregateOf(
            describedField(
              descriptor,
              `${element.path}.${field}`,
              element.path,
            ),
          ),
        element.path,
        context,
      ),
    });
  });
  return kept;
}

/**
 * Each field's offer cut to what its path feeds. A field with nothing left
 * — the path feeds no aggregation, or none of what was declared — is taken
 * out of the capability, and said to be.
 */
function narrowAggregations(
  fields: readonly AggregationFieldCapability[],
  at: IssuePath,
  aggregateOf: (field: string) => FieldAggregateDescriptor | undefined,
  scope: string | undefined,
  context: AggregationContext,
): AggregationFieldCapability[] {
  const kept: AggregationFieldCapability[] = [];
  fields.forEach((declared, index) => {
    const path =
      scope === undefined ? declared.field : `${scope}.${declared.field}`;
    const where: IssuePath = [...at, index];
    const narrowed = narrowAggregation(
      declared,
      aggregateOf(declared.field),
      context,
    );
    if (!narrowed.capability) {
      context.findings.push(
        warn(
          issue('capability.analysis.field-unavailable', where, {
            field: path,
          }),
        ),
      );
      return;
    }
    if (narrowed.dropped.length > 0)
      context.findings.push(
        warn(
          issue('capability.analysis.field-narrowed', where, {
            field: path,
            dropped: listParam(narrowed.dropped),
          }),
        ),
      );
    kept.push(narrowed.capability);
  });
  return kept;
}

function narrowAggregation(
  declared: AggregationFieldCapability,
  aggregate: FieldAggregateDescriptor | undefined,
  context: AggregationContext,
): { capability: AggregationFieldCapability | null; dropped: string[] } {
  if (!aggregate) return { capability: null, dropped: [] };
  const { metrics } = context;
  const numeric = metrics.includes(AggregationMetricTypes.NUMERIC);
  // A date histogram buckets by the units both declare; none in common is
  // no date histogram at all.
  const dateUnits = declared.dateUnits?.filter(unit =>
    context.dateUnits.includes(unit),
  );
  // So does a calendar part: the parts the field offers (every one when
  // it says none) that the model groups by.
  const wantedParts = datePartsOf(declared);
  const dateParts = wantedParts.filter(part =>
    context.dateParts.includes(part),
  );
  const groups = declared.groups.filter(
    group =>
      aggregate.groups.includes(group) &&
      (group !== AggregationGroupTypes.DATE_HISTOGRAM ||
        (dateUnits ?? []).length > 0) &&
      (group !== AggregationGroupTypes.DATE_PART || dateParts.length > 0),
  );
  const functions = declared.functions.filter(
    fn => numeric && aggregate.functions.includes(fn),
  );
  const flag = (
    wanted: boolean | undefined,
    offered: boolean,
    metric: string,
  ): boolean => wanted === true && offered && metrics.includes(metric);
  const any = flag(declared.any, aggregate.any, AggregationMetricTypes.ANY);
  const distinctCount = flag(
    declared.distinctCount,
    aggregate.distinctCount,
    AggregationMetricTypes.DISTINCT_COUNT,
  );
  const percentile = flag(
    declared.percentile,
    aggregate.percentile,
    AggregationMetricTypes.PERCENTILE,
  );
  // One flag offers both ends; the model lists each metric type on its own.
  const firstLast =
    flag(
      declared.firstLast,
      aggregate.firstLast,
      AggregationMetricTypes.FIRST,
    ) && metrics.includes(AggregationMetricTypes.LAST);

  const missingKey =
    declared.missingKey !== false &&
    aggregate.missingKey &&
    groups.includes(AggregationGroupTypes.TERMS);
  // What the source does not offer, each as the key of its word: the
  // finding names it in the reader's language, never by its wire value.
  const dropped = [
    ...declared.groups
      .filter(group => !groups.includes(group))
      .map(group => `label.group.type.${group}`),
    ...(groups.includes(AggregationGroupTypes.TERMS) &&
    declared.missingKey !== false &&
    !aggregate.missingKey
      ? ['label.capability.missing-key']
      : []),
    ...(declared.inMetricFilter !== false && !aggregate.inMetricFilter
      ? ['label.capability.in-metric-filter']
      : []),
    ...(declared.expressionInput !== false && !aggregate.expressionInput
      ? ['label.capability.expression-input']
      : []),
    ...(groups.includes(AggregationGroupTypes.DATE_HISTOGRAM)
      ? (declared.dateUnits ?? [])
          .filter(unit => !dateUnits?.includes(unit))
          .map(unit => `label.date-unit.${unit}`)
      : []),
    ...(groups.includes(AggregationGroupTypes.DATE_PART)
      ? wantedParts
          .filter(part => !dateParts.includes(part))
          .map(part => `label.date-part.${part}`)
      : []),
    ...[
      ...declared.functions.filter(fn => !functions.includes(fn)),
      ...(declared.any && !any ? [AggregationMetricTypes.ANY] : []),
      ...(declared.distinctCount && !distinctCount
        ? [AggregationMetricTypes.DISTINCT_COUNT]
        : []),
      ...(declared.percentile && !percentile
        ? [AggregationMetricTypes.PERCENTILE]
        : []),
    ].map(fn => `label.summary.fn.${fn}`),
    ...(declared.firstLast && !firstLast
      ? ['label.capability.first-last']
      : []),
  ];
  if (
    groups.length === 0 &&
    functions.length === 0 &&
    !any &&
    !distinctCount &&
    !percentile &&
    !firstLast
  )
    return { capability: null, dropped };

  const capability: AggregationFieldCapability = {
    ...declared,
    groups,
    functions,
  };
  if (dateUnits !== undefined) capability.dateUnits = dateUnits;
  if (
    groups.includes(AggregationGroupTypes.DATE_PART) &&
    dateParts.length < wantedParts.length
  )
    capability.dateParts = dateParts;
  if (!missingKey && groups.includes(AggregationGroupTypes.TERMS))
    capability.missingKey = false;
  if (!aggregate.inMetricFilter) capability.inMetricFilter = false;
  if (!aggregate.expressionInput) capability.expressionInput = false;
  if (declared.any !== undefined) capability.any = any;
  if (declared.distinctCount !== undefined)
    capability.distinctCount = distinctCount;
  if (declared.percentile !== undefined) capability.percentile = percentile;
  if (declared.firstLast !== undefined) capability.firstLast = firstLast;
  return { capability, dropped };
}

/** The declared sizes, each lowered to the entry's where the entry's is smaller. */
function lowered(
  declared: AnalysisLimits | undefined,
  descriptor: QueryModelDescriptor,
): AnalysisLimits {
  const entry = descriptor.limits.aggregation;
  const least = (own: number | undefined, theirs: number) =>
    own === undefined ? theirs : Math.min(own, theirs);
  return {
    ...declared,
    maxGroups: least(declared?.maxGroups, entry.maxGroups),
    maxMetrics: least(declared?.maxMetrics, entry.maxMetrics),
    maxElements: least(declared?.maxElements, entry.maxElements),
  };
}

/** Whether an analysis can start from anything: the count, or one metric of one field. */
export function constructible(capability: AnalysisCapability): boolean {
  if (capability.count) return true;
  const offers = [
    ...capability.fields,
    ...(capability.elements ?? []).flatMap(element => element.aggregations),
  ];
  return offers.some(
    field =>
      field.functions.length > 0 ||
      field.any === true ||
      field.distinctCount === true ||
      field.percentile === true ||
      field.firstLast === true,
  );
}
