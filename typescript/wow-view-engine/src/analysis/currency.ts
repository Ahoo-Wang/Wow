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
  currencyPathOf,
  type AnalysisDerivedExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CurrencyReading,
  type DataViewDefinition,
  type Issue,
  type RecordData,
} from '../model/index.js';
import { companionReading, joinCurrencies } from '../model/currency.js';
import { issue } from '../filter/index.js';
import { analysisScope } from './capability.js';
import { metricFieldOf, metricFunctionOf } from './metricFormat.js';

/**
 * The two metrics asked beside a metric of money whose currency each record
 * holds, so its answer can say whose money it is (capabilities.md 21):
 * how many currencies the records behind each row hold (`count`, a
 * `DISTINCT_COUNT` of the currency field) and one of them (`code`, an
 * `ANY`). One currency is that currency; more than one means the row's sum
 * adds unlike amounts, and it is not shown as a number at all.
 *
 * They carry the metric's own condition, so a conditioned metric is told
 * about the records it counted, not the whole group.
 */
export interface CurrencyCompanion {
  /** The metric it tells about. */
  metric: string;
  /** The currency field, as the config spells a field. */
  field: string;
  /** Alias of the `ANY` of the currency field. */
  code: string;
  /** Alias of the `DISTINCT_COUNT` of the currency field. */
  count: string;
}

/** A companion's aliases, derived from the metric's own. */
export function companionAliases(alias: string): {
  code: string;
  count: string;
} {
  return { code: `${alias}__currency`, count: `${alias}__currencies` };
}

/**
 * The metrics of a config that are money in a currency each record holds,
 * with what tells their currency. A metric is one when its value is in the
 * field's unit — a sum, an average, a smallest or largest, a percentile, a
 * value of one record — of a field whose `numeric` is row-currency money and
 * whose author declared no format of their own. A count, a distinct count
 * or a formula is in no one's currency.
 *
 * A companion is asked only where the source can answer it: the currency
 * field takes both a distinct count and an `ANY` in this scope, and the
 * metrics fit the capability's `maxMetrics` with them. Where it cannot, the
 * metric is listed in `unchecked`: its currency cannot be told, and a
 * reader is told so rather than shown a sum that may add yen to dollars.
 */
export function currencyCompanions(
  definition: DataViewDefinition,
  config: Pick<AnalysisViewConfig, 'metrics' | 'elements'>,
): { companions: CurrencyCompanion[]; unchecked: string[] } {
  const capability = definition.analysis;
  if (!capability) return { companions: [], unchecked: [] };
  const scope = analysisScope(definition, capability, config);
  const found: { metric: AnalysisMetric; field: string }[] = [];
  for (const metric of config.metrics) {
    if (!carriesCurrency(metric)) continue;
    const name = metricFieldOf(metric);
    const field = name === undefined ? undefined : scope.fields.get(name);
    const currency = field && name && currencyPathOf(field, name);
    if (currency !== undefined) found.push({ metric, field: currency });
  }
  const room =
    capability.limits?.maxMetrics === undefined
      ? Number.POSITIVE_INFINITY
      : capability.limits.maxMetrics - config.metrics.length;
  const companions: CurrencyCompanion[] = [];
  const unchecked: string[] = [];
  for (const { metric, field } of found) {
    const aggregation = scope.aggregations.get(field);
    const answerable =
      aggregation?.any === true &&
      aggregation.distinctCount === true &&
      (companions.length + 1) * 2 <= room;
    if (answerable)
      companions.push({
        metric: metric.alias,
        field,
        ...companionAliases(metric.alias),
      });
    else unchecked.push(metric.alias);
  }
  return { companions, unchecked };
}

/** Whether a metric's value is in its field's unit, and so in its currency. */
function carriesCurrency(metric: AnalysisMetric): boolean {
  switch (metricFunctionOf(metric)) {
    case 'COUNT':
    case 'DISTINCT_COUNT':
    case 'DERIVED':
      return false;
    default:
      return true;
  }
}

