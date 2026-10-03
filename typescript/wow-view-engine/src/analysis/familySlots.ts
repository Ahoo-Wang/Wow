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
  BoxplotSpec,
  CalendarSpec,
  CandlestickSpec,
  CartesianSeries,
  CartesianSpec,
  ChartType,
  FunnelSpec,
  GaugeSpec,
  HeatmapSpec,
  HierarchySpec,
  MapSpec,
  MetricCardSpec,
  ParallelSpec,
  PieSpec,
  RadarSpec,
  SankeySpec,
  ScatterSpec,
  ThemeRiverSpec,
  TreemapSpec,
  WaterfallSpec,
} from '../model/index.js';
import { boxSet, type FiveNumbers } from './boxplot.js';
import { ohlcSet, type Ohlc } from './candlestick.js';
import { chartLevels } from './hierarchy.js';
import { profileAxes } from './profiles.js';

/**
 * Each family's slots, filled from the shape in force: the rules
 * `fitChartSlots` runs through the family's row of `FAMILY_RULES`
 * (`familyRules.ts`). A slot the user chose is kept while the shape still
 * has it; everything else is filled again.
 */

/**
 * What a card's headline may be: any metric over no dimension; over the one
 * date dimension only one read off sums (`readsOffSums`, D38) — the first
 * of those, when the one chosen is not.
 */
export function headlines(shape: SlotShape): string[] {
  return shape.groups.length === 0
    ? shape.metrics
    : shape.metrics.filter(alias => shape.trendable.has(alias));
}

/** The aliases a chart may reference, and the facts a slot rule asks. */
export interface SlotShape {
  /** What each metric is a quantity of (`metricMeasure`), by alias. */
  measures: ReadonlyMap<string, string>;
  groups: string[];
  metrics: string[];
  /** The metrics a mark can measure: every one that is not a moment. */
  quantities: string[];
  /** Each field's five numbers the quantities hold (`fiveNumberSets`). */
  fiveNumbers: FiveNumbers[];
  /** Each field's four numbers of a candle the quantities hold (`ohlcSets`). */
  ohlc: Ohlc[];
  /** Metrics the projection may add up across rows (a pie's merged tail). */
  additive: Set<string>;
  /**
   * Metrics a card's trend can headline (`readsOffSums`): those that add,
   * and the ratios of their sums (D38).
   */
  trendable: Set<string>;
  dateGroups: string[];
  /** The date groups bucketed by day: what a calendar lays out. */
  dayGroups: string[];
}

/**
 * The alias in this slot, or the one the shape offers for it.
 *
 * An empty string is what a slot with nothing to hold reads as — no group is
 * named that — so the chart keeps a complete sub-object and validation names
 * the slot instead of the family.
 */
export function slot(
  chosen: string | undefined,
  available: readonly string[],
  at = 0,
): string {
  if (chosen !== undefined && available.includes(chosen)) return chosen;
  return available[at] ?? '';
}

/**
 * A cartesian chart consumes every group: one on the horizontal axis, a second
 * pivoted into a series per value. A pivot draws exactly one metric, so the
 * series list narrows to one the moment a second group appears and opens back
 * up to every metric when it goes.
 *
 * Between those two moments the series list is the analyst's (D20 屏 J): a
 * list that still names metrics the shape has is kept exactly as it stands,
 * because this function runs again on every redraw and a list re-derived
 * there is a series the options panel cannot remove — pressed, it came
 * straight back.
 */
