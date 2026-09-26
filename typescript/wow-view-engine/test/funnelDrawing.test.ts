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
import { describe, expect, it } from 'vitest';
import type { FunnelData } from '../src/index.js';
import { shapeFunnel } from '../src/analysis/funnel.js';
import {
  fitName,
  funnelFit,
  funnelPlotHeight,
  lyingDown,
  stageLength,
  standing,
} from '../src/ui/charts/funnelFit.js';
import {
  drawnStages,
  dropText,
  funnelOption,
  type FunnelWords,
} from '../src/ui/charts/funnelOption.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';

/** The funnel as the library is asked for it, and as it is fitted to a plot. */
const theme: ChartTheme = {
  ...CHART_FALLBACK,
  key: 'test',
  resolve: color => (color === 'var(--chart-1)' ? 'rgb(38, 117, 211)' : color),
};
const text = CHART_FALLBACK.text;

const label = (_alias: string | undefined, value: unknown) =>
  typeof value === 'number' ? value.toLocaleString('en') : String(value);

const words: FunnelWords = {
  value: 'Value',
  fromPrevious: 'From previous',
  fromFirst: 'From first',
  drop: 'Drop',
  largest: 'Largest drop',
};

/** The funnel of the user's report (2026-09-25): 下单 to 交易完成. */
const ORDERS: FunnelData = {
  type: 'funnel',
  stages: [
    { label: '下单', value: 20375, conversion: 1, share: 1 },
    {
      label: '付款',
      value: 18750,
      conversion: 18750 / 20375,
      share: 18750 / 20375,
      drop: 1625,
    },
    {
      label: '发货',
      value: 18317,
      conversion: 18317 / 18750,
      share: 18317 / 20375,
      drop: 433,
    },
    {
      label: '签收',
      value: 17992,
      conversion: 17992 / 18317,
      share: 17992 / 20375,
      drop: 325,
    },
    {
      label: '交易完成',
      value: 17401,
      conversion: 17401 / 17992,
      share: 17401 / 20375,
      drop: 591,
    },
  ],
  largestDrop: 1,
};

