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
  isPercentStacked,
  seriesMark,
  valueLabelsOn,
  type CartesianData,
  type DerivedLine,
  type PlacedLine,
} from '../../analysis/index.js';
import type { CartesianSeries, ChartSpec } from '../../model/index.js';
import { allWhole, axisId, categoryTick, formatShare } from './axis.js';
import { LARGE_FROM } from './cartesianZoom.js';
import type { ColumnTitle, FilledNote, ValueLabel } from './family.js';
import { measureText } from './measure.js';
import { colorOf } from './palette.js';
import { sharedScales, type NiceScale } from './scale.js';

/** What a cartesian drawing reads besides its data. */
export interface CartesianContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  /** Whether the marks grow into place (`useChartMotion`). */
  animate: boolean;
  /** Whether a press on a mark opens the follow-up menu. */
  pickable: boolean;
  /**
   * The category ticks written short, one per point, where the axis is a
   * date bucket (`shortDateTicks`); a point with none is written as its
   * column reads it. The tooltip still names the whole bucket.
   */
  ticks?: readonly (string | undefined)[];
  /**
   * The short ticks of some of the axis's buckets, as `ticks` writes all of
   * them: where a thinned axis names every few, the year is written where
   * it changes among the ones named, not among all (`datedFit`).
   */
  tickFor?: (values: readonly unknown[]) => (string | undefined)[] | undefined;
  /**
   * What joins two column titles into one axis title, 「、」 or ", " — an
   * axis of a two-axis chart that measures several metrics names them all.
   */
  join?: string;
  /** A filled-in value as the tooltip says it (`useFilledNote`). */
  filled?: FilledNote;
  /**
   * The series the reader switched off in the legend, by key. They keep
   * their colour and their place in the legend, and are drawn nowhere else:
   * not as marks, not in a stack's shares or totals, not on a scale, not in
   * the tooltip — the axis is fitted to what is left (D33 batch A).
   */
  hidden?: ReadonlySet<string>;
  /**
   * What a change in the tooltip is measured against, 「较上一期」: given,
   * a time axis's tooltip says how each number moved from the bucket
   * before (`bucketChange`); left out, it says only the numbers.
   */
  against?: string;
  /**
   * Whether a long axis also zooms by gesture — a pinch, Ctrl + wheel — as
   * well as by its slider (`zoomOption`): a workbench's chart, not a
   * dashboard panel's or a read-only embedding's.
   */
  zoomGestures?: boolean;
  /**
   * Whether a drag along the axis brushes a stretch of it for the follow-up
   * menu (D33 Q52, `brushOption`): a time axis whose marks are pressable.
   */
  brushes?: boolean;
  /**
   * What the marks drawn over the chart say (D33 batch B): a derived line's
   * name, a statistic line's caption, the words by the highest and lowest
   * points. Left out, those say nothing but their numbers.
   */
  words?: MarkWords;
}

/** The words the reference, derived and extreme marks are written with. */
export interface MarkWords {
  /** A derived line's name: 「7 期移动平均（算出的）」, and whose. */
  derived(line: DerivedLine, series: string | undefined): string;
  /** A statistic line's caption, its number already written. */
  statistic(of: 'average' | 'median', value: string): string;
  high: string;
  low: string;
}

/**
 * `data` without the series `hidden` names — what the marks, the scales and
 * the reading table say once the legend switched them off. The same object
 * when nothing is hidden.
 */
export function withoutHidden(
  data: CartesianData,
  hidden: ReadonlySet<string> | undefined,
): CartesianData {
  if (!hidden || hidden.size === 0) return data;
  const series = data.series.filter(series => !hidden.has(series.key));
  // A derived line goes with the series it is computed from, and on its own.
  const derived = data.derived?.filter(
    line =>
      !hidden.has(line.key) && series.some(entry => entry.key === line.metric),
  );
  return {
    ...data,
    series,
    ...(data.derived ? { derived } : {}),
  };
}

