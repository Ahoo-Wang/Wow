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

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  fieldAliasSegment,
  isSingleStringField,
  type AggregationFieldCapability,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type RuntimeLimits,
} from '../model/index.js';
import { emptyFilter } from '../filter/index.js';

const DEFAULT_LIMIT = 100;
const DEFAULT_PERCENTILE = 95;

/** Alias derived from a field path, kept to the single segment Wow allows. */
export function aliasOf(field: string, suffix: string): string {
  return `${fieldAliasSegment(field)}_${suffix}`;
}

/**
 * The first metric a definition can express, in a fixed order.
 *
 * `validateDefinition` only accepts a capability that can produce one of
 * these, so a definition that was admitted always has a usable default.
 */
function defaultMetric(
  capability: DataViewDefinition['analysis'],
): AnalysisMetric {
  if (!capability) throw new Error('no analysis capability');
  if (capability.count) return { type: 'COUNT', alias: 'count' };

  const numeric = capability.fields.find(entry => entry.functions.length > 0);
  if (numeric)
    return {
      type: 'NUMERIC',
      alias: aliasOf(numeric.field, numeric.functions[0].toLowerCase()),
      function: numeric.functions[0],
      expression: { type: 'FIELD', field: numeric.field },
    };

  const distinct = capability.fields.find(entry => entry.distinctCount);
  if (distinct)
    return {
      type: 'DISTINCT_COUNT',
      alias: aliasOf(distinct.field, 'distinct'),
      expression: { type: 'FIELD', field: distinct.field },
    };

  const percentile = capability.fields.find(entry => entry.percentile);
  if (percentile)
    return {
      type: 'PERCENTILE',
      alias: aliasOf(percentile.field, `p${DEFAULT_PERCENTILE}`),
      expression: { type: 'FIELD', field: percentile.field },
      percentile: DEFAULT_PERCENTILE,
    };

  const any = capability.fields.find(entry => entry.any);
  if (any)
    return { type: 'ANY', alias: aliasOf(any.field, 'any'), field: any.field };

  throw new Error('analysis capability declares no usable metric');
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
 * A TERMS dimension over `field`, with the sentinel bucket a new one starts
 * with — on the fields that can carry one, which is Wow's rule and
 * `validateGroups`'s.
 *
 * The editor builds its dimensions through this, so "a dimension never drops
 * records without saying so" is one rule in one place rather than a default
 * each surface repeats.
 */
export function termsGroup(
  field: FieldDefinition,
  alias: string,
  kind?: { singleString?: boolean },
): AnalysisGroup {
  return {
    type: 'TERMS',
    field: field.name,
    alias,
    ...(isSingleStringField(field, kind)
      ? { missingKey: DEFAULT_MISSING_KEY }
      : {}),
  };
}

function defaultGroup(
  definition: DataViewDefinition,
  fields: readonly AggregationFieldCapability[],
): AnalysisGroup[] {
  const terms = fields.find(entry =>
    entry.groups.includes(AggregationGroupType.TERMS),
  );
  if (!terms) return [];
  const field = definition.fields.find(entry => entry.name === terms.field);
  const alias = aliasOf(terms.field, 'group');
  // Nothing here holds a registry, so a field of a kind an application
  // registered itself is read by the built-in rule. Admission, which does
  // hold one, is the authority; this only decides what a first config says.
  return field
    ? [termsGroup(field, alias)]
    : [{ type: 'TERMS', field: terms.field, alias }];
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

  const metric = defaultMetric(capability);
  const groups = defaultGroup(definition, capability.fields);
  const limit = Math.min(
    capability.limits?.defaultLimit ?? DEFAULT_LIMIT,
    capability.limits?.maxLimit ?? Number.POSITIVE_INFINITY,
    limits.maxAnalysisRows,
  );

  return {
    filter: emptyFilter(),
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups,
    metrics: [metric],
    sort: groups.length > 0 ? [{ alias: metric.alias, direction: 'DESC' }] : [],
    limit,
    layout: groups.length > 0 ? 'chart' : 'table',
    table: { columns: [] },
    chart:
      groups.length > 0
        ? {
            type: 'bar',
            cartesian: {
              x: groups[0].alias,
              series: [{ metric: metric.alias }],
            },
          }
        : { type: 'metric', metric: { metric: metric.alias } },
  };
}
