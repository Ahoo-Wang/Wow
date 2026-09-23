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
  Pick<AnalysisEditorController, 'groups'>;

/** The params a chart finding names a dimension or a metric by. */
const NAMED_PARAMS = ['alias', 'metric'] as const;

/**
 * A chart finding as a reader is told it: every dimension and metric it
 * names said as the result's column is headed (`groupReference`,
 * `metricReference` — the words the table header, the sort and 「只保留」
 * use), never by its alias. The kernel names them by alias because an alias
 * is all it has; 「漏斗要一个可累加的指标，avg 不是」 named the program's key
 * for 「金额的平均」, which the reader never typed and cannot find on screen.
 *
 * `chart.as-table` is said with the chart and its reason, the tile's own
 * words in the picker: 「柱状图画不了这个结果（最多两个维度），先以表格显示」.
 *
 * An alias the draft does not have is left as it is — it has no header —
 * and the messages of the two findings that carry one (`chart.group.unknown`,
 * `chart.metric.unknown`) do not print it. Findings of every other family
 * pass through untouched.
 */
export function chartIssueNamer(
  analysis: AnalysisNaming,
  messages: MessageFormatters,
): (found: Issue) => Issue {
  const title = (alias: string): string | undefined => {
    const group = analysis.groups.find(entry => entry.alias === alias);
    if (group) return groupReference(analysis, group, messages);
    const metric = analysis.metrics.find(entry => entry.alias === alias);
    return metric && metricReference(analysis, metric, messages);
  };
  return found => {
    if (!found.code.startsWith('chart.') || !found.params) return found;
    if (found.code === 'chart.as-table') return asTable(found, messages);
    const params = { ...found.params };
    let named = false;
    for (const key of NAMED_PARAMS) {
      const value = params[key];
      const said = typeof value === 'string' ? title(value) : undefined;
      if (said === undefined) continue;
      params[key] = said;
      named = true;
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
