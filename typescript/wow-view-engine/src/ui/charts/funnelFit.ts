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
import {
  drawnStages,
  dropText,
  GAP,
  numbersOf,
  type DrawnStage,
  type FunnelContext,
  type FunnelWords,
} from './funnelOption.js';
import { measureText } from './measure.js';
import type { ChartTheme } from './theme.js';

type Text = ChartTheme['text'];

/** The longest a stage is drawn, in lines of the chart's text. */
const STAGE_LINES = 4.5;
/** Lying down, the longest a stage runs along, in lines of the text. */
const STAGE_ALONG = 16;
/** A line of the chart's text, as a multiple of its size. */
const LINE_HEIGHT = 1.4;
/** The widest a standing funnel grows, in lines of the chart's text. */
const FUNNEL_LINES = 40;
/** The room around the drawing, and between a mark and its words. */
const PAD = 4;
/** The room kept free inside a stage around the words written in it. */
const INSET = 6;
/** How far the column of words stands from the funnel's widest point. */
const LEADER = 16;
/** A heavier word is a little wider than the canvas measures a plain one. */
const BOLD = 1.06;

/** How long a standing stage is drawn at most, for the chart's text size. */
export function stageLength(size: number): number {
  return Math.round(size * STAGE_LINES);
}

/**
 * How tall a funnel's plot is: its stages at their longest, in `rem` so it
 * follows the host's root size as the text does. The plot is sized by what
 * it draws rather than by its width's aspect: five stages drew 761px tall
 * at 16:9, each 121px, with the words far off to one side (2026-09-25). A
 * funnel lying down has one band of stages and two rows of words under it.
 */
export function funnelPlotHeight(stages: number, horizontal: boolean): string {
  // The chart's text is 0.75rem (`text-xs`); a stage is STAGE_LINES of it.
  const stage = 0.75 * STAGE_LINES;
  const rem = horizontal
    ? stage * 3.5
    : stages * stage + ((stages - 1) * GAP + PAD * 2) / 16;
  return `${Math.round(rem * 1000) / 1000}rem`;
}

/** Where a funnel's parts land in a plot of one size. */
export interface FunnelLayout {
  /** The series' box, the unseen foot's room included. */
  box: { left: number; top: number; width: number; height: number };
  /** Each stage: its extent along the funnel, its widths, its words. */
  stages: readonly {
    start: number;
    end: number;
    /** Its width across at its start and at its end. */
    from: number;
    to: number;
    words: 'inside' | 'outside';
  }[];
  /** The words' graphic elements, by the ids `funnelOption` made them with. */
  elements: Record<string, unknown>[];
}

/**
 * The funnel fitted to its plot, as the chart's `adapt`: each stage as long
 * as the plot allows and never longer than `stageLength` — the stages
 * centred in what is left, so a board's tall panel does not stretch five
 * stages to a hand's width each — and the funnel as wide as the plot leaves
 * beside its column of words, never wider than `FUNNEL_LINES` of text.
 * A stage's words go inside it, on two lines or one, when they fit the
 * narrower of its two edges; otherwise beside the funnel with a leader, the
 * name cut with an ellipsis before any number is (the stage's tooltip says
 * it whole). The drops stand in that column level with their seams.
 *
 * Lying down, the stages share the width, their words go inside or in a
 * row under them, and the drops in a second row under their seams.
 */
export function funnelFit(
  data: FunnelData,
  context: Pick<
    FunnelContext,
    'spec' | 'label' | 'column' | 'locale' | 'words'
  >,
): (
  width: number,
  height: number,
  window: unknown,
  text: Text,
) => EChartsCoreOption | undefined {
  const stages = drawnStages(data, context);
  const horizontal = context.spec?.funnel?.orientation === 'horizontal';
  return (width, height, _window, text) => {
    if (stages.length === 0 || width <= 0 || height <= 0) return undefined;
    const layout = (horizontal ? lyingDown : standing)(
      stages,
      width,
      height,
      text,
      context.words,
    );
    return {
      series: [{ ...layout.box }],
      graphic: { elements: layout.elements },
    };
  };
}

const widthOf = (text: Text, words: string, bold = false) =>
  measureText(words, text.family, text.size) * (bold ? BOLD : 1);

/**
 * `name` cut with an ellipsis until it and `rest` fit `room`: the numbers
 * are never cut, the name gives way.
 */
export function fitName(
  name: string,
  rest: string,
  room: number,
  text: Text,
): string {
  const whole = (cut: string) =>
    widthOf(text, rest ? `${cut} ${rest}` : cut, true);
  if (whole(name) <= room) return name;
  const chars = [...name];
  for (let keep = chars.length - 1; keep > 0; keep--) {
    const cut = `${chars.slice(0, keep).join('')}…`;
    if (whole(cut) <= room) return cut;
  }
  return '…';
}

/** A stage's words on one line: its name, then its numbers. */
const oneLine = (stage: DrawnStage) => `${stage.name} ${numbersOf(stage)}`;

