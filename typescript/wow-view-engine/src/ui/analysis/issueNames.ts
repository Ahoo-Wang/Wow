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

import { CHART_TYPES, type ChartType, type Issue } from '../../model/index.js';
import type { ChartUnfit } from '../../analysis/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { summaryFunctionKey } from '../display.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import {
  groupReference,
  metricReference,
  type MetricNaming,
} from './editing.js';

/**
 * What a finding's aliases are read against: the draft's dimensions and
 * metrics — the config the findings are about — and what names their
 * fields and conditions.
 */
export type AnalysisNaming = MetricNaming &
  Pick<AnalysisEditorController, 'groups'> &
  Partial<Pick<AnalysisEditorController, 'expandable'>>;

/** The params a finding names a dimension or a metric by. */
const COLUMN_PARAMS = ['alias', 'metric'] as const;

/** The params a finding names a field by: its path, or an expansion's. */
const FIELD_PARAMS = ['field', 'path'] as const;

/**
 * The families whose findings the analysis view shows. `filter.*` rides
 * along for its fields only: the analysis scope's conditions are said in
 * the same status line, and a field there is the same field.
 */
const NAMED_FAMILIES = ['analysis.', 'chart.', 'filter.'] as const;

/**
 * Where an alias is the subject rather than a reference: the finding is
 * that the key itself is wrong, and two columns that share one would be
 * named as two different headers, neither of which is the problem.
 */
const ALIAS_ITSELF = 'analysis.alias.';

/**
 * A finding as a reader is told it, in the words the screen uses — never a
 * key the program addresses things by. The kernel names what it has: an
 * alias, a field's path, a function or a unit as Wow spells it. Only the
 * catalogue knows what those are called, so they are said here:
 *
 * - a dimension or a metric (`alias`, `metric`) as its result column is
 *   headed (`groupReference`, `metricReference` — the words the table
 *   header, the sort and 「只保留」 use). 「漏斗要一个可累加的指标，avg 不
 *   是」 named the program's key for 「金额的平均」, which the reader never
 *   typed and cannot find on screen;
 * - a field (`field`, `path`) by its label, as the tray's pickers list it;
 * - a summary (`fn`) as the metric's summary select says it, 「平均」 for
 *   `AVG`, and the earliest of a moment 「最早」;
 * - a time unit (`unit`) as the dimension's granularity reads, 「按月」, and
 *   a calendar part (`part`) as its cycle reads, 「按星期」;
 * - a dimension type (`type` of `analysis.group.unsupported`) as the
 *   dimension card offers it, 「按数值区间」;
 * - a condition's operator (`operator`) as its operator select says it.
 *
 * `chart.as-table` is said with the chart and its reason, the tile's own
 * words in the picker: 「柱状图画不了这个结果（最多两个维度），先以表格显示」.
 *
 * Anything the draft does not have is left as it is — it has no name on
 * screen, and the finding is usually that it is missing. Findings of every
 * other family pass through untouched.
 */
export function analysisIssueNamer(
  analysis: AnalysisNaming,
  messages: MessageFormatters,
): (found: Issue) => Issue {
  const column = (alias: string): string | undefined => {
    const group = analysis.groups.find(entry => entry.alias === alias);
    if (group) return groupReference(analysis, group, messages);
    const metric = analysis.metrics.find(entry => entry.alias === alias);
    return metric && metricReference(analysis, metric, messages);
  };
  const field = (path: string): string | undefined =>
    analysis.fields.find(entry => entry.field === path)?.label ??
    analysis.conditionFields?.find(entry => entry.name === path)?.label ??
    (analysis.expandable?.path === path
      ? analysis.expandable.label
      : undefined);
  const labelled = (key: string, raw: string): string | undefined => {
    const said = messages.label(key as never, undefined, raw);
    return said === raw ? undefined : said;
  };
  return found => {
    if (!found.params || !NAMED_FAMILIES.some(f => found.code.startsWith(f)))
      return found;
    if (found.code === 'chart.as-table') return asTable(found, messages);
    const params = { ...found.params };
    let named = false;
    const say = (key: string, said: string | undefined) => {
      if (said === undefined) return;
      params[key] = said;
      named = true;
    };
    const text = (key: string): string | undefined => {
      const value = params[key];
      return typeof value === 'string' ? value : undefined;
    };
    if (
      !found.code.startsWith('filter.') &&
      !found.code.startsWith(ALIAS_ITSELF)
    )
      for (const key of COLUMN_PARAMS) {
        const alias = text(key);
        if (alias !== undefined) say(key, column(alias));
      }
    for (const key of FIELD_PARAMS) {
      const path = text(key);
      if (path !== undefined) say(key, field(path));
    }
    const operator = text('operator');
    if (operator !== undefined)
      say('operator', labelled(`label.operator.${operator}`, operator));
    if (found.code.startsWith('analysis.')) {
      const fn = text('fn');
      const cell =
        typeof found.params.field === 'string'
          ? analysis.fields.find(entry => entry.field === found.params?.field)
              ?.cell
          : undefined;
      if (fn !== undefined)
        say('fn', labelled(summaryFunctionKey(fn as never, cell), fn));
      const unit = text('unit');
      if (unit !== undefined)
        say('unit', labelled(`label.date-unit.${unit}`, unit));
      const part = text('part');
      if (part !== undefined)
        say('part', labelled(`label.date-part.${part}`, part));
      const type = text('type');
      if (found.code === 'analysis.group.unsupported' && type !== undefined)
        say('type', labelled(`label.group.type.${type}`, type));
    }
    return named ? { ...found, params } : found;
  };
}

/** `chart.as-table` with the chart's name and why it cannot draw. */
function asTable(found: Issue, messages: MessageFormatters): Issue {
  const type = found.params?.type;
  const reason = found.params?.reason;
  if (
    typeof type !== 'string' ||
    !CHART_TYPES.includes(type as ChartType) ||
    typeof reason !== 'string' ||
    !reason.startsWith('chart.fit.')
  )
    return found;
  return {
    ...found,
    code: 'label.analysis.as-table',
    params: {
      type: messages.label(`label.chart.type.${type as ChartType}`),
      reason: messages.label(reason as ChartUnfit),
    },
  };
}
