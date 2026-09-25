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
  derivedText,
  expressionText,
  freeAlias,
  groupOfType,
  isFormula,
  metricCondition,
  metricFunctionOf,
  metricOfSummary,
  summaryChoices,
  type MetricCondition,
} from '../../analysis/index.js';
import { columnTitle } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';

export { freeAlias };
import type {
  AnalysisDateUnit,
  AnalysisGroup,
  AnalysisMetric,
} from '../../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../../react/index.js';

/**
 * What the tray builds when a field is picked — the dimension or the metric
 * a field starts as, under a free alias — and what a metric is called. The
 * shapes are the analysis kernel's one builder of each (`groupOfType`,
 * `metricOfSummary`); this only picks the tray's alias and first choice.
 * Pure, so the cards stay markup and the tests read the rules straight.
 */

/**
 * What naming a metric reads: the fields its formula or summary is over,
 * the other metrics a derived one refers to, and — where there is a runtime
 * to read them — the fields and kinds its own condition is read by.
 */
export type MetricNaming = Pick<
  AnalysisEditorController,
  'fields' | 'metrics'
> &
  Partial<
    Pick<AnalysisEditorController, 'conditionFields' | 'kinds' | 'approximate'>
  >;

/** The condition a metric counts under, read as its result column reads it. */
export function conditionOf(
  analysis: MetricNaming,
  metric: AnalysisMetric,
): MetricCondition | undefined {
  return metricCondition(
    metric,
    analysis.conditionFields ?? [],
    analysis.kinds,
  );
}

/** Every alias in use, which is the set an addition must stay clear of. */
export function usedAliases(analysis: AnalysisEditorController): string[] {
  return [...analysis.aliases.groups, ...analysis.aliases.metrics];
}

/**
 * The dimension a field picked on the tray starts as: the first shape its
 * capability offers, a time dimension at the granularity recommended for the
 * range (K4). The shape is the kernel's one builder's (`groupOfType`).
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

/** The metric a field picked on the tray starts as: the first way it can be measured. */
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
 * no such neighbour, so 「金额的总和」 and 「金额的平均」 would read as one
 * name. This composes the summary in exactly the way the result column and
 * the chart slots do — `columnTitle` over the same label and the same
 * function — so a metric reads the same word wherever it is mentioned.
 * Its own condition goes along too (D20 显示名): two cards over one field
 * that differ only in what they count are 「金额的总和」 and 「金额的总和 ·
 * 已发运」 here as they are in the result.
 */
export function metricReference(
  analysis: MetricNaming,
  metric: AnalysisMetric,
  messages: MessageFormatters,
): string {
  // The field's reading goes along, so the latest of a datetime is named
  // 「最晚」 here as its result column is.
  const cell = analysis.fields.find(
    entry => entry.field === fieldOfMetric(metric),
  )?.cell;
  const condition = conditionOf(analysis, metric);
  return columnTitle(
    metric.label === undefined
      ? {
          label: metricFallbackName(analysis, metric, messages, true),
          fn: metricFunctionOf(metric),
          ...(cell === undefined ? {} : { cell }),
          ...(condition ? { condition } : {}),
          ...(analysis.approximate
            ? { approximate: analysis.approximate.includes(metric.type) }
            : {}),
        }
      : { label: metric.label, named: true },
    messages,
    analysis.approximate,
  );
}

/**
 * What a dimension is called away from its own card — in the sort, in a
 * finding — as its result column is headed: the name the analyst gave, else
 * its field, a time dimension with its granularity (「创建时间（按日）」).
 */
export function groupReference(
  analysis: Pick<MetricNaming, 'fields'>,
  group: AnalysisGroup,
  messages: MessageFormatters,
): string {
  return columnTitle(
    group.label === undefined
      ? {
          label:
            analysis.fields.find(entry => entry.field === group.field)?.label ??
            group.field,
          ...(group.type === 'DATE_HISTOGRAM' ? { dateUnit: group.unit } : {}),
        }
      : { label: group.label, named: true },
    messages,
  );
}

/**
 * The name a metric falls back to without one of its own. `composed` is for
 * a name a summary is put around (`metricReference`): a formula is then
 * bracketed, 「(金额 − 成本)的总和」, as its result column is
 * (`projectAnalysis`) — bare, the sum read as belonging to 成本 alone. Its
 * own card says it bare, with the summary control beside it.
 */
export function metricFallbackName(
  analysis: MetricNaming,
  metric: AnalysisMetric,
  messages: MessageFormatters,
  composed = false,
): string {
  const fieldLabel = (field: string) =>
    analysis.fields.find(entry => entry.field === field)?.label ?? field;
  if (metric.type === 'COUNT')
    return messages.label('label.analysis.row-count');
  if (isFormula(metric))
    return expressionText(metric.expression, fieldLabel, composed);
  if (metric.type === 'DERIVED')
    return derivedText(metric.expression, alias => {
      // An operand is a reference to another metric, and says its summary
      // (「金额的总和 ÷ 记录数」), as the derived column's header does.
      const referenced = analysis.metrics.find(entry => entry.alias === alias);
      return referenced
        ? metricReference(analysis, referenced, messages)
        : alias;
    });
  const field = fieldOfMetric(metric);
  return fieldLabel(field);
}