/** A drop's words, the largest one's naming itself. */
const dropWords = (stage: DrawnStage, words: FunnelWords) =>
  stage.drop === undefined
    ? ''
    : stage.drop.largest
      ? `${dropText(stage.drop)} · ${words.largest}`
      : dropText(stage.drop);

/**
 * Whether a stage's words fit inside it, and on how many lines: `along` is
 * the room along the funnel, `across` the narrower of its two edges.
 */
function wordsInside(
  stage: DrawnStage,
  along: number,
  across: number,
  line: number,
  text: Text,
): 0 | 1 | 2 {
  if (
    along >= line * 2 + 2 &&
    Math.max(
      widthOf(text, stage.name, true),
      widthOf(text, numbersOf(stage), true),
    ) <= across
  )
    return 2;
  if (along >= line && widthOf(text, oneLine(stage), true) <= across) return 1;
  return 0;
}

/** The words written inside a stage, on the lines it has room for. */
const insideText = (stage: DrawnStage, lines: 0 | 1 | 2) =>
  lines === 2
    ? `${stage.name}\n${numbersOf(stage)}`
    : lines === 1
      ? oneLine(stage)
      : '';

/** A funnel standing up: the stages top to bottom, the words to the right. */
export function standing(
  stages: readonly DrawnStage[],
  width: number,
  height: number,
  text: Text,
  words: FunnelWords,
): FunnelLayout {
  const count = stages.length;
  const line = Math.ceil(text.size * LINE_HEIGHT);
  const along = Math.max(
    1,
    Math.min(
      stageLength(text.size),
      (height - PAD * 2 - GAP * (count - 1)) / count,
    ),
  );
  const top = Math.max(PAD, (height - (count * along + (count - 1) * GAP)) / 2);
  const longest = Math.max(0, ...stages.map(stage => stage.value));
  const drops = Math.max(
    0,
    ...stages.map(stage =>
      widthOf(text, dropWords(stage, words), stage.drop?.largest === true),
    ),
  );
  const place = (column: number) => {
    const wide = Math.max(
      0,
      Math.min(width - column - LEADER - PAD * 2, text.size * FUNNEL_LINES),
    );
    // The funnel and its words together, centred in the plot.
    const left = Math.max(PAD, (width - (wide + LEADER + column)) / 2);
    const across = (value: number) =>
      longest > 0 ? (Math.max(0, value) / longest) * wide : 0;
    const extents = stages.map((stage, index) => {
      const start = top + index * (along + GAP);
      const from = across(stage.value);
      const to = across(stages[index + 1]?.value ?? stage.value);
      const lines = wordsInside(
        stage,
        along,
        Math.min(from, to) - INSET * 2,
        line,
        text,
      );
      return { start, end: start + along, from, to, lines };
    });
    return { left, wide, extents };
  };
  // Placed twice: the stages whose words go beside the funnel widen the
  // column, which narrows the funnel, which can send more words out. The
  // column takes at most half the plot; past that the names are cut.
  let placed = place(drops);
  const beside = Math.max(
    drops,
    ...placed.extents.flatMap((extent, index) =>
      extent.lines === 0 ? [widthOf(text, oneLine(stages[index]), true)] : [],
    ),
  );
  if (beside > drops) placed = place(Math.min(beside, width / 2));
  const { left, wide, extents } = placed;
  const centre = left + wide / 2;
  const columnX = left + wide + LEADER;
  const room = Math.max(0, width - columnX - PAD);
  const placements = extents.map((extent, index) => {
    const stage = stages[index];
    const middle = (extent.start + extent.end) / 2;
    const inside = extent.lines > 0;
    const edge = centre + (extent.from + extent.to) / 4;
    const placedWords: Record<string, unknown>[] = [
      {
        id: `stage-in-${index}`,
        invisible: !inside,
        x: centre,
        y: middle,
        style: {
          text: insideText(stage, extent.lines),
          align: 'center',
          verticalAlign: 'middle',
          lineHeight: line,
        },
      },
      {
        id: `stage-out-${index}`,
        invisible: inside,
        x: columnX,
        y: middle,
        style: {
          text: inside
            ? ''
            : `${fitName(stage.name, numbersOf(stage), room, text)} ${numbersOf(stage)}`,
          align: 'left',
          verticalAlign: 'middle',
        },
      },
      {
        id: `leader-${index}`,
        invisible: inside,
        shape: {
          points: inside
            ? []
            : [
                [edge + PAD / 2, middle],
                [columnX - PAD, middle],
              ],
        },
      },
    ];
    if (stage.drop === undefined) return { placedWords, dropped: [] };
    // Level with the seam between this stage and the one before it.
    const seam = extent.start - GAP / 2;
    const largest = stage.drop.largest;
    return {
      placedWords,
      dropped: [
        {
          id: `drop-${index}`,
          invisible: false,
          x: columnX,
          y: seam,
          style: {
            text: dropWords(stage, words),
            align: 'left',
            verticalAlign: 'middle',
            width: room,
            overflow: 'truncate',
          },
        },
        {
          id: `drop-leader-${index}`,
          invisible: !largest,
          shape: {
            points: largest
              ? [
                  [centre + extent.from / 2 + PAD / 2, seam],
                  [columnX - PAD, seam],
                ]
              : [],
          },
        },
      ],
    };
  });
  // In the order `funnelOption` made them: every stage's words, then every
  // drop's — the first drawing lays one onto the other item by item.
  const elements = [
    ...placements.flatMap(one => one.placedWords),
    ...placements.flatMap(one => one.dropped),
  ];
  return {
    // The foot is one more datum: the box is one stage longer than those
    // drawn, and the foot, of no length, leaves that room empty.
    box: {
      left,
      top,
      width: wide,
      height: (count + 1) * along + count * GAP,
    },
    stages: extents.map(({ lines, ...extent }) => ({
      ...extent,
      words: lines > 0 ? 'inside' : 'outside',
    })),
    elements,
  };
}

