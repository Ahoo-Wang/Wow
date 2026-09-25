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
import type { FunnelData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { formatShare } from './axis.js';
import { stageName, type ColumnTitle, type ValueLabel } from './family.js';
import { color } from './palette.js';
import { emphasized, inkOn, type ChartTheme, chartText } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** The words a funnel writes that are not its data's. */
export interface FunnelWords {
  /** The tooltip's names for a stage's numbers. */
  value: string;
  fromPrevious: string;
  fromFirst: string;
  drop: string;
  /** Said beside the step that loses the most (「最大流失」). */
  largest: string;
}

/** What a funnel reads besides its stages. */
export interface FunnelContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  words: FunnelWords;
  animate: boolean;
  /**
   * Whether a stage is pressed for its group (a funnel staged by a
   * dimension, with a press to hand it to): the pointer says so.
   */
  pickable?: boolean;
}

/** What a stage lost against the one before it, as it is written. */
export interface DrawnDrop {
  /** The one before's value minus this one's; negative where it grew. */
  value: number;
  /** The count, signed: 「−1,625」, 「+120」, 「0」. */
  text: string;
  /** The share of the one before, signed: 「−8.0%」; none after a 0. */
  rate?: string;
  /** Whether this is the step that loses the most (`largestDrop`). */
  largest: boolean;
}

/** One stage as the drawing, the tooltip and the reading table name it. */
export interface DrawnStage {
  name: string;
  value: number;
  /** Its value as its column reads it. */
  text: string;
  /** Against the one before it; none after a stage of 0. */
  conversion?: string;
  /** Against the first; none when the first is 0. */
  share?: string;
  /** What it lost on the way from the one before; none on the first. */
  drop?: DrawnDrop;
}

const MINUS = '\u2212';

/** The seam between two stages, as the library's own example draws it. */
export const GAP = 2;

/**
 * The stages with their names, values, both conversions and drops. A stage
 * taken from a group is named by that group's value, a metric stage by the
 * name it was given or its column's title (`stageName`); each value, and
 * each drop, reads as the metric that measures it. Percentages are written
 * to one decimal, always (`formatShare`), so 「−8.0%」 beside 「−2.3%」 lines
 * up and none reads rounder than it is.
 */
export function drawnStages(
  data: FunnelData,
  {
    spec,
    label,
    column,
    locale,
  }: Pick<FunnelContext, 'spec' | 'label' | 'column' | 'locale'>,
): DrawnStage[] {
  const stages = spec?.funnel?.stages;
  const measured = (index: number) =>
    stages === undefined
      ? undefined
      : stages.from === 'group'
        ? stages.value
        : stages.items[index]?.metric;
  return data.stages.map((stage, index) => {
    const metric = measured(index);
    const drop: DrawnDrop | undefined =
      stage.drop === undefined
        ? undefined
        : {
            value: stage.drop,
            text: signed(stage.drop, label(metric, Math.abs(stage.drop))),
            ...(stage.conversion === undefined
              ? {}
              : {
                  rate: signed(
                    stage.drop,
                    formatShare(Math.abs(1 - stage.conversion), locale),
                  ),
                }),
            largest: data.largestDrop === index,
          };
    return {
      name: stageName(stages, index, stage.label, label, column),
      value: stage.value,
      text: label(metric, stage.value),
      ...(stage.conversion === undefined
        ? {}
        : { conversion: formatShare(stage.conversion, locale) }),
      ...(stage.share === undefined
        ? {}
        : { share: formatShare(stage.share, locale) }),
      ...(drop === undefined ? {} : { drop }),
    };
  });
}

/** A loss written as one: 「−」 before what went, 「+」 before what came. */
function signed(drop: number, magnitude: string): string {
  if (drop > 0) return `${MINUS}${magnitude}`;
  if (drop < 0) return `+${magnitude}`;
  return magnitude;
}

/** A drop as it is written between two stages: 「−1,625 · −8.0%」. */
export function dropText(drop: DrawnDrop): string {
  return drop.rate === undefined ? drop.text : `${drop.text} · ${drop.rate}`;
}

/** A stage's numbers after its name: its value, and its share of the first. */
export function numbersOf(stage: DrawnStage): string {
  return stage.share === undefined
    ? stage.text
    : `${stage.text} · ${stage.share}`;
}

/**
 * A funnel as the library draws it (2026-09-25, the user's call): the
 * library's own funnel, each stage a trapezoid from its width to the next
 * one's, in the order the stages were given — never re-sorted, since a
 * funnel's order is the business's — with a seam of the ground between
 * them. Widths run from zero to the largest stage, so a funnel that loses
 * little tapers little, which is what it did. The last stage has no next,
 * so it keeps its own width to its foot (an unseen datum of no length after
 * it) rather than running to a point, which would read as nothing left.
 *
 * One hue — the palette's first slot, as one series wears it — and not a
 * slot a stage: the stages are one quantity at successive steps, not
 * categories, and a colour each would say they were; a ramp of lightness
 * would add a second reading to the one the widths already give, and move
 * the ink written inside from stage to stage. Patterns, when they are on,
 * are drawn over it as over any series (`withPatterns`).
 *
 * Every word is the drawing's own text, placed by `funnelFit` once the plot
 * has a size — the stage's name and numbers inside it in the ink that
 * stands furthest from the fill (`inkOn`), or beside the funnel with a
 * leader; the drops level with their seams, the largest in words and
 * weight and with a leader of its own, never by colour alone. They are made
 * here, with their colours, and `funnelFit` places them.
 */