/** One series as drawn: the kernel's, and what the spec says about it. */
export interface DrawnSeries {
  key: string;
  metric: string;
  /** Its name in the legend and the tooltip. */
  name: string;
  /** A CSS colour: a theme slot or the one the spec pinned. */
  color: string;
  side: 'left' | 'right';
  configured?: CartesianSeries;
  /** The mark: the chart's own, or — in a combo — the one the spec names. */
  kind: 'bar' | 'line' | 'area';
}

type Side = 'left' | 'right';

/** The series as the legend, the tooltip and the marks all name them. */
export function drawnSeries(
  data: CartesianData,
  { spec, label, column }: Pick<CartesianContext, 'spec' | 'label' | 'column'>,
): DrawnSeries[] {
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  return data.series.map((series, index) => {
    const configured = bySeries.get(series.metric);
    return {
      key: series.key,
      metric: series.metric,
      // A pivoted series shows its split value as that field shows it; an
      // unpivoted one is its column's title — 「金额的总和」, never the
      // alias, which names the query.
      name:
        series.value === undefined
          ? (column(series.metric) ?? series.label)
          : label(spec?.cartesian?.splitBy, series.value),
      // The spec names a pivoted series by its split value as the kernel
      // labels it, and an unpivoted one by its metric alias.
      color: colorOf(spec, index, series.label, series.metric),
      side: axisId(configured?.axis),
      configured,
      kind: seriesMark(data.chart, configured),
    };
  });
}

/**
 * The widest a category name is drawn upright before the bars lie on their
 * side, in pixels at the page's 12px: some eight CJK characters or a dozen
 * Latin ones. Past it the names slant, and a slanted name of a ranking is
 * read with the head tilted — Metabase and every BI tool turn such a chart
 * into a row chart instead (2026-09-23 audit).
 */
export const LONG_NAME = 96;

/**
 * Whether a bar chart lies on its side: as the analyst said, and — where
 * they said nothing — when a category's name is too long to stand under
 * its bar. A time axis never does: dates read left to right, and they are
 * short. Lines, areas and combos stay upright: a line lying on its side is
 * no line chart anyone reads.
 */
export function drawsHorizontal(
  spec: ChartSpec | undefined,
  names: readonly string[],
  dated: boolean,
  measure: (text: string) => number = text => measureText(text),
): boolean {
  const orientation = spec?.cartesian?.orientation;
  if (orientation !== undefined) return orientation === 'horizontal';
  return (
    spec?.type === 'bar' &&
    !dated &&
    names.some(name => measure(categoryTick(name)) > LONG_NAME)
  );
}

/**
 * The stacks of a cartesian chart and the shares they are drawn as.
 *
 * A stack as the library draws it is the spec's name on one axis. Series
 * measured against two axes are two scales, and stacking a count on top
 * of an amount drew the count at the amount's height; each axis stacks
 * its own. A line never stacks: its point would stand at the running sum
 * while its label says its own value (「¥640」 at ¥1920), with no band
 * under it to show the part it adds — it draws its own values.
 *
 * Stacked to 100% (`percentStack`): a bar or an area chart only — a
 * combo's line would stand on a scale of shares — whose stacks are then
 * drawn as each part's share of its stack at that category, so every
 * stack reaches the top. The reading table says the same shares.
 */
