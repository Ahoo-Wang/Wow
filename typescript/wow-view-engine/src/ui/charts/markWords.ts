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

import type {
  CartesianData,
  CartesianGap,
  DerivedLine,
} from '../../analysis/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import type { MarkWords } from './cartesianPlan.js';

/**
 * A derived line's name as the legend, the tooltip and the reading table
 * say it — 「7 期移动平均（算出的）」 — with the series it follows when the
 * chart draws more than one: 「累计 · 金额的总和（算出的）」.
 */
export function derivedName(
  messages: MessageFormatters,
  line: Pick<DerivedLine, 'kind' | 'window'>,
  series?: string,
): string {
  // A moving average not drawn has no window to name: it is 「移动平均」.
  const kind =
    line.kind === 'moving-average' && line.window === undefined
      ? messages.label('label.chart.derived.add.moving-average')
      : messages.label(`label.chart.derived.${line.kind}`, {
          window: line.window ?? '',
        });
  const name =
    series === undefined
      ? kind
      : messages.label('label.chart.derived.of', { name: kind, series });
  return messages.label('label.chart.derived.computed', { name });
}

/** The words the reference, derived and extreme marks are written with. */
export function markWords(messages: MessageFormatters): MarkWords {
  return {
    derived: (line, series) => derivedName(messages, line, series),
    statistic: (of, value) =>
      messages.label('label.chart.statistic.caption', {
        statistic: messages.label(`label.chart.statistic.${of}`),
        value,
      }),
    high: messages.label('label.chart.extreme.high'),
    low: messages.label('label.chart.extreme.low'),
  };
}

/**
 * Why a line the spec asked for is not drawn, one sentence each (Q53):
 * 「趋势（算出的）没有画：结果只显示了前 100 组，算出的线会不完整。」
 */
export function gapNotes(
  messages: MessageFormatters,
  data: CartesianData,
  column: (alias: string | undefined) => string | undefined,
): string[] {
  const several = data.series.length > 1;
  return (data.gaps ?? []).map(gap => {
    const series = several ? (column(gap.metric) ?? gap.metric) : undefined;
    const what =
      gap.kind === 'average' || gap.kind === 'median'
        ? messages.label('label.chart.statistic.caption', {
            statistic: messages.label(`label.chart.statistic.${gap.kind}`),
            value: column(gap.metric) ?? gap.metric,
          })
        : derivedName(messages, { kind: gap.kind }, series);
    return messages.label('label.chart.gap.note', {
      what,
      reason: gapReason(messages, gap),
    });
  });
}

/**
 * One gap's reason, as the options page greys a choice with it too; a
 * result cut short says at how many groups.
 */
export function gapReason(
  messages: MessageFormatters,
  gap: Pick<CartesianGap, 'kind' | 'gap'> & { limit?: number },
): string {
  const statistic = gap.kind === 'average' || gap.kind === 'median';
  return messages.label(
    statistic && gap.gap === 'split'
      ? 'label.chart.gap.statistic-split'
      : `label.chart.gap.${gap.gap}`,
    { limit: gap.limit ?? '' },
  );
}
