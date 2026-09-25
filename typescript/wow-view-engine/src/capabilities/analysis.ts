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
  AggregationGroupType,
  type FieldAggregateDescriptor,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import type {
  AggregationFieldCapability,
  AnalysisMetric,
  AnalysisCapability,
  AnalysisElementCapability,
  AnalysisLimits,
  Issue,
  IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { warn } from './fields.js';
import { describedField } from './match.js';

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
    findings,
  };

  const next: AnalysisCapability = {
    ...capability,
    count: capability.count && offered.metrics.includes('COUNT'),
    fields: narrowAggregations(
      capability.fields,
      ['analysis', 'fields'],
      path => describedField(descriptor, path)?.aggregate,
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
  next.limits = lowered(capability.limits, descriptor);

  if (!constructible(next)) {
    findings.push(warn(issue('capability.analysis.unavailable', ['analysis'])));
    return undefined;
  }
  return next;
}

/** Every metric type there is, as a capability names them. */
const METRIC_TYPES: readonly AnalysisMetric['type'][] = [
  'COUNT',
  'NUMERIC',
  'ANY',
  'DISTINCT_COUNT',
  'PERCENTILE',
  'DERIVED',
];

/** The types of `listed` a capability can name; a server's own are left out. */
function metricTypes(listed: readonly string[]): AnalysisMetric['type'][] {
  return METRIC_TYPES.filter(type => listed.includes(type));
}

interface AggregationContext {
  metrics: readonly string[];
  /** The units a date histogram may bucket by (#3489). */
  dateUnits: readonly string[];
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
          describedField(descriptor, `${element.path}.${field}`, element.path)
            ?.aggregate,
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
            dropped: narrowed.dropped.join(', '),
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
  const numeric = metrics.includes('NUMERIC');
  // A date histogram buckets by the units both declare; none in common is
  // no date histogram at all.
  const dateUnits = declared.dateUnits?.filter(unit =>
    context.dateUnits.includes(unit),
  );
  const groups = declared.groups.filter(
    group =>
      aggregate.groups.includes(group) &&
      (group !== AggregationGroupType.DATE_HISTOGRAM ||
        (dateUnits ?? []).length > 0),
  );
  const functions = declared.functions.filter(
    fn => numeric && aggregate.functions.includes(fn),
  );
  const flag = (
    wanted: boolean | undefined,
    offered: boolean,
    metric: string,
  ): boolean => wanted === true && offered && metrics.includes(metric);
  const any = flag(declared.any, aggregate.any, 'ANY');
  const distinctCount = flag(
    declared.distinctCount,
    aggregate.distinctCount,
    'DISTINCT_COUNT',
  );
  const percentile = flag(
    declared.percentile,
    aggregate.percentile,
    'PERCENTILE',
  );

  const missingKey =
    declared.missingKey !== false &&
    aggregate.missingKey &&
    groups.includes(AggregationGroupType.TERMS);
  const dropped = [
    ...declared.groups.filter(group => !groups.includes(group)),
    ...(groups.includes(AggregationGroupType.TERMS) &&
    declared.missingKey !== false &&
    !aggregate.missingKey
      ? ['MISSING_KEY']
      : []),
    ...(declared.inMetricFilter !== false && !aggregate.inMetricFilter
      ? ['IN_METRIC_FILTER']
      : []),
    ...(declared.expressionInput !== false && !aggregate.expressionInput
      ? ['EXPRESSION_INPUT']
      : []),
    ...(groups.includes(AggregationGroupType.DATE_HISTOGRAM)
      ? (declared.dateUnits ?? []).filter(unit => !dateUnits?.includes(unit))
      : []),
    ...declared.functions.filter(fn => !functions.includes(fn)),
    ...(declared.any && !any ? ['ANY'] : []),
    ...(declared.distinctCount && !distinctCount ? ['DISTINCT_COUNT'] : []),
    ...(declared.percentile && !percentile ? ['PERCENTILE'] : []),
  ];
  if (
    groups.length === 0 &&
    functions.length === 0 &&
    !any &&
    !distinctCount &&
    !percentile
  )
    return { capability: null, dropped };

  const capability: AggregationFieldCapability = {
    ...declared,
    groups,
    functions,
  };
  if (dateUnits !== undefined) capability.dateUnits = dateUnits;
  if (!missingKey && groups.includes(AggregationGroupType.TERMS))
    capability.missingKey = false;
  if (!aggregate.inMetricFilter) capability.inMetricFilter = false;
  if (!aggregate.expressionInput) capability.expressionInput = false;
  if (declared.any !== undefined) capability.any = any;
  if (declared.distinctCount !== undefined)
    capability.distinctCount = distinctCount;
  if (declared.percentile !== undefined) capability.percentile = percentile;
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
function constructible(capability: AnalysisCapability): boolean {
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
      field.percentile === true,
  );
}