export function stackPlan(
  data: CartesianData,
  spec: ChartSpec | undefined,
): {
  stackOf(
    series: Pick<DrawnSeries, 'side' | 'configured' | 'kind'>,
  ): string | undefined;
  /** Whether the stacks are drawn as shares. */
  percent: boolean;
  /** A series' share of its stack at one point, when it is drawn as one. */
  shareAt(key: string, index: number): number | undefined;
} {
  const cartesian = spec?.cartesian;
  const bySeries = new Map(
    (cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  const stackOf = (
    series: Pick<DrawnSeries, 'side' | 'configured' | 'kind'>,
  ) =>
    series.configured?.stack === undefined || series.kind === 'line'
      ? undefined
      : `${series.side}:${series.configured.stack}`;
  const percent =
    cartesian !== undefined &&
    (data.chart === 'bar' || data.chart === 'area') &&
    isPercentStacked(cartesian, data.chart);
  const stackByKey = new Map(
    data.series.map(series => {
      const configured = bySeries.get(series.metric);
      return [
        series.key,
        stackOf({
          side: axisId(configured?.axis),
          configured,
          kind: seriesMark(data.chart, configured),
        }),
      ];
    }),
  );
  const wholes = percent
    ? data.points.map(point => {
        const sums = new Map<string, number>();
        for (const series of data.series) {
          const stack = stackByKey.get(series.key);
          const value = point.values[series.key];
          if (stack !== undefined && typeof value === 'number')
            sums.set(stack, (sums.get(stack) ?? 0) + Math.abs(value));
        }
        return sums;
      })
    : undefined;
  return {
    stackOf,
    percent,
    shareAt(key, index) {
      const stack = stackByKey.get(key);
      const value = data.points[index]?.values[key];
      const whole =
        stack === undefined ? undefined : wholes?.[index]?.get(stack);
      return typeof value === 'number' && whole !== undefined && whole > 0
        ? value / whole
        : undefined;
    },
  };
}

/**
 * Everything a cartesian chart decides before it knows its theme or its
 * size: which way it lies, its series and their stacks, what each mark
 * draws, the scale of each value axis, and the text each label writes.
 * The option (`cartesianOption`) and the size's adjustments
 * (`cartesianFit`) both read it, so the two cannot disagree about, say,
 * which labels there are.
 */
export interface CartesianPlan {
  data: CartesianData;
  context: CartesianContext;
  horizontal: boolean;
  /** The category names, as their column reads them. */
  names: string[];
  /**
   * A category's tick, by its name — the library hands a tick its name and
   * an index counted from the zoom's first category, so the name is the
   * one to go by: short on a time axis (`ticks`), cut for its axis else.
   */
  tickOf(name: string): string;
  /** The series drawn: every one the legend has not switched off. */
  series: DrawnSeries[];
  /**
   * Every series, switched off or not, in the colour each keeps either way:
   * what the legend lists.
   */
  legend: DrawnSeries[];
  /** The value axes there are: the left, and the right when anything is on it. */
  sides: Side[];
  stackOf(entry: DrawnSeries): string | undefined;
  /** The members of each stack, by the stack's name. */
  stacks: Map<string, DrawnSeries[]>;
  /** Whether a series stands in a stack of more than one. */
  stacked(entry: DrawnSeries): boolean;
  /** Whether a series is drawn as its shares of a 100% stack. */
  asShares(entry: DrawnSeries): boolean;
  shareAt(entry: DrawnSeries, index: number): number | undefined;
  /** What a series draws at one point: its value, or its share. */
  drawnAt(entry: DrawnSeries, index: number): number | null;
  /** A drawn number as text: a share as a percentage, a value as its column. */
  drawnText(entry: DrawnSeries, value: number): string;
  /** Whether a series writes its values at all (`valueLabelsOn`). */
  labelled(entry: DrawnSeries): boolean;
  /**
   * Whether the kernel filled a series' value at one point rather than
   * measured it (`CartesianData.points[].filled`): a known 0, still drawn —
   * the line drops to it, the bar stands at nothing — but never labelled,
   * since a screen of 「0」 buried the bars that have a number (D23, Q14).
   */
  filledAt(entry: DrawnSeries, index: number): boolean;
  /**
   * What a stacked bar's segment writes inside itself at one point: its
   * part — and nothing for a part of nothing, or for the one part a stack
   * has, which is its total and is written over it.
   */
  insideText(entry: DrawnSeries, index: number): string;
  /** The bar stacks that write a total over themselves, and each total. */
  totals: { members: DrawnSeries[]; texts: string[] }[];
  /** Every value label written past a mark's end, a total's included. */
  outerTexts: string[];
  /**
   * The value axes' scales, where the chart owns them (`sharedScales`):
   * every axis, unless one carries a bound the analyst set — then the
   * library rounds, and two axes are lined up by its `alignTicks`.
   */
  scales: Partial<Record<Side, NiceScale>>;
  /** The span each value axis runs, owned or as far as the marks reach. */
  span(side: Side): { min: number; max: number };
  /**
   * The highest and the lowest the marks reach on one axis, and — with
   * `withLines` — its reference lines.
   */
  reach(side: Side, withLines: boolean): { high: number; low: number };
  /** Whether an axis steps in whole numbers only. */
  wholeOn(side: Side): boolean;
  /** Whether an axis measures shares: a 100% stack stands on it. */
  sharesOn(side: Side): boolean;
  /** Every value an axis carries: its series' and its reference lines'. */
  valuesOn(side: Side): number[];
  /** The reference lines, where the kernel placed them. */
  lines: PlacedLine[];
  /** The derived lines drawn: every one the legend has not switched off. */
  derived: DrawnDerived[];
  /** Every derived line, switched off or not: what the legend lists. */
  derivedLegend: DrawnDerived[];
  /**
   * Whether a series marks its highest and lowest point: the spec asks, the
   * kernel found two to mark, and it stands on its own — a segment of a
   * stack stands at the stack's height, and past `LARGE_FROM` bars there is
   * no mark to hang one on.
   */
  extremesOf(entry: DrawnSeries): { high: number; low: number } | undefined;
}

/** A derived line as drawn: the kernel's, its name and its axis. */
export interface DrawnDerived extends DerivedLine {
  name: string;
  side: Side;
}

export function cartesianPlan(
  data: CartesianData,
  context: CartesianContext,
): CartesianPlan {
  const { spec, label, ticks } = context;
  const cartesian = spec?.cartesian;
  const names = data.points.map(point => label(cartesian?.x, point.x));
  const horizontal = drawsHorizontal(spec, names, ticks !== undefined);
  // Coloured before any is taken away, so a series keeps its colour when
  // the one before it is switched off.
  const legend = drawnSeries(data, context);
  const hidden = context.hidden;
  const series =
    hidden && hidden.size > 0
      ? legend.filter(entry => !hidden.has(entry.key))
      : legend;
  // The reference lines where the kernel placed them: a statistic at its
  // number. Only a constant is the analyst's number, and only it may decide
  // that an axis steps in whole numbers.
  // Data shaped elsewhere than `shapeChart` carries none: its constant lines
  // are the spec's as written, a statistic having no number to stand at.
  const lines: PlacedLine[] =
    data.references ??
    (cartesian?.referenceLines ?? []).flatMap(line =>
      line.statistic === undefined && typeof line.value === 'number'
        ? [{ ...line, value: line.value }]
        : [],
    );
  const bands = cartesian?.referenceBands ?? [];
  const sideOf = (metric: string) =>
    legend.find(entry => entry.metric === metric)?.side ?? 'left';
  // A running total outgrows the numbers it adds up — ninety days of sales
  // flattened every day's bar to the floor under it — so it takes the other
  // axis, on a scale of its own, where no series stands there. A trend and
  // a moving average keep their series' scale: they are read against it.
  const other = (side: Side): Side => (side === 'left' ? 'right' : 'left');
  const derivedSide = (line: DerivedLine): Side => {
    const own = sideOf(line.metric);
    return line.kind === 'cumulative' &&
      !legend.some(entry => entry.side === other(own))
      ? other(own)
      : own;
  };
  const derivedLegend: DrawnDerived[] = (data.derived ?? []).map(line => ({
    ...line,
    side: derivedSide(line),
    name:
      context.words?.derived(
        line,
        series.length + (hidden?.size ?? 0) > 1
          ? legend.find(entry => entry.key === line.metric)?.name
          : undefined,
      ) ?? line.metric,
  }));
  const derived = derivedLegend.filter(
    line =>
      !hidden?.has(line.key) && series.some(entry => entry.key === line.metric),
  );
  const hasRight =
    series.some(entry => entry.side === 'right') ||
    lines.some(line => axisId(line.axis) === 'right') ||
    derived.some(line => line.side === 'right') ||
    bands.some(band => axisId(band.axis) === 'right');
  const sides: Side[] = hasRight ? ['left', 'right'] : ['left'];
  const {
    stackOf,
    percent,
    shareAt: shareOf,
  } = stackPlan(withoutHidden(data, hidden), spec);
  const stacks = new Map<string, DrawnSeries[]>();
  for (const entry of series) {
    const stack = stackOf(entry);
    if (stack !== undefined)
      stacks.set(stack, [...(stacks.get(stack) ?? []), entry]);
  }
  const stacked = (entry: DrawnSeries) =>
    (stacks.get(stackOf(entry) ?? '')?.length ?? 0) > 1;
  const asShares = (entry: DrawnSeries) =>
    percent && stackOf(entry) !== undefined;
  const shareAt = (entry: DrawnSeries, index: number) =>
    shareOf(entry.key, index);
  const drawnAt = (entry: DrawnSeries, index: number): number | null => {
    const value = data.points[index]?.values[entry.key] ?? null;
    if (!asShares(entry) || value === null) return value;
    return shareAt(entry, index) ?? null;
  };
  const drawnText = (entry: DrawnSeries, value: number) =>
    asShares(entry)
      ? formatShare(value, context.locale)
      : label(entry.metric, value, true);
  // Bars in large mode (`LARGE_FROM`) are one path, and carry no labels.
  const labelled = (entry: DrawnSeries) =>
    valueLabelsOn(spec, entry.kind) &&
    !(entry.kind === 'bar' && data.points.length > LARGE_FROM);
  const filledAt = (entry: DrawnSeries, index: number) =>
    data.points[index]?.filled?.includes(entry.key) === true;
  const sharesOn = (side: Side) =>
    percent &&
    series.some(entry => entry.side === side && stackOf(entry) !== undefined);

  const valuesOn = (side: Side) => [
    ...series
      .filter(entry => entry.side === side)
      .flatMap(entry =>
        data.points.map((_point, index) => drawnAt(entry, index)),
      )
      .filter((value): value is number => value !== null),
    ...lines
      .filter(
        line => axisId(line.axis) === side && line.statistic === undefined,
      )
      .map(line => line.value),
  ];
  /**
   * The highest and the lowest a mark or a reference line reaches on one
   * axis: a stack reaches the sum of its parts on either side of zero,
   * anything else its value. The axis starts at zero, so neither is ever
   * past it the wrong way. A line past the marks is a threshold the reader
   * set, and the axis stretches to it rather than letting it fall off.
   */
  const reachOn = (side: Side, withLines = true) => {
    let high = 0;
    let low = 0;
    for (const [index] of data.points.entries()) {
      const sums = new Map<string | undefined, { up: number; down: number }>();
      for (const entry of series.filter(one => one.side === side)) {
        const value = drawnAt(entry, index);
        if (typeof value !== 'number') continue;
        const stack = stackOf(entry);
        if (stack === undefined) {
          high = Math.max(high, value);
          low = Math.min(low, value);
          continue;
        }
        const sum = sums.get(stack) ?? { up: 0, down: 0 };
        if (value > 0) sum.up += value;
        else sum.down += value;
        sums.set(stack, sum);
      }
      for (const { up, down } of sums.values()) {
        high = Math.max(high, up);
        low = Math.min(low, down);
      }
    }
    if (!withLines) return { high, low };
    const over = [
      ...lines.filter(one => axisId(one.axis) === side).map(one => one.value),
      ...bands
        .filter(band => axisId(band.axis) === side)
        .flatMap(band => [band.from, band.to]),
      ...derived
        .filter(line => line.side === side)
        .flatMap(line => line.values)
        .filter((value): value is number => value !== null),
    ].filter(Number.isFinite);
    for (const value of over) {
      high = Math.max(high, value);
      low = Math.min(low, value);
    }
    return { high, low };
  };
  const wholeOn = (side: Side) => !sharesOn(side) && allWhole(valuesOn(side));
  // A bound the analyst set is theirs: the library scales that chart.
  const bounded = sides.some(side => {
    const axis = cartesian?.yAxis?.[side];
    return axis?.min !== undefined || axis?.max !== undefined;
  });
  const owned = bounded
    ? []
    : sharedScales(
        sides.map(side =>
          sharesOn(side)
            ? { low: 0, high: 1, whole: false }
            : { ...reachOn(side), whole: wholeOn(side) },
        ),
      );
  const scales: Partial<Record<Side, NiceScale>> = {};
  for (const [index, side] of sides.entries())
    if (owned[index]) scales[side] = owned[index];

  const span = (side: Side) => {
    const scale = scales[side];
    if (scale) return { min: scale.min, max: scale.max };
    const axis = cartesian?.yAxis?.[side];
    const reach = reachOn(side);
    return {
      min: axis?.min ?? reach.low,
      // The library rounds the top up; a tenth is about what it adds.
      max: axis?.max ?? (reach.high > 0 ? reach.high * 1.1 : 1),
    };
  };

  const insideText = (entry: DrawnSeries, index: number): string => {
    const value = drawnAt(entry, index);
    if (value === null || value === 0 || filledAt(entry, index)) return '';
    const stack = stackOf(entry);
    const members = stack === undefined ? [] : (stacks.get(stack) ?? []);
    const parts = members.filter(member => {
      const at = data.points[index]?.values[member.key];
      return typeof at === 'number' && at !== 0;
    });
    // The stack's one part is its whole: the total over the stack says it,
    // and the segment saying it too wrote one number twice.
    const totalled =
      !asShares(entry) && totalsOn(members) && parts.length === 1;
    return totalled ? '' : drawnText(entry, value);
  };
  /** Whether a stack of these members writes a total over itself. */
  function totalsOn(members: readonly DrawnSeries[]) {
    const bars = members.filter(member => member.kind === 'bar');
    return bars.length > 1 && labelled(bars[0]) && !asShares(bars[0]);
  }

  const totals = [...stacks.values()]
    .map(members => members.filter(member => member.kind === 'bar'))
    .filter(members => totalsOn(members))
    .map(members => ({
      members,
      // A stack made only of filled parts measured nothing: no total.
      texts: data.points.map(point => {
        const parts = members
          .filter(member => !point.filled?.includes(member.key))
          .map(member => point.values[member.key])
          .filter((value): value is number => typeof value === 'number');
        return parts.length > 0
          ? label(
              members[0].metric,
              parts.reduce((sum, value) => sum + value, 0),
              true,
            )
          : '';
      }),
    }));

  const outerTexts = [
    ...series
      // A stacked bar writes its part inside its segment.
      .filter(
        entry => labelled(entry) && !(entry.kind === 'bar' && stacked(entry)),
      )
      .flatMap(entry =>
        data.points.map((_point, index) => {
          const value = drawnAt(entry, index);
          return value === null || filledAt(entry, index)
            ? ''
            : drawnText(entry, value);
        }),
      ),
    ...totals.flatMap(total => total.texts),
  ];

  const shortTick = new Map<string, string>();
  names.forEach((name, index) => {
    const short = ticks?.[index];
    if (short !== undefined && !shortTick.has(name)) shortTick.set(name, short);
  });
  const tickOf = (name: string) => shortTick.get(name) ?? categoryTick(name);
  const extremesOf = (entry: DrawnSeries) =>
    stacked(entry) ||
    asShares(entry) ||
    (entry.kind === 'bar' && data.points.length > LARGE_FROM)
      ? undefined
      : data.extremes?.[entry.key];

  return {
    data,
    context,
    horizontal,
    names,
    tickOf,
    series,
    legend,
    sides,
    stackOf,
    stacks,
    stacked,
    asShares,
    shareAt,
    drawnAt,
    drawnText,
    labelled,
    filledAt,
    insideText,
    totals,
    outerTexts,
    scales,
    span,
    reach: reachOn,
    wholeOn,
    sharesOn,
    valuesOn,
    lines,
    derived,
    derivedLegend,
    extremesOf,
  };
}
