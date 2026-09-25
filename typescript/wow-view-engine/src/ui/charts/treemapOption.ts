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
import type { TreemapData, TreemapTile } from '../../analysis/index.js';
import { CHART_COLOR_SLOTS, type ChartSpec } from '../../model/index.js';
import type { RecordData } from '../../model/index.js';
import { formatShare } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { color } from './palette.js';
import { emphasized, inkOn, mixColor, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a treemap reads besides its tiles. */
export interface TreemapContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
}

/**
 * How far the smallest tile of a level fades toward the ground when the
 * tiles share one hue: shaded by rank, the biggest in the full colour.
 */
export const PALEST = 0.4;

/** One drawn tile, as the tooltip, the press and the reading take it. */
export interface DrawnTile {
  /** The group values it stands for: its own, and its outer tile's. */
  row: RecordData;
  /** Its name, and its outer tile's first where it has one. */
  name: string;
  path: string;
  value: number;
  /** Its number as its column reads it, whole. */
  text: string;
  /** Its share of the whole drawn. */
  share: string;
}

/**
 * Every innermost tile, in the order drawn: each with the groups it stands
 * for, its name, its number and its share of the whole. Its id in the
 * option is its place here, which is how a press is read back.
 */
export function drawnTiles(
  data: TreemapData,
  { spec, label, locale }: Pick<TreemapContext, 'spec' | 'label' | 'locale'>,
): DrawnTile[] {
  const treemap = spec?.treemap;
  const whole = data.tiles.reduce((sum, tile) => sum + tile.value, 0);
  const leaf = (tile: TreemapTile, outer?: TreemapTile): DrawnTile => {
    const name = label(treemap?.category, tile.group);
    const parent =
      outer === undefined ? undefined : label(treemap?.parent, outer.group);
    return {
      row: {
        ...(treemap ? { [treemap.category]: tile.group } : {}),
        ...(outer && treemap?.parent !== undefined
          ? { [treemap.parent]: outer.group }
          : {}),
      },
      name,
      path: parent === undefined ? name : `${parent} · ${name}`,
      value: tile.value,
      text: label(treemap?.value, tile.value),
      share: formatShare(whole > 0 ? tile.value / whole : 0, locale),
    };
  };
  return data.nested
    ? data.tiles.flatMap(outer =>
        (outer.tiles ?? []).map(tile => leaf(tile, outer)),
      )
    : data.tiles.map(tile => leaf(tile));
}

/**
 * The colours of one level's tiles. Up to the palette's size each takes a
 * slot of its own, in order; past it a slot handed out twice would say two
 * tiles are one thing, so the level wears the first slot alone, shaded
 * toward the ground by rank (largest first) — a treemap is how a result of
 * more categories than colours is read (D33 Q56), and every tile carries
 * its own name, so the colour need not.
 */
export function levelColors(theme: ChartTheme, count: number): string[] {
  if (count <= CHART_COLOR_SLOTS)
    return Array.from({ length: count }, (_, index) =>
      theme.resolve(color(index)),
    );
  const fill = theme.resolve(color(0));
  return Array.from({ length: count }, (_, index) =>
    mixColor(theme.ground, fill, 1 - (index / (count - 1)) * (1 - PALEST)),
  );
}

/**
 * A treemap as the library draws it: the whole cut into tiles by area,
 * each named on itself with its number under the name, the ground's colour
 * between them. Two levels nest the inner tiles inside a block per outer
 * value, headed by that value's name; an inner tile wears its block's
 * colour, shaded toward the ground by rank within it. The library's own
 * zoom, pan and breadcrumb are off: a press opens the follow-up menu on
 * the tile, as a press on a bar does, and the result never moves under
 * the reader.
 */
