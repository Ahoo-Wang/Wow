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
  modeHsl,
  modeLab,
  modeLch,
  modeOklab,
  modeOklch,
  modeP3,
  modeRgb,
  parse,
  // Not a React hook, whatever the name looks like: it registers a colour
  // space with the parser. Aliased so the hook rules read it as what it is.
  useMode as registerMode,
} from 'culori/fn';
import {
  CHART_FAMILY,
  type AnalysisViewConfig,
  type ChartSpec,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type { ChartContext } from './chartRefs.js';
import { FAMILY_RULES } from './familyRules.js';

/**
 * The colour spaces `parse` is taught to read. `culori/fn` is the
 * tree-shakable entry: it ships no mode registered, and a mode's syntaxes
 * only become parseable once it is passed to `useMode`. These seven are
 * every syntax a stylesheet writes a colour in.
 */
registerMode(modeRgb);
registerMode(modeHsl);
registerMode(modeLab);
registerMode(modeLch);
registerMode(modeOklab);
registerMode(modeOklch);
registerMode(modeP3);

/** A theme slot, which is how the palette itself is named. */
const VARIABLE_COLOR = /^var\(--[\w-]+\)$/;

/**
 * Whether a saved colour is one the renderer may pass on. A chart colour
 * reaches the page as a colour — the drawing is handed it made concrete, the
 * legend's dot wears it as a style — so a config from a store gets to name a
 * colour and nothing else. The renderer applies the same predicate and falls
 * back to the palette, so an unvalidated spec draws in a slot rather than in
 * whatever it said.
 *
 * Two shapes pass. A `var(--slot)` reference, which is how this package's own
 * palette is written and which no parser resolves; and anything `culori`
 * parses, which decides validity rather than a character class: a named
 * colour (case-insensitively, as CSS reads them), `#rgb` through `#rrggbbaa`,
 * `rgb()`/`rgba()`, `hsl()`/`hsla()`, `lab()`, `lch()`, `oklab()`, `oklch()`
 * and `color()` over the spaces the registered modes name — `srgb`,
 * `display-p3` and the rest. A spelling outside that set is refused even
 * where it would have painted, `currentcolor` among them; the hand-written
 * regexes this replaced did the opposite, taking `banana`, `rgb(foo)` and
 * `color(nope)` for colours and leaving the series they named unpainted.
 */
export function isChartColor(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const text = value.trim();
  return VARIABLE_COLOR.test(text) || parse(text) !== undefined;
}

/**
 * One finding per colour the theme would refuse to paint with, and one for a
 * `colors` that is no map of them. Only `undefined` means "none pinned": a
 * string, a number, `null` or an array is a config that lost its shape, and
 * reading it as "not provided" hid that.
 */
function colors(chart: ChartSpec, path: IssuePath): Issue[] {
  const colors_ = chart.colors;
  if (colors_ === undefined) return [];
  if (typeof colors_ !== 'object' || colors_ === null || Array.isArray(colors_))
    return [issue('chart.colors.malformed', [...path, 'colors'])];
  return Object.entries(colors_)
    .filter(([, value]) => !isChartColor(value))
    .map(([key]) => issue('chart.colors.invalid', [...path, 'colors', key]));
}

/**
 * Charts reference aliases, so their rules are about what the query actually
 * produces. The strictest one is that a chart must consume every group: an
 * unconsumed dimension leaves several rows per coordinate, and AVG, percentile
 * and DISTINCT_COUNT cannot be re-aggregated over them in the projection.
 *
 * `moments` are the metrics whose value is a moment (`momentMetrics`): a
 * slot a mark measures refuses one (`chart.metric.moment`), and so does a
 * card's comparison, target and number format over one. `validateAnalysis`
 * passes them from the scope; left out, nothing is a moment.
 *
 * These are the rules of a chart that is drawn: `validateAnalysis` asks
 * them only while the layout is the chart and its type can draw the shape
 * (D20; `chartUnfit`). A finding names a group or a metric by its alias, in
 * `alias` or `metric`, because an alias is all this layer has; a surface
 * says it as the column is headed (`analysisIssueNamer` in `/ui`).
 */
export function validateChart(
  config: AnalysisViewConfig,
  moments: ReadonlySet<string> = new Set(),
): Issue[] {
  const path: IssuePath = ['chart'];
  const chart = config.chart;
  // `validateAnalysis` refuses a missing chart before reaching here, but this
  // is exported on its own and a config from a store may have none.
  if (typeof chart !== 'object' || chart === null)
    return [issue('analysis.config.malformed', path)];
  const family = CHART_FAMILY[chart.type];
  if (!family) return [issue('chart.type.unknown', [...path, 'type'])];
  if (chart[family] === undefined)
    return [issue('chart.family.missing', path, { type: chart.type, family })];

  const context: ChartContext = {
    groups: new Set(config.groups.map(group => group.alias)),
    metrics: new Map(config.metrics.map(metric => [metric.alias, metric])),
    moments,
    chart,
    path,
  };

  return [
    ...colors(chart, path),
    ...FAMILY_RULES[family].validate(context, config),
  ];
}
