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

import type { EChartsCoreOption } from 'echarts/core';

/**
 * How faint a mark that is not the group pressed is drawn: a dashboard panel
 * that set the board's filter keeps every group and marks the one pressed
 * (D22 I). Faded rather than hidden — the others are still the panel's
 * answer — and by opacity alone, so the palette and the marks' own colours
 * stay the theme's.
 */
export const FADED_OPACITY = 0.5;

/**
 * Which of a series' data are the group pressed: `lit(series, datum)` is
 * asked for each drawn datum, `series` the index the family's option names
 * its marks by (`s{index}`, or the one series of a pie, a scatter or a
 * heatmap).
 */
export type Lit = (series: number, datum: number) => boolean;

/**
 * The option with every datum that is not lit faded. Only the family's own
 * marks are touched — a series whose id is `s{n}` (the cartesian marks), or
 * the one series of a pie, a scatter or a heatmap — never a stack's totals or a reference line, which
 * stand for no group. Without `lit`, or with no datum it lights, the option
 * is handed back as it is, so a chart nothing was pressed on draws exactly
 * what it drew before.
 */
export function faded(
  option: EChartsCoreOption,
  lit: Lit | undefined,
): EChartsCoreOption {
  const series = option.series;
  if (!lit || !Array.isArray(series)) return option;
  const marks = series.flatMap((entry: Record<string, unknown>, position) => {
    const index = markIndex(entry, position);
    return index === null || !Array.isArray(entry.data)
      ? []
      : [{ index, count: entry.data.length }];
  });
  // Nothing pressed on this chart — the value came from elsewhere, or its
  // group is not among the ones drawn — marks nothing, and fades nothing.
  const any = marks.some(({ index, count }) =>
    Array.from({ length: count }, (_, at) => at).some(at => lit(index, at)),
  );
  if (!any) return option;
  return {
    ...option,
    series: series.map((entry: Record<string, unknown>, position) => {
      const index = markIndex(entry, position);
      if (index === null || !Array.isArray(entry.data)) return entry;
      return {
        ...entry,
        data: entry.data.map((datum: unknown, at: number) =>
          lit(index, at) ? datum : fade(datum),
        ),
      };
    }),
  };
}

/** The families drawn as one series whose every datum is a group. */
const ONE_SERIES = new Set(['pie', 'scatter', 'heatmap']);

/** The index a series stands for among the marks, or `null` for none. */
function markIndex(entry: Record<string, unknown>, position: number) {
  if (typeof entry.type === 'string' && ONE_SERIES.has(entry.type))
    return position;
  const id = typeof entry.id === 'string' ? /^s(\d+)$/.exec(entry.id) : null;
  return id ? Number(id[1]) : null;
}

function fade(datum: unknown): unknown {
  if (typeof datum === 'object' && datum !== null && 'value' in datum) {
    const style = (datum as { itemStyle?: object }).itemStyle ?? {};
    return {
      ...datum,
      itemStyle: { ...style, opacity: FADED_OPACITY },
    };
  }
  return { value: datum, itemStyle: { opacity: FADED_OPACITY } };
}