/** The companions of a config, as metrics the query asks beside its own. */
export function companionMetrics(
  config: Pick<AnalysisViewConfig, 'metrics'>,
  companions: readonly CurrencyCompanion[],
): AnalysisMetric[] {
  const byAlias = new Map(config.metrics.map(metric => [metric.alias, metric]));
  return companions.flatMap(companion => {
    const metric = byAlias.get(companion.metric);
    const filter =
      metric && 'filter' in metric ? (metric.filter ?? undefined) : undefined;
    const condition = filter === undefined ? {} : { filter };
    return [
      {
        type: 'DISTINCT_COUNT',
        alias: companion.count,
        expression: { type: 'FIELD', field: companion.field },
        ...condition,
      },
      {
        type: 'ANY',
        alias: companion.code,
        field: companion.field,
        ...condition,
      },
    ] satisfies AnalysisMetric[];
  });
}

/**
 * The metrics a derived metric is computed from, by alias, all the way
 * down: GMV ÷ orders reads GMV's currency, and a ratio of a ratio reads
 * the operands of both.
 */
export function derivedOperands(
  metric: AnalysisMetric,
  byAlias: ReadonlyMap<string, AnalysisMetric>,
  seen: Set<string> = new Set(),
): string[] {
  if (metric.type !== 'DERIVED') return [metric.alias];
  const refs: string[] = [];
  const walk = (node: AnalysisDerivedExpression): void => {
    if (node.type === 'METRIC_REF') refs.push(node.metric);
    else if (node.type === 'BINARY') {
      walk(node.left);
      walk(node.right);
    }
  };
  walk(metric.expression);
  return refs.flatMap(ref => {
    const operand = byAlias.get(ref);
    if (!operand || seen.has(ref)) return [];
    seen.add(ref);
    return derivedOperands(operand, byAlias, seen);
  });
}

/** Readings of several companions, joined: see `joinCurrencies`. */
export function companionsReading(
  row: RecordData | undefined,
  companions: readonly Pick<CurrencyCompanion, 'code' | 'count'>[],
): CurrencyReading | undefined {
  return joinCurrencies(
    companions.map(companion => companionReading(row, companion)),
  );
}

/**
 * The codes a result says about its money beside the rows
 * (`currencyIssues`): drawn beside the result they are about, with the offer
 * to group by the currency field.
 */
export const CURRENCY_ISSUE_CODES: readonly string[] = [
  'analysis.result.mixed-currency',
  'analysis.metric.currency-unchecked',
];

/**
 * What a result says about its money: each metric some of whose rows are
 * in several currencies — blank there, and why (`analysis.result.mixed-currency`)
 * — and each whose currency the source could not be asked for
 * (`analysis.metric.currency-unchecked`), whose sums may add unlike
 * amounts unseen. Both name the currency field (`field`, and the raw
 * `currencyField` a screen offers to group by), because grouping by it is
 * what separates the amounts. A result already grouped by it is in one
 * currency per row and says nothing: its companions tell each row's.
 */
export function currencyIssues(
  definition: DataViewDefinition,
  config: Pick<AnalysisViewConfig, 'metrics' | 'elements' | 'groups'>,
  rows: readonly RecordData[],
): Issue[] {
  const { companions, unchecked } = currencyCompanions(definition, config);
  const index = new Map(
    config.metrics.map((metric, at) => [metric.alias, at] as const),
  );
  const grouped = (field: string) =>
    config.groups.some(group => group.field === field);
  const found: Issue[] = [];
  for (const companion of companions) {
    // Grouped by its currency, every row is in one; the whole under
    // 「合计」 still holds them all and says so in its own cell.
    if (grouped(companion.field)) continue;
    if (!rows.some(row => companionReading(row, companion)?.type === 'mixed'))
      continue;
    found.push(
      issue(
        'analysis.result.mixed-currency',
        ['metrics', index.get(companion.metric) ?? 0],
        {
          metric: companion.metric,
          field: companion.field,
          currencyField: companion.field,
        },
        'warning',
      ),
    );
  }
  const capability = definition.analysis;
  if (unchecked.length > 0 && capability) {
    const scope = analysisScope(definition, capability, config);
    for (const alias of unchecked) {
      const metric = config.metrics[index.get(alias) ?? -1];
      const name = metric && metricFieldOf(metric);
      const field = name === undefined ? undefined : scope.fields.get(name);
      const currency = field && name && currencyPathOf(field, name);
      if (currency === undefined || grouped(currency)) continue;
      found.push(
        issue(
          'analysis.metric.currency-unchecked',
          ['metrics', index.get(alias) ?? 0],
          { metric: alias, field: currency, currencyField: currency },
          'warning',
        ),
      );
    }
  }
  return found;
}