export function cartesianSlots(
  spec: CartesianSpec | undefined,
  shape: SlotShape,
  type: ChartType,
): CartesianSpec {
  const x = slot(spec?.x, shape.groups);
  const others = shape.groups.filter(alias => alias !== x);
  const splitBy = others.length > 0 ? slot(spec?.splitBy, others) : undefined;
  const previous = new Map(
    (spec?.series ?? []).map(series => [series.metric, series]),
  );
  // What the spec draws now, minus any metric the shape no longer has. The
  // pivot that narrowed it is the one thing that reopens it: that narrowing
  // was the shape's doing, not a choice anyone made.
  const named =
    spec?.splitBy === undefined
      ? (spec?.series ?? [])
          .map(series => series.metric)
          .filter(metric => shape.quantities.includes(metric))
      : [];
  const pivoted = slot(spec?.series?.[0]?.metric, shape.quantities);
  const drawn =
    splitBy !== undefined
      ? pivoted === ''
        ? []
        : [pivoted]
      : named.length > 0
        ? arrivingCombo(type, named, previous)
          ? [
              ...named,
              ...shape.quantities.filter(metric => !named.includes(metric)),
            ]
          : named
        : shape.quantities;
  const lead = drawn[0];
  const leadSide =
    lead === undefined ? 'left' : (previous.get(lead)?.axis ?? 'left');
  const series = drawn.map((metric, index) =>
    oneSeries(
      previous.get(metric),
      metric,
      type,
      index,
      comboAxis(shape.measures, lead, metric, leadSide),
    ),
  );
  // A reference line names the axis it hangs on, and an axis with no series
  // on it is not an axis; the line goes with the series that left.
  const axes = new Set(series.map(entry => entry.axis ?? 'left'));
  // A statistic line, like a derived series, is taken of one drawn metric,
  // and goes with it too.
  const drawnOn = (metric: string | undefined, axis?: 'left' | 'right') =>
    series.some(
      entry =>
        entry.metric === metric &&
        (axis === undefined || (entry.axis ?? 'left') === axis),
    );
  const lines = (spec?.referenceLines ?? []).filter(
    line =>
      axes.has(line.axis) &&
      (line.statistic === undefined || drawnOn(line.metric, line.axis)),
  );
  const bands = (spec?.referenceBands ?? []).filter(band =>
    axes.has(band.axis),
  );
  const derived = (spec?.derived ?? []).filter(entry => drawnOn(entry.metric));
  // A share of a stack is a part of a sum: over a metric that does not add
  // up there is no whole to be a part of (`chart.cartesian.percent-not-additive`).
  const shares =
    spec?.percentStack === true &&
    series.length > 0 &&
    series.every(entry => shape.additive.has(entry.metric));
  return {
    x,
    ...(splitBy === undefined ? {} : { splitBy }),
    series,
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
    ...(spec?.yAxis === undefined ? {} : { yAxis: spec.yAxis }),
    ...(lines.length > 0 ? { referenceLines: lines } : {}),
    ...(bands.length > 0 ? { referenceBands: bands } : {}),
    ...(spec?.extremes === true ? { extremes: true } : {}),
    ...(derived.length > 0 ? { derived } : {}),
    ...(spec?.missing === undefined ? {} : { missing: spec.missing }),
    ...(shares ? { percentStack: true } : {}),
  };
}

/**
 * One drawn series, keeping how it was drawn; a combo names its own mark,
 * and a series that has none yet takes the combo's default for its place
 * (`comboMark`) and the axis its measure asks for (`comboAxis`), unless an
 * axis was chosen for it already.
 */
function oneSeries(
  previous: CartesianSeries | undefined,
  metric: string,
  type: ChartType,
  index: number,
  axis: 'left' | 'right',
): CartesianSeries {
  const series: CartesianSeries = { ...previous, metric };
  if (type !== 'combo' || series.type !== undefined) return series;
  return {
    ...series,
    type: comboMark(index),
    ...(series.axis === undefined && axis === 'right' ? { axis } : {}),
  };
}

/**
 * The mark a combo gives the series at `index` when nobody has chosen one:
 * the first metric as bars, every other as a line — Metabase's combo, and
 * the only reading of 「组合图」 that is not a bar chart under another name.
 * Picking it used to draw every series as bars, and the analyst who asked
 * for bars with a line saw nothing change (2026-09-23 audit P1-8). A mark
 * the analyst picked on the options page is kept.
 */
export function comboMark(index: number): 'bar' | 'line' {
  return index === 0 ? 'bar' : 'line';
}

/**
 * The axis a combo gives `metric` when nobody has chosen one: the first
 * series' side (`leadSide`) when it measures the same kind of thing as the
 * first series (`lead`), the other side when it does not.
 *
 * Decided by what the metrics are, not by their values, which this layer
 * never sees (`metricMeasure`): a count beside an amount, an amount beside
 * an average, a percent beside a count are two scales, and drawn on one the
 * smaller lies flat along zero — the combo's second metric always sat on the
 * left axis, and a count beside money read as nothing. Two metrics of one
 * measure stay together even when their values are far apart: nothing says
 * they differ, and one scale is what lets them be compared. An axis the
 * analyst picked on the options page is theirs and is kept.
 */
export function comboAxis(
  measures: ReadonlyMap<string, string>,
  lead: string | undefined,
  metric: string,
  leadSide: 'left' | 'right' = 'left',
): 'left' | 'right' {
  const same =
    lead === undefined ||
    lead === metric ||
    measures.get(lead) === measures.get(metric);
  if (same) return leadSide;
  return leadSide === 'left' ? 'right' : 'left';
}

/**
 * A combo just picked over a chart that draws one metric of several: one
 * mark is no combo, so the others join as lines. Told apart by the series
 * naming no mark — every series a combo has drawn names one, so a combo the
 * analyst narrowed to one series on its options page stays one series.
 */
function arrivingCombo(
  type: ChartType,
  named: readonly string[],
  previous: ReadonlyMap<string, CartesianSeries>,
): boolean {
  return (
    type === 'combo' &&
    named.length === 1 &&
    previous.get(named[0])?.type === undefined
  );
}