export function funnelOption(
  data: FunnelData,
  context: FunnelContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, animate, pickable = false, words } = context;
  const stages = drawnStages(data, context);
  const horizontal = spec?.funnel?.orientation === 'horizontal';
  const fill = theme.resolve(color(0));
  const longest = Math.max(0, ...stages.map(stage => stage.value));
  const last = stages[stages.length - 1];
  const series = {
    // The stages' own marks, by the id a board's pressed group is marked
    // through (`faded`).
    id: 's0',
    type: 'funnel',
    orient: horizontal ? 'horizontal' : 'vertical',
    sort: 'none',
    funnelAlign: 'center',
    min: 0,
    max: longest > 0 ? longest : 1,
    minSize: '0%',
    maxSize: '100%',
    gap: GAP,
    cursor: pickable ? 'pointer' : 'default',
    label: { show: false },
    labelLine: { show: false },
    itemStyle: {
      color: fill,
      borderColor: theme.ground,
      borderWidth: theme.slice.border,
    },
    emphasis: {
      label: { show: false },
      itemStyle: { color: emphasized(theme, fill) },
    },
    data: [
      ...stages.map(stage => ({
        name: stage.name,
        value: Math.max(0, stage.value),
      })),
      // The last stage's foot: as wide as the last stage, of no length.
      ...(last === undefined
        ? []
        : [
            {
              name: '',
              value: Math.max(0, last.value),
              itemStyle: {
                color: 'transparent',
                borderColor: 'transparent',
                height: 0,
                width: 0,
              },
              emphasis: { disabled: true },
              tooltip: { show: false },
            },
          ]),
    ],
  };
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const stage = stages[dataIndex];
        if (!stage) return '';
        return tooltipHtml(stage.name, [
          { color: fill, name: words.value, value: stage.text },
          ...(stage.drop === undefined
            ? []
            : [
                {
                  color: fill,
                  name: words.drop,
                  value: dropText(stage.drop),
                  ...(stage.drop.largest ? { note: words.largest } : {}),
                },
              ]),
          ...(stage.conversion === undefined || stage.drop === undefined
            ? []
            : [
                {
                  color: fill,
                  name: words.fromPrevious,
                  value: stage.conversion,
                },
              ]),
          ...(stage.share === undefined || stage.drop === undefined
            ? []
            : [{ color: fill, name: words.fromFirst, value: stage.share }]),
        ]);
      },
    },
    series: [series],
    graphic: { elements: funnelWords(stages, theme, fill) },
  };
}

/**
 * The words' elements, unplaced and unseen until `funnelFit` places them:
 * per stage its words inside, its words beside and the leader to them; per
 * drop its words and, for the largest, its leader. Each by an id, so a new
 * size moves them without drawing them again.
 */
function funnelWords(
  stages: readonly DrawnStage[],
  theme: ChartTheme,
  fill: string,
): Record<string, unknown>[] {
  const text = (id: string, ink: string, weight?: number) => ({
    id,
    type: 'text',
    // Over the stages: a graphic stands under the series unless raised.
    z: WORDS_Z,
    silent: true,
    invisible: true,
    style: {
      text: '',
      fill: ink,
      fontFamily: theme.text.family,
      fontSize: theme.text.size,
      ...(weight === undefined ? {} : { fontWeight: weight }),
    },
  });
  const leader = (id: string, stroke: string) => ({
    id,
    type: 'polyline',
    z: WORDS_Z,
    silent: true,
    invisible: true,
    shape: { points: [] },
    style: { stroke, lineWidth: theme.grid.width, fill: 'none' },
  });
  return [
    ...stages.flatMap((_stage, index) => [
      text(`stage-in-${index}`, inkOn(theme, fill), STAGE_WEIGHT),
      text(`stage-out-${index}`, theme.foreground, STAGE_WEIGHT),
      leader(`leader-${index}`, theme.axis.color),
    ]),
    ...stages.flatMap((stage, index) =>
      stage.drop === undefined
        ? []
        : [
            text(
              `drop-${index}`,
              stage.drop.largest ? theme.foreground : theme.axis.color,
              stage.drop.largest ? LARGEST_WEIGHT : undefined,
            ),
            leader(`drop-leader-${index}`, theme.foreground),
          ],
    ),
  ];
}

/** Above every series the library draws. */
const WORDS_Z = 100;
/** A stage's words, a step heavier than the drops beside them. */
const STAGE_WEIGHT = 500;
/** The largest drop's words, heavier again: it is the one to read. */
const LARGEST_WEIGHT = 600;
