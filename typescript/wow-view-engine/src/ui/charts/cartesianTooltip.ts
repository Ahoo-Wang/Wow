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

import { bucketChange, type BucketChange } from '../../analysis/index.js';
import { formatChange, formatShare } from './axis.js';
import type { CartesianPlan, DrawnSeries } from './cartesianPlan.js';
import type { ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml, type TooltipRow } from './tooltip.js';

/**
 * A cartesian chart's tooltip: every series drawn at the category under the
 * pointer, its number whole and in its column's format, a filled-in 0 said
 * to be one, a 100% stack's share beside the value — and on a time axis how
 * each number moved from the bucket before (「+12.3%」), with the footnote
 * saying what it is measured against (no second query, only the rows already
 * drawn, `bucketChange` says where there is a change to tell).
 * A derived line's number follows the series', named as computed (D33
 * batch B).
 */
export function cartesianTooltip(
  plan: CartesianPlan,
  theme: ChartTheme,
): object {
  const { data, context, series, names } = plan;
  const { spec, label, locale } = context;
  const cartesian = spec?.cartesian;
  const hasBars = series.some(entry => entry.kind === 'bar');

  const rowAt = (entry: DrawnSeries, index: number): TooltipRow => {
    const point = data.points[index];
    const read = label(entry.metric, point?.values[entry.key]);
    // A filled-in 0 says it is one: no records there, not a count of none
    // that came back (D23, Q14).
    const value =
      plan.filledAt(entry, index) && context.filled
        ? context.filled(cartesian?.x, read)
        : read;
    // Stacked to 100%, the mark is a share and the tooltip says both: what
    // the part is, and what part of its stack.
    const share = plan.asShares(entry) ? plan.shareAt(entry, index) : undefined;
    const change = context.against
      ? bucketChange(data, entry.key, index)
      : undefined;
    return {
      color: theme.resolve(entry.color),
      name: entry.name,
      value:
        share === undefined
          ? value
          : `${value} · ${formatShare(share, locale)}`,
      ...(change
        ? { note: changeText(change, delta => label(entry.metric, delta)) }
        : {}),
    };
  };

  return {
    ...tooltipFrame(theme),
    trigger: 'axis',
    // A band behind the bars of one category; a rule through the points of
    // a line, which a band would blur. Behind them, not over them: the
    // library draws its pointer above the series, and a half-grey band laid
    // over the one bar being read paled it and its number — the hovered bar
    // read as the disabled one (2026-09-23 audit).
    axisPointer: hasBars
      ? {
          type: 'shadow',
          z: 0,
          shadowStyle: { color: theme.grid.color, opacity: 0.5 },
        }
      : {
          type: 'line',
          lineStyle: { color: theme.muted, width: theme.grid.width },
        },
    formatter: (params: { dataIndex: number }[] | { dataIndex: number }) => {
      const first = Array.isArray(params) ? params[0] : params;
      const index = first?.dataIndex ?? -1;
      const point = data.points[index];
      if (!point) return '';
      const measured = series
        .filter(entry => typeof point.values[entry.key] === 'number')
        .map(entry => rowAt(entry, index));
      const rows: TooltipRow[] = [
        ...measured,
        // A derived line's number at this bucket, under its name that says
        // it was computed, in the ink its dashes are drawn in.
        ...plan.derived.flatMap(line => {
          const value = line.values[index];
          return typeof value === 'number'
            ? [
                {
                  color: theme.foreground,
                  name: line.name,
                  // A running share is a share, whatever the metric is in.
                  value:
                    line.kind === 'cumulative-share'
                      ? formatShare(value, locale)
                      : label(line.metric, value),
                  ...earlierChange(line, value, point.values[line.metric]),
                },
              ]
            : [];
        }),
      ];
      const name = names[index] ?? '';
      const ongoing =
        data.unfinished !== undefined && data.unfinished.at === point.x;
      return tooltipHtml(
        ongoing ? (context.words?.ongoingPeriod(name) ?? name) : name,
        rows,
        // The footnote names what a series' change is against; an earlier
        // period's line names its own (D88).
        measured.some(row => row.note !== undefined)
          ? context.against
          : undefined,
      );
    },
  };

  /**
   * An earlier period's line (D88) says how the metric moved from it, in
   * words that name the period: 「较去年同期 +12.3%」.
   */
  function earlierChange(
    line: CartesianPlan['derived'][number],
    was: number,
    value: number | null | undefined,
  ): Pick<TooltipRow, 'note'> {
    if (
      (line.kind !== 'previous' && line.kind !== 'previous-year') ||
      typeof value !== 'number' ||
      !context.words?.changeFrom
    )
      return {};
    const delta = value - was;
    const change = { delta, ratio: was === 0 ? null : delta / Math.abs(was) };
    return {
      note: context.words.changeFrom(
        line.kind,
        changeText(change, amount => label(line.metric, amount)),
      ),
    };
  }

  /** A change as the tooltip writes it: the share, or — from 0 — the amount. */
  function changeText(
    change: BucketChange,
    amount: (delta: number) => string,
  ): string {
    return change.ratio === null
      ? `${change.delta > 0 ? '+' : ''}${amount(change.delta)}`
      : formatChange(change.ratio, locale);
  }
}