/**
 * A pie's slices are shares of a whole, so it measures what adds up (D33
 * Q56), as the families below do (`summed`).
 */
export function pieSlots(spec: PieSpec | undefined, shape: SlotShape): PieSpec {
  const value = slot(spec?.value, summed(shape));
  const merges =
    spec?.maxSlices !== undefined &&
    Number.isInteger(spec.maxSlices) &&
    spec.maxSlices >= 2;
  return {
    category: slot(spec?.category, shape.groups),
    value,
    ...(spec?.donut === undefined ? {} : { donut: spec.donut }),
    ...(merges ? { maxSlices: spec?.maxSlices } : {}),
  };
}

export function heatmapSlots(
  spec: HeatmapSpec | undefined,
  shape: SlotShape,
): HeatmapSpec {
  const x = slot(spec?.x, shape.groups);
  const y = slot(
    spec?.y,
    shape.groups.filter(alias => alias !== x),
  );
  return {
    x,
    y,
    value: slot(spec?.value, shape.quantities),
    ...(spec?.scale === undefined ? {} : { scale: spec.scale }),
  };
}

export function scatterSlots(
  spec: ScatterSpec | undefined,
  shape: SlotShape,
): ScatterSpec {
  const x = slot(spec?.x, shape.quantities);
  const y = slot(
    spec?.y,
    shape.quantities.filter(alias => alias !== x),
  );
  const size =
    spec?.size !== undefined && shape.quantities.includes(spec.size)
      ? spec.size
      : undefined;
  return {
    category: slot(spec?.category, shape.groups),
    x,
    y,
    ...(size === undefined ? {} : { size }),
    // How the axes are drawn is the analyst's, whichever metrics they hold.
    ...(spec?.xAxis === undefined ? {} : { xAxis: spec.xAxis }),
    ...(spec?.yAxis === undefined ? {} : { yAxis: spec.yAxis }),
  };
}

/**
 * Stages come from metrics when there is nothing to group by, and otherwise
 * from the values of the one group, whose business order is data this layer
 * has never seen, so an order already written down is kept and an absent one
 * stays absent for the editor to ask about. Either way a stage measures only
 * what adds up — a record count or a sum.
 */
export function funnelSlots(
  spec: FunnelSpec | undefined,
  shape: SlotShape,
): FunnelSpec {
  // A stage is a count of what entered or remained: only a metric that adds
  // up is one (`chart.funnel.not-additive`), so a lead that does not gives
  // way to the first that does.
  const counted = shape.quantities.filter(alias => shape.additive.has(alias));
  const rest = {
    ...(spec?.orientation === undefined
      ? {}
      : { orientation: spec.orientation }),
  };
  if (shape.groups.length === 0) {
    // The stages stay in the order the spec puts them in — that order is
    // the analyst's, set by hand on the options page, and this runs again
    // on every redraw — with any metric the list does not name yet added at
    // the end, where it can be moved from.
    const named =
      spec?.stages.from === 'metrics'
        ? spec.stages.items.filter(item => counted.includes(item.metric))
        : [];
    const taken = new Set(named.map(item => item.metric));
    return {
      stages: {
        from: 'metrics',
        items: [
          ...named,
          ...counted
            .filter(metric => !taken.has(metric))
            .map(metric => ({ metric })),
        ],
      },
      ...rest,
    };
  }
  const category = slot(
    spec?.stages.from === 'group' ? spec.stages.category : undefined,
    shape.groups,
  );
  const kept = spec?.stages.from === 'group' ? spec.stages : undefined;
  return {
    stages: {
      from: 'group',
      category,
      value: slot(kept?.value, counted),
      // A stage listed twice is one stage (`chart.funnel.duplicate-stage`).
      order: [...new Set(kept?.order ?? [])],
      ...(kept?.cumulative === undefined
        ? {}
        : { cumulative: kept.cumulative }),
    },
    ...rest,
  };
}

/**
 * The metrics a family that adds its numbers up may measure: the quantities
 * that add (a record count or a sum), so a lead that does not — an average
 * carried in from a bar chart — gives way to the first that does.
 */
export function summed(shape: SlotShape): string[] {
  return shape.quantities.filter(alias => shape.additive.has(alias));
}

/** A waterfall steps along its one dimension and adds up one metric. */
export function waterfallSlots(
  spec: WaterfallSpec | undefined,
  shape: SlotShape,
): WaterfallSpec {
  return {
    x: slot(spec?.x, shape.groups),
    value: slot(spec?.value, summed(shape)),
    ...(spec?.total === undefined ? {} : { total: spec.total }),
  };
}

/**
 * A treemap tiles one dimension; a second, when there is one, is the outer
 * level the tiles nest in (`parent`), and goes when it does.
 */