export function treemapOption(
  data: TreemapData,
  context: TreemapContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight } = context;
  const tiles = drawnTiles(data, context);
  const lit = (row: RecordData) => !highlight || highlight(row);
  const anyLit = highlight ? tiles.some(tile => highlight(tile.row)) : false;
  // What each tile is painted, by its place, for its tooltip's swatch.
  const fills: string[] = [];
  let at = 0;
  const node = (tile: TreemapTile, fill: string) => {
    const drawn = tiles[at];
    const id = `t${at}`;
    fills[at] = fill;
    at += 1;
    return {
      id,
      name: drawn?.name ?? '',
      value: tile.value,
      itemStyle: {
        color: fill,
        ...(anyLit && drawn && !lit(drawn.row)
          ? { opacity: FADED_OPACITY }
          : {}),
      },
      label: { color: inkOn(theme, fill) },
      emphasis: { itemStyle: { color: emphasized(theme, fill) } },
    };
  };
  const outer = levelColors(theme, data.tiles.length);
  const nodes = data.nested
    ? data.tiles.map((block, index) => {
        const fill = outer[index] ?? theme.resolve(color(0));
        const inner = block.tiles ?? [];
        return {
          id: `p${index}`,
          name: label(spec?.treemap?.parent, block.group),
          value: block.value,
          itemStyle: { color: fill, borderColor: fill },
          upperLabel: { color: inkOn(theme, fill) },
          children: inner.map((tile, rank) =>
            node(
              tile,
              inner.length > 1
                ? mixColor(
                    theme.ground,
                    fill,
                    1 - (rank / (inner.length - 1)) * (1 - PALEST),
                  )
                : fill,
            ),
          ),
        };
      })
    : data.tiles.map((tile, index) =>
        node(tile, outer[index] ?? theme.resolve(color(0))),
      );
  const measured = column(spec?.treemap?.value) ?? '';
  /** Which innermost tile a datum is (`t{n}`); an outer block is none. */
  const tileOf = (datum: { id?: string } | undefined) =>
    datum?.id?.startsWith('t') ? Number(datum.id.slice(1)) : undefined;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ data: datum }: { data?: { id?: string } }) => {
        const place = tileOf(datum);
        const tile = place === undefined ? undefined : tiles[place];
        if (!tile || place === undefined) return '';
        return tooltipHtml(tile.path, [
          {
            color: fills[place] ?? 'transparent',
            name: measured,
            value: `${tile.text} · ${tile.share}`,
          },
        ]);
      },
    },
    series: [
      {
        type: 'treemap',
        cursor: pickable ? 'pointer' : 'default',
        left: 0,
        top: 0,
        right: 0,
        bottom: 0,
        roam: false,
        nodeClick: false,
        breadcrumb: { show: false },
        // Largest first, as the kernel ordered them.
        sort: false,
        data: nodes,
        label: {
          show: true,
          position: 'insideTopLeft',
          fontSize: 12,
          overflow: 'truncate',
          formatter: ({ data: datum }: { data?: { id?: string } }) => {
            const place = tileOf(datum);
            const tile = place === undefined ? undefined : tiles[place];
            return tile
              ? `{name|${tile.name}}\n{value|${label(spec?.treemap?.value, tile.value, true)}}`
              : '';
          },
          rich: {
            name: { fontSize: 12, lineHeight: 16 },
            value: { fontSize: 11, lineHeight: 15, opacity: 0.85 },
          },
        },
        upperLabel: {
          show: data.nested,
          // Its own words: left to itself it takes the tiles' formatter,
          // which names tiles only, and a block's header stood empty.
          formatter: '{b}',
          height: 20,
          fontSize: 12,
          fontWeight: 500,
          overflow: 'truncate',
        },
        itemStyle: { borderColor: theme.ground, borderWidth: 1, gapWidth: 2 },
        levels: data.nested
          ? [
              { itemStyle: { borderWidth: 0, gapWidth: 3 } },
              { itemStyle: { borderWidth: 2, gapWidth: 1 } },
              { itemStyle: { borderColor: theme.ground, borderWidth: 1 } },
            ]
          : [],
      },
    ],
  };
}