/** A funnel lying down: stages left to right, the words under them. */
export function lyingDown(
  stages: readonly DrawnStage[],
  width: number,
  height: number,
  text: Text,
  words: FunnelWords,
): FunnelLayout {
  const count = stages.length;
  const line = Math.ceil(text.size * LINE_HEIGHT);
  const along = Math.max(
    1,
    Math.min(
      text.size * STAGE_ALONG,
      (width - PAD * 2 - GAP * (count - 1)) / count,
    ),
  );
  const left = Math.max(PAD, (width - (count * along + (count - 1) * GAP)) / 2);
  const top = PAD;
  const longest = Math.max(0, ...stages.map(stage => stage.value));
  // Under the stages: the drops, on up to three lines — the count, the
  // share, and the largest one's words — and, only when some stage's words
  // do not fit inside it, a row of those above them: name, value, share.
  const place = (wordsRow: boolean) => {
    const below = (wordsRow ? PAD + line * 3 : 0) + PAD + line * 3;
    const tall = Math.max(1, height - PAD * 2 - below);
    const across = (value: number) =>
      longest > 0 ? (Math.max(0, value) / longest) * tall : 0;
    const extents = stages.map((stage, index) => {
      const start = left + index * (along + GAP);
      const from = across(stage.value);
      const to = across(stages[index + 1]?.value ?? stage.value);
      const lines = wordsInside(
        stage,
        Math.min(from, to),
        along - INSET * 2,
        line,
        text,
      );
      return { start, end: start + along, from, to, lines };
    });
    return { tall, extents };
  };
  let placed = place(false);
  const wordsRow = placed.extents.some(extent => extent.lines === 0);
  if (wordsRow) placed = place(true);
  const { tall, extents } = placed;
  const middle = top + tall / 2;
  const wordsY = top + tall + PAD;
  const dropsY = wordsY + (wordsRow ? line * 3 + PAD : 0);
  const placements = extents.map((extent, index) => {
    const stage = stages[index];
    const centre = (extent.start + extent.end) / 2;
    const inside = extent.lines > 0;
    const placedWords: Record<string, unknown>[] = [
      {
        id: `stage-in-${index}`,
        invisible: !inside,
        x: centre,
        y: middle,
        style: {
          text: insideText(stage, extent.lines),
          align: 'center',
          verticalAlign: 'middle',
          lineHeight: line,
        },
      },
      {
        id: `stage-out-${index}`,
        invisible: inside,
        x: centre,
        y: wordsY,
        style: {
          text: inside
            ? ''
            : [fitName(stage.name, '', along, text), stage.text, stage.share]
                .filter(part => part !== undefined)
                .join('\n'),
          align: 'center',
          verticalAlign: 'top',
          lineHeight: line,
          width: along,
          overflow: 'truncate',
        },
      },
      { id: `leader-${index}`, invisible: true, shape: { points: [] } },
    ];
    if (stage.drop === undefined) return { placedWords, dropped: [] };
    const seam = extent.start - GAP / 2;
    const largest = stage.drop.largest;
    return {
      placedWords,
      dropped: [
        {
          id: `drop-${index}`,
          invisible: false,
          x: seam,
          y: dropsY,
          style: {
            text: [
              stage.drop.text,
              stage.drop.rate,
              largest ? words.largest : undefined,
            ]
              .filter(part => part !== undefined)
              .join('\n'),
            align: 'center',
            verticalAlign: 'top',
            lineHeight: line,
            width: along,
            overflow: 'truncate',
          },
        },
        {
          id: `drop-leader-${index}`,
          invisible: !largest,
          shape: {
            points: largest
              ? [
                  [seam, middle + extent.from / 2 + PAD / 2],
                  [seam, dropsY - PAD / 2],
                ]
              : [],
          },
        },
      ],
    };
  });
  const elements = [
    ...placements.flatMap(one => one.placedWords),
    ...placements.flatMap(one => one.dropped),
  ];
  return {
    box: {
      left,
      top,
      width: (count + 1) * along + count * GAP,
      height: tall,
    },
    stages: extents.map(({ lines, ...extent }) => ({
      ...extent,
      words: lines > 0 ? 'inside' : 'outside',
    })),
    elements,
  };
}