export function treemapSlots(
  spec: TreemapSpec | undefined,
  shape: SlotShape,
): TreemapSpec {
  const category = slot(spec?.category, shape.groups);
  const others = shape.groups.filter(alias => alias !== category);
  return {
    category,
    ...(others.length > 0 ? { parent: slot(spec?.parent, others) } : {}),
    value: slot(spec?.value, summed(shape)),
  };
}

/**
 * A card is one number, so it groups by nothing — unless it draws a sparkline,
 * which needs exactly one date grouping and a headline read off sums, since
 * that headline is the buckets summed whenever the totals query did not run
 * — or, for a ratio of sums, their sums divided (D38).
 */
export function cardSlots(
  spec: MetricCardSpec | undefined,
  shape: SlotShape,
): MetricCardSpec {
  const metric = slot(spec?.metric, headlines(shape));
  // A moment is written out as it is: nothing compares to it, nothing is a
  // target for it, and a number format does not print it.
  const quantity = shape.quantities.includes(metric);
  const compare =
    quantity &&
    spec?.compare !== undefined &&
    shape.quantities.includes(spec.compare.metric)
      ? spec.compare
      : undefined;
  const trending =
    shape.groups.length === 1 &&
    shape.dateGroups.length === 1 &&
    shape.trendable.has(metric) &&
    (compare === undefined || shape.trendable.has(compare.metric));
  return {
    metric,
    ...(compare === undefined ? {} : { compare }),
    ...(spec?.target === undefined || !quantity ? {} : { target: spec.target }),
    ...(spec?.format === undefined || !quantity ? {} : { format: spec.format }),
    ...(spec?.lowerIsBetter === true && quantity
      ? { lowerIsBetter: true }
      : {}),
    // How the trend reads — its last period or the whole — is the
    // analyst's, and survives a refit onto another date group.
    ...(trending ? { trend: { ...spec?.trend, x: shape.dateGroups[0] } } : {}),
  };
}

/** A box: one dimension, and a field's five numbers kept or found again. */
export function boxplotSlots(
  spec: BoxplotSpec | undefined,
  shape: SlotShape,
): BoxplotSpec {
  return {
    category: slot(spec?.category, shape.groups),
    ...boxSet(spec, shape.fiveNumbers),
  };
}

/** A candle: one date dimension, and a field's four numbers. */
export function candlestickSlots(
  spec: CandlestickSpec | undefined,
  shape: SlotShape,
): CandlestickSpec {
  return {
    x: slot(spec?.x, shape.dateGroups),
    ...ohlcSet(spec, shape.ohlc),
  };
}

/** Its scale and target are the analyst's, whichever metric it reads. */
export function gaugeSlots(
  spec: GaugeSpec | undefined,
  shape: SlotShape,
): GaugeSpec {
  return {
    ...spec,
    metric: slot(spec?.metric, shape.quantities),
  };
}

/** The map is the host's, and stays whichever region it shades. */
export function mapSlots(spec: MapSpec | undefined, shape: SlotShape): MapSpec {
  return {
    region: slot(spec?.region, shape.groups),
    value: slot(spec?.value, shape.quantities),
    ...(spec?.map === undefined ? {} : { map: spec.map }),
  };
}

/** A calendar lays out one dimension by day. */
export function calendarSlots(
  spec: CalendarSpec | undefined,
  shape: SlotShape,
): CalendarSpec {
  return {
    date: slot(spec?.date, shape.dayGroups),
    value: slot(spec?.value, shape.quantities),
  };
}

/** The river runs along the date; the other dimension is its streams. */
export function riverSlots(
  spec: ThemeRiverSpec | undefined,
  shape: SlotShape,
): ThemeRiverSpec {
  const x = slot(spec?.x, shape.dateGroups);
  return {
    x,
    splitBy: slot(
      spec?.splitBy,
      shape.groups.filter(alias => alias !== x),
    ),
    value: slot(spec?.value, summed(shape)),
  };
}

/**
 * A sunburst, a tree or a sankey: every dimension is a level, in the
 * analyst's order; the parts are sizes of a whole, so only what adds up
 * measures them.
 */
export function levelSlots(
  spec: HierarchySpec | SankeySpec | undefined,
  shape: SlotShape,
): HierarchySpec {
  return {
    levels: chartLevels(spec?.levels, shape.groups),
    value: slot(spec?.value, summed(shape)),
  };
}

/** A radar or parallel axes: one dimension, and an axis per quantity. */
export function profileSlots(
  spec: RadarSpec | ParallelSpec | undefined,
  shape: SlotShape,
): RadarSpec {
  return {
    category: slot(spec?.category, shape.groups),
    metrics: profileAxes(spec?.metrics, shape.quantities),
  };
}
