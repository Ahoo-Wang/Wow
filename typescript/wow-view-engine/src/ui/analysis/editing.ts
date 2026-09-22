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
  DEFAULT_MISSING_KEY,
  derivedText,
  expressionText,
  freeAlias,
  isFormula,
  metricFunctionOf,
} from '../../analysis/index.js';
import { columnTitle } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';

export { freeAlias };
import type {
  AnalysisDateUnit,
  AnalysisFunction,
  AnalysisGroup,
  AnalysisMetric,
} from '../../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../../react/index.js';

/**
 * What the tray builds when a field is picked: the dimension or the metric a
 * field starts as, and the shape a choice on a card turns it into. Pure, so
 * the cards stay markup and the tests read the rules straight.
 */

/**
 * What naming a metric reads: the fields its formula or summary is over,
 * and the other metrics a derived one refers to.
 */
export type MetricNaming = Pick<AnalysisEditorController, 'fields' | 'metrics'>;

/** Every alias in use, which is the set an addition must stay clear of. */
export function aliasesOf(analysis: AnalysisEditorController): string[] {
  return [...analysis.aliases.groups, ...analysis.aliases.metrics];
}

/**
 * The dimension a field starts as: the first shape its capability allows,
 * a time dimension at the granularity recommended for the range (K4).
 */
export function defaultGroup(
  field: AnalysisFieldOption,
  taken: readonly string[],
  unit?: AnalysisDateUnit,
): AnalysisGroup {
  return groupOfType(
    field,
    field.groups[0] ?? 'TERMS',
    freeAlias(field.field, taken),
    unit,
  );
}

/**
 * A dimension of `type` on `field`, whole rather than as a patch. A
 * dimension by value starts with the sentinel bucket wherever the field can
 * carry one: without it Wow drops every record missing the value, and a
 * dimension never drops records without saying so (`termsGroup`).
 */
export function groupOfType(
  field: AnalysisFieldOption,
  type: string,
  alias: string,
  unit?: AnalysisDateUnit,
): AnalysisGroup {
  if (type === 'DATE_HISTOGRAM')
    return {
      type: 'DATE_HISTOGRAM',
      field: field.field,
      alias,
      unit: unit ?? field.dateUnits[0] ?? 'DAY',
    };
  if (type === 'HISTOGRAM')
    return { type: 'HISTOGRAM', field: field.field, alias, interval: 1 };
  return {
    type: 'TERMS',
    field: field.field,
    alias,
    ...(field.missingKey ? { missingKey: DEFAULT_MISSING_KEY } : {}),
  };
}

/**
 * How a metric summarises its field, as one choice: a function for a
 * numeric metric, the metric's own type for the others. This is what the
 * card's "summary" select shows and takes, so the six ways Wow can measure a
 * field read as one list (D20 汇总方式).
 */
export type SummaryChoice =
  AnalysisFunction | 'DISTINCT_COUNT' | 'PERCENTILE' | 'ANY';

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

/** The choices a field offers, in the order the card lists them. */
export function summaryChoices(field: AnalysisFieldOption): SummaryChoice[] {
  return [
    ...field.functions,
    ...(field.distinctCount ? (['DISTINCT_COUNT'] as const) : []),
    ...(field.percentile ? (['PERCENTILE'] as const) : []),
    ...(field.any ? (['ANY'] as const) : []),
  ];
}

/** A metric of `choice` on `field`, whole: the card replaces, never patches. */
export function metricOfSummary(
  field: AnalysisFieldOption,
  choice: SummaryChoice,
  alias: string,
): AnalysisMetric {
  const expression = { type: 'FIELD', field: field.field } as const;
  switch (choice) {
    case 'DISTINCT_COUNT':
      return { type: 'DISTINCT_COUNT', alias, expression };
    case 'PERCENTILE':
      return { type: 'PERCENTILE', alias, expression, percentile: 95 };
    case 'ANY':
      return { type: 'ANY', alias, field: field.field };
    default:
      return { type: 'NUMERIC', alias, function: choice, expression };
  }
}

/** The metric a field starts as: the first way it can be measured. */
export function defaultMetric(
  field: AnalysisFieldOption,
  taken: readonly string[],
): AnalysisMetric {
  const choice = summaryChoices(field)[0] ?? 'ANY';
  return metricOfSummary(field, choice, freeAlias(field.field, taken));
}

/** The field a metric measures, or none for a count and a derived metric. */
export function fieldOfMetric(metric: AnalysisMetric): string {
  if (metric.type === 'COUNT' || metric.type === 'DERIVED') return '';
  if (metric.type === 'ANY') return metric.field;
  return metric.expression.type === 'FIELD' ? metric.expression.field : '';
}

/**
 * What a metric is called on the tray: the name the analyst gave, else
 * what the metric composes — the record count's own word, a formula or a
 * derived metric said as its author would, a field's label otherwise.
 */
export function metricName(
  analysis: MetricNaming,
  metric: AnalysisMetric,
  messages: MessageFormatters,
): string {
  return metric.label ?? metricFallbackName(analysis, metric, messages);
}

/**
 * What a metric is called *away from its own card* — in「只保留」, in the
 * sort, as an operand of a derived metric, and in any label that names one.
 *
 * The card's own title says the bare field name, because the summary
 * combobox sits right beside it and says the rest. Everywhere else there is
 * no such neighbour, so 「金额 的 合计」 and 「金额 的 平均」 would read as one
 * name. This composes the summary in exactly the way the result column and
 * the chart slots do — `columnTitle` over the same label and the same
 * function — so a metric reads the same word wherever it is mentioned.
 */
export function metricReference(
  analysis: MetricNaming,
  metric: AnalysisMetric,
  messages: MessageFormatters,
): string {
  return columnTitle(
    metric.label === undefined
      ? {
          label: metricFallbackName(analysis, metric, messages),
          fn: metricFunctionOf(metric),
        }
      : { label: metric.label, named: true },
    messages,
  );
}

/** The name a metric falls back to without one of its own. */
export function metricFallbackName(
  analysis: MetricNaming,
  metric: AnalysisMetric,
  messages: MessageFormatters,
): string {
  const fieldLabel = (field: string) =>
    analysis.fields.find(entry => entry.field === field)?.label ?? field;
  if (metric.type === 'COUNT')
    return messages.label('label.analysis.row-count');
  if (isFormula(metric)) return expressionText(metric.expression, fieldLabel);
  if (metric.type === 'DERIVED')
    return derivedText(metric.expression, alias => {
      const referenced = analysis.metrics.find(entry => entry.alias === alias);
      return referenced ? metricName(analysis, referenced, messages) : alias;
    });
  const field = fieldOfMetric(metric);
  return fieldLabel(field);
}
