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

/**
 * Charts are stored per family: the sub-object of the current `type` is
 * required and the others are kept, so switching families loses nothing.
 * Every reference is a group or metric alias; no charting library types.
 */
export interface ChartSpec {
  type: ChartType;
  /** Shared by bar, line, area and combo. */
  cartesian?: CartesianSpec;
  pie?: PieSpec;
  heatmap?: HeatmapSpec;
  scatter?: ScatterSpec;
  funnel?: FunnelSpec;
  metric?: MetricCardSpec;
  legend?: 'auto' | 'top' | 'bottom' | 'right' | 'none';
  /**
   * Whether the values are written on the marks. Left out, the mark
   * decides (`valueLabelsOn`): a bar writes them — a combo's bars too — a
   * line, an area and the other families do not; `true` writes them on
   * every mark, and `false` is a choice and stands.
   */
  labels?: boolean;
  /**
   * The colours pinned by hand; the theme fills in the rest from its palette.
   *
   * A key is the series or slice as the kernel labels it: the category value
   * of a split or a pie — a number or a boolean as text, `null` as the empty
   * string — or, where nothing is split, the metric alias. Internal keys are
   * never addressable, so a numeric category is named `"1"`, not `1`; nor is
   * the text a chart shows, so an enum is named by its code rather than its
   * label, and a pinned colour holds in every language.
   */
  colors?: Record<string, string>;
}

export type ChartType =
  | 'bar'
  | 'line'
  | 'area'
  | 'combo'
  | 'pie'
  | 'heatmap'
  | 'scatter'
  | 'funnel'
  | 'metric';

export const CHART_TYPES: readonly ChartType[] = [
  'bar',
  'line',
  'area',
  'combo',
  'pie',
  'heatmap',
  'scatter',
  'funnel',
  'metric',
];

/**
 * The families a chart type belongs to, each the name of the sub-object of
 * `ChartSpec` its types share: four cartesian types draw one kind of spec,
 * every other type is a family of its own.
 */
export type ChartFamily = Extract<
  keyof ChartSpec,
  'cartesian' | 'pie' | 'heatmap' | 'scatter' | 'funnel' | 'metric'
>;

/**
 * How many categorical colours the theme holds: `--chart-1` to `--chart-8`
 * in `styles.css`, the same eight hues in both modes, in a fixed order.
 *
 * It lives here rather than beside the palette in `/ui` because the kernel
 * shapes to it: a slot handed out twice is two categories wearing one
 * colour, which a legend cannot tell apart, so a pie folds its tail into
 * "Other" before a ninth slice would need a colour (`shapeChart`). A number
 * the projection and the palette both read has to sit where both may import
 * it, and the theme is held to the same count by a test that reads the
 * stylesheet. A host can restyle a slot through `--fve-chart-N`; it cannot
 * add one, so the count is the engine's and not the host's.
 */
export const CHART_COLOR_SLOTS = 8;

/** Which sub-object each chart type requires. */
export const CHART_FAMILY: Readonly<Record<ChartType, ChartFamily>> =
  Object.freeze({
    bar: 'cartesian',
    line: 'cartesian',
    area: 'cartesian',
    combo: 'cartesian',
    pie: 'pie',
    heatmap: 'heatmap',
    scatter: 'scatter',
    funnel: 'funnel',
    metric: 'metric',
  });

export interface CartesianSeries {
  /** Metric alias. */
  metric: string;
  /** Required per series when the chart type is `combo`. */
  type?: 'bar' | 'line' | 'area';
  axis?: 'left' | 'right';
  /**
   * Series sharing a stack name are stacked — bars and areas; a line draws
   * its own values whatever it says (`stacks`).
   */
  stack?: string;
  smooth?: boolean;
}

export interface CartesianSpec {
  /** Group alias. */
  x: string;
  /** Second group alias, pivoting one metric into a series per value. */
  splitBy?: string;
  series: CartesianSeries[];
  orientation?: 'vertical' | 'horizontal';
  yAxis?: { left?: AxisSpec; right?: AxisSpec };
  referenceLines?: ReferenceLine[];
}

export interface AxisSpec {
  label?: string;
  min?: number;
  max?: number;
  format?: ValueFormat;
}

export interface ReferenceLine {
  axis: 'left' | 'right';
  value: number;
  label?: string;
}

export type ValueFormat = 'auto' | 'percent' | 'compact';

export interface PieSpec {
  /** Group alias. */
  category: string;
  /** Metric alias. */
  value: string;
  donut?: boolean;
  /**
   * Merges the remainder into "other"; additive metrics only. Left out, an
   * additive pie still folds at `CHART_COLOR_SLOTS`, and a larger number is
   * read as that many — past it two slices would share a colour.
   */
  maxSlices?: number;
}

export interface HeatmapSpec {
  /** Group alias. */
  x: string;
  /** Another group alias. */
  y: string;
  /** Metric alias. */
  value: string;
  scale?: 'linear' | 'log';
}

export interface ScatterSpec {
  /** Group alias; one point per value. */
  category: string;
  /** Metric aliases. */
  x: string;
  y: string;
  size?: string;
}

/**
 * Stages come either from one filtered metric each, or from the values of a
 * single group with an explicit business order.
 */
export interface FunnelSpec {
  stages: FunnelStages;
  conversion?: 'previous' | 'first' | 'none';
  orientation?: 'vertical' | 'horizontal';
}

export type FunnelStages =
  | { from: 'metrics'; items: { metric: string; label?: string }[] }
  | {
      from: 'group';
      category: string;
      value: string;
      /** Business order of the stages, taken from the group values. */
      order: string[];
      /**
       * Accumulate into "reached at least this stage" — each stage plus
       * every later one, which is right only when each object sits in
       * exactly one stage. Defaults to false: each stage is its own rows'
       * number, the one the table shows.
       */
      cumulative?: boolean;
    };

export interface MetricCardSpec {
  /** Metric alias. */
  metric: string;
  compare?: { metric: string; mode: 'delta' | 'percent' };
  /** Rendered as progress towards this value. */
  target?: number;
  /** Sparkline; requires exactly one DATE_HISTOGRAM group with this alias. */
  trend?: { x: string };
  format?: ValueFormat;
}