const context = {
  spec: {
    type: 'funnel' as const,
    funnel: {
      stages: {
        from: 'metrics' as const,
        items: ORDERS.stages.map((stage, index) => ({
          metric: `m${index}`,
          label: stage.label,
        })),
      },
    },
  },
  label,
  column: () => undefined,
  locale: 'en',
  words,
  animate: false,
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const byId = (elements: Loose[], id: string) =>
  elements.find(element => element.id === id)!;

describe('shapeFunnel', () => {
  const metricFunnel = (values: number[]) =>
    shapeFunnel(
      {
        stages: {
          from: 'metrics',
          items: values.map((_, index) => ({ metric: `m${index}` })),
        },
      },
      [Object.fromEntries(values.map((value, index) => [`m${index}`, value]))],
    );

  /**
   * The drop is what the one before had and this one has not; the step
   * that loses the most is the one that loses the largest share of what
   * it had, not the largest count — 下单 → 付款 loses 1,625 of 20,375
   * (8.0%), 签收 → 完成 591 of 17,992 (3.3%).
   */
  it('says what each stage lost, and which lost the largest share', () => {
    const data = metricFunnel([20375, 18750, 18317, 17992, 17401]);
    expect(data.stages.map(stage => stage.drop)).toEqual([
      undefined,
      1625,
      433,
      325,
      591,
    ]);
    expect(data.stages[4].share).toBeCloseTo(17401 / 20375);
    expect(data.largestDrop).toBe(1);
    // By share, not by count: 10 of 20 beats 30 of 1,000.
    expect(metricFunnel([1000, 970, 20, 10]).largestDrop).toBe(2);
  });

  it('says a stage that grew, and marks nothing when none lost', () => {
    const equal = metricFunnel([50, 50, 50]);
    expect(equal.stages.map(stage => stage.drop)).toEqual([undefined, 0, 0]);
    expect(equal.stages.map(stage => stage.conversion)).toEqual([1, 1, 1]);
    expect(equal.largestDrop).toBeUndefined();
    const grew = metricFunnel([50, 60]);
    expect(grew.stages[1].drop).toBe(-10);
    expect(grew.largestDrop).toBeUndefined();
  });

  it('takes the first of equal losses as the largest', () => {
    expect(metricFunnel([100, 50, 25]).largestDrop).toBe(1);
  });

  /**
   * A stage of nothing: everything before it was lost, and after it there
   * is nothing to convert from — the drop is said, the share of nothing
   * is not.
   */
  it('reads a stage of nothing, and the stage after it', () => {
    const data = metricFunnel([80, 0, 0]);
    expect(data.stages[1]).toMatchObject({ value: 0, drop: 80, share: 0 });
    expect(data.stages[1].conversion).toBe(0);
    expect(data.stages[2].drop).toBe(0);
    expect(data.stages[2].conversion).toBeUndefined();
    expect(data.largestDrop).toBe(1);
    // A first stage of nothing: no share of it anywhere.
    const empty = metricFunnel([0, 5]);
    expect(empty.stages.map(stage => stage.share)).toEqual([
      undefined,
      undefined,
    ]);
    expect(empty.stages[1].drop).toBe(-5);
  });
});

describe('drawnStages', () => {
  it('writes both conversions and each drop, signed, to one decimal', () => {
    const stages = drawnStages(ORDERS, context);
    expect(stages[0]).toEqual({
      name: '下单',
      value: 20375,
      text: '20,375',
      conversion: '100.0%',
      share: '100.0%',
    });
    expect(stages[1]).toEqual({
      name: '付款',
      value: 18750,
      text: '18,750',
      conversion: '92.0%',
      share: '92.0%',
      drop: {
        value: 1625,
        text: '−1,625',
        rate: '−8.0%',
        largest: true,
      },
    });
    expect(stages.map(stage => stage.drop && dropText(stage.drop))).toEqual([
      undefined,
      '−1,625 · −8.0%',
      '−433 · −2.3%',
      '−325 · −1.8%',
      '−591 · −3.3%',
    ]);
    expect(stages[4]?.share).toBe('85.4%');
    expect(stages.filter(stage => stage.drop?.largest)).toHaveLength(1);
  });

  it('says a stage that grew, one that held, and one after nothing', () => {
    const stages = drawnStages(
      {
        type: 'funnel',
        stages: [
          { label: 'a', value: 50, conversion: 1, share: 1 },
          { label: 'b', value: 50, conversion: 1, share: 1, drop: 0 },
          { label: 'c', value: 60, conversion: 1.2, share: 1.2, drop: -10 },
          { label: 'd', value: 0, conversion: 0, share: 0, drop: 60 },
          { label: 'e', value: 0, share: 0, drop: 0 },
        ],
      },
      context,
    );
    expect(stages.map(stage => stage.drop && dropText(stage.drop))).toEqual([
      undefined,
      '0 · 0.0%',
      '+10 · +20.0%',
      '−60 · −100.0%',
      // After a stage of nothing there is no share of it to lose.
      '0',
    ]);
    expect(stages[4]?.conversion).toBeUndefined();
    expect(stages.some(stage => stage.drop?.largest)).toBe(false);
  });
});

describe('funnelOption', () => {
  const option = funnelOption(ORDERS, context, theme) as Loose;
  const [series] = option.series;

  /**
   * The library's own funnel (the user's call, 2026-09-25): each stage a
   * trapezoid from its width to the next one's, in the order given, widths
   * from zero, a seam of the ground between stages, one colour.
   */
  it('is the library’s funnel, in the order given, from zero, one colour', () => {
    expect(option.series).toHaveLength(1);
    expect(series).toMatchObject({
      id: 's0',
      type: 'funnel',
      sort: 'none',
      orient: 'vertical',
      min: 0,
      max: 20375,
      minSize: '0%',
      maxSize: '100%',
      gap: 2,
      label: { show: false },
      itemStyle: {
        color: 'rgb(38, 117, 211)',
        borderColor: theme.ground,
        borderWidth: theme.slice.border,
      },
    });
    expect(series.data.slice(0, 5)).toEqual(
      ORDERS.stages.map(stage => ({ name: stage.label, value: stage.value })),
    );
  });

  /**
   * The last stage keeps its own width to its foot: an unseen datum of no
   * length after it, which draws nothing and says nothing.
   */
  it('gives the last stage a foot of its own width', () => {
    const foot = series.data[5];
    expect(series.data).toHaveLength(6);
    expect(foot).toMatchObject({
      value: 17401,
      itemStyle: { color: 'transparent', height: 0, width: 0 },
      tooltip: { show: false },
    });
    expect(option.tooltip.formatter({ dataIndex: 5 })).toBe('');
  });

  it('says a stage’s numbers in its tooltip, the largest drop named', () => {
    const html: string = option.tooltip.formatter({ dataIndex: 1 });
    for (const said of [
      '付款',
      '18,750',
      '−1,625 · −8.0%',
      'Largest drop',
      'From previous',
      'From first',
      '92.0%',
    ])
      expect(html).toContain(said);
    // The first stage has nothing before it to lose from or convert from.
    const first: string = option.tooltip.formatter({ dataIndex: 0 });
    expect(first).not.toContain('Drop');
    expect(first).not.toContain('From previous');
  });

  /**
   * The words are the drawing's own text, made with their colours: inside
   * a stage in the ink that stands off the fill, beside it in the
   * foreground; the drops quiet, the largest in the foreground and heavier.
   */
  it('makes the words with their colours, over the stages, unplaced', () => {
    const elements: Loose[] = option.graphic.elements;
    expect(elements.every(element => element.invisible === true)).toBe(true);
    expect(elements.every(element => element.z > 2)).toBe(true);
    expect(byId(elements, 'stage-in-0').style.fill).toBe(theme.ground);
    expect(byId(elements, 'stage-out-0').style.fill).toBe(theme.foreground);
    expect(byId(elements, 'drop-2').style.fill).toBe(theme.axis.color);
    expect(byId(elements, 'drop-1').style).toMatchObject({
      fill: theme.foreground,
      fontWeight: 600,
    });
    // No drop before the first stage.
    expect(elements.some(element => element.id === 'drop-0')).toBe(false);
  });

  it('lies down when asked', () => {
    const lying = funnelOption(
      ORDERS,
      {
        ...context,
        spec: {
          type: 'funnel',
          funnel: { ...context.spec.funnel, orientation: 'horizontal' },
        },
      },
      theme,
    ) as Loose;
    expect(lying.series[0].orient).toBe('horizontal');
  });
});

describe('funnelFit', () => {
  const stages = drawnStages(ORDERS, context);

  /**
   * Five stages drew 761px tall at 16:9, 121px each (2026-09-25). A stage
   * is never longer than `stageLength` of the chart's text, and the plot
   * is sized to that.
   */
  it('bounds a stage’s length, and sizes the plot to the stages', () => {
    expect(stageLength(12)).toBe(54);
    expect(funnelPlotHeight(5, false)).toBe('17.875rem');
    expect(funnelPlotHeight(2, false)).toBe('7.375rem');
    expect(funnelPlotHeight(5, true)).toBe('11.813rem');
    const tall = standing(stages, 776, 900, text, words);
    for (const stage of tall.stages)
      expect(stage.end - stage.start).toBe(stageLength(text.size));
    // Centred in a plot taller than they need, never stretched to it.
    const first = tall.stages[0]!;
    const last = tall.stages[4]!;
    expect(first.start - 0).toBeCloseTo(900 - last.end, 5);
    // The library shares its box out among the stages and the foot.
    expect(tall.box.height).toBe(6 * 54 + 5 * 2);
    expect(tall.box.top).toBe(first.start);
  });

  it('draws widths from zero against the largest stage, each to the next', () => {
    const { stages: placed, box } = standing(stages, 776, 300, text, words);
    expect(placed[0]!.from).toBeCloseTo(box.width);
    expect(placed[0]!.to).toBeCloseTo((box.width * 18750) / 20375);
    expect(placed[4]!.to).toBeCloseTo(placed[4]!.from);
    expect(placed[4]!.from / placed[0]!.from).toBeCloseTo(17401 / 20375);
  });

  /**
   * The words sit by the stages, not a column away: inside a stage where
   * they fit, and every drop level with its seam, a leader's length from
   * the funnel's widest point.
   */
  it('writes the words inside the stages, and each drop at its seam', () => {
    const layout = standing(stages, 776, 300, text, words);
    expect(layout.stages.map(stage => stage.words)).toEqual(
      Array(5).fill('inside'),
    );
    const elements = layout.elements as Loose[];
    const inside = byId(elements, 'stage-in-1');
    expect(inside.invisible).toBe(false);
    expect(inside.style.text).toBe('付款\n18,750 · 92.0%');
    const stage = layout.stages[1]!;
    expect(inside.y).toBeCloseTo((stage.start + stage.end) / 2);
    const drop = byId(elements, 'drop-1');
    expect(drop.style.text).toBe('−1,625 · −8.0% · Largest drop');
    expect(drop.y).toBeCloseTo(stage.start - 1);
    expect(drop.x - (layout.box.left + layout.box.width)).toBeCloseTo(16);
    // The largest drop's leader runs from its seam to its words; no other.
    expect(byId(elements, 'drop-leader-1').invisible).toBe(false);
    expect(byId(elements, 'drop-leader-2').invisible).toBe(true);
  });

  /**
   * Narrow, the words that do not fit a stage go beside it, with a leader,
   * the name cut before any number is.
   */
  it('puts the words beside a stage too narrow for them, name cut first', () => {
    const long = drawnStages(
      {
        ...ORDERS,
        stages: ORDERS.stages.map((stage, index) =>
          index === 4
            ? { ...stage, label: '交易完成并且已经确认收货的订单' }
            : stage,
        ),
      },
      {
        ...context,
        spec: {
          ...context.spec,
          funnel: {
            stages: {
              from: 'metrics' as const,
              items: ORDERS.stages.map((_stage, index) => ({
                metric: `m${index}`,
              })),
            },
          },
        },
        column: () => undefined,
      },
    );
    const layout = standing(long, 300, 300, text, words);
    expect(layout.stages[4]!.words).toBe('outside');
    const elements = layout.elements as Loose[];
    const beside = byId(elements, 'stage-out-4');
    expect(beside.invisible).toBe(false);
    expect(beside.style.text).toMatch(/… 17,401 · 85\.4%$/);
    expect(byId(elements, 'stage-in-4').invisible).toBe(true);
    expect(byId(elements, 'leader-4').shape.points).toHaveLength(2);
  });

  it('cuts a name, never the numbers', () => {
    expect(fitName('下单', '1', 1000, text)).toBe('下单');
    expect(fitName('一二三四五六', '99', 60, text)).toBe('一二…');
    expect(fitName('一二三', '99', 1, text)).toBe('…');
  });

  /**
   * Lying down, the stages share the width; the drops stand under their
   * seams on up to three lines, and a row for the stages' words is kept
   * only when some stage's words do not fit inside it.
   */
  it('lies down: drops under the seams, a words row only when needed', () => {
    const wide = lyingDown(stages, 776, 189, text, words);
    expect(wide.stages.map(stage => stage.words)).toEqual(
      Array(5).fill('inside'),
    );
    const drop = byId(wide.elements as Loose[], 'drop-1');
    expect(drop.style.text).toBe('−1,625\n−8.0%\nLargest drop');
    expect(drop.x).toBeCloseTo(wide.stages[1]!.start - 1);
    expect(drop.y).toBeCloseTo(wide.box.top + wide.box.height + 4);
    // The foot's room, one stage along, after the last.
    const along = wide.stages[0]!.end - wide.stages[0]!.start;
    expect(wide.box.width).toBeCloseTo(6 * along + 5 * 2);

    const narrow = lyingDown(stages, 358, 189, text, words);
    expect(narrow.stages.some(stage => stage.words === 'outside')).toBe(true);
    const below = byId(narrow.elements as Loose[], 'stage-out-0');
    expect(below.style.text).toBe('下单\n20,375\n100.0%');
    expect(byId(narrow.elements as Loose[], 'drop-1').y).toBeGreaterThan(
      below.y + 17 * 3,
    );
  });

  it('fits nothing to a plot of no size, or a funnel of no stage', () => {
    const fit = funnelFit(ORDERS, context);
    expect(fit(0, 300, undefined, text)).toBeUndefined();
    expect(
      funnelFit({ type: 'funnel', stages: [] }, context)(
        300,
        300,
        undefined,
        text,
      ),
    ).toBeUndefined();
    const placed = fit(776, 300, undefined, text) as Loose;
    expect(placed.series[0]).toEqual(
      standing(stages, 776, 300, text, words).box,
    );
  });
});
