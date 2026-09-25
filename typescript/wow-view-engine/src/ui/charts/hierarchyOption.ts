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
import type {
  HierarchyData,
  HierarchyNode,
  SankeyData,
} from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { formatShare } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { color } from './palette.js';
import { emphasized, inkOn, mixColor, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';
import { levelColors, PALEST } from './treemapOption.js';

/** What a hierarchy or a sankey reads besides its data. */
export interface HierarchyContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
}

/** One drawn part: where it sits, what it is called, and its share. */
export interface DrawnPart {
  /** The group values from the outermost level down to it. */
  row: RecordData;
  /** Its own level's value, as its column reads it. */
  name: string;
  /** Every level's value down to it, joined: 「家居 · 浴巾」. */
  path: string;
  value: number;
  /** Its number as its column reads it, whole. */
  text: string;
  /** Its share of the whole drawn. */
  share: string;
  /** How deep, from 0; a leaf is at `depth - 1`. */
  level: number;
  leaf: boolean;
}

/** The levels of a sunburst or a tree, by the spec of its own type. */
function levelsOf(data: HierarchyData, spec: ChartSpec | undefined) {
  return (data.type === 'sunburst' ? spec?.sunburst : spec?.tree)?.levels ?? [];
}

/**
 * Every part of a sunburst or a tree, parents before their parts, in the
 * order drawn: each with its path, its number and its share of the whole.
 * Its id in the option is its place here (`h{n}`), which is how a press
 * and a tooltip are read back.
 */
export function drawnParts(
  data: HierarchyData,
  { spec, label, locale }: Pick<HierarchyContext, 'spec' | 'label' | 'locale'>,
): DrawnPart[] {
  const levels = levelsOf(data, spec);
  const value = (data.type === 'sunburst' ? spec?.sunburst : spec?.tree)?.value;
  const whole = data.nodes.reduce((sum, node) => sum + node.value, 0);
  const parts: DrawnPart[] = [];
  const walk = (node: HierarchyNode, level: number, above: string[]) => {
    const name = label(levels[level], node.group);
    const names = [...above, name];
    parts.push({
      row: node.path,
      name,
      path: names.join(' · '),
      value: node.value,
      text: label(value, node.value),
      share: formatShare(whole > 0 ? node.value / whole : 0, locale),
      level,
      leaf: node.children === undefined,
    });
    for (const child of node.children ?? []) walk(child, level + 1, names);
  };
  for (const node of data.nodes) walk(node, 0, []);
  return parts;
}

/** The part a datum is (`h{n}`), by its place in `drawnParts`. */
export function partOf(
  datum: { id?: unknown } | undefined,
): number | undefined {
  return typeof datum?.id === 'string' && datum.id.startsWith('h')
    ? Number(datum.id.slice(1))
    : undefined;
}

/**
 * The option's nodes: the first level a slot each (one hue shaded by size
 * past the palette, `levelColors`), each deeper part its parent's colour
 * shaded toward the ground by rank among its siblings, as a treemap nests.
 */
function nodesOf(
  data: HierarchyData,
  parts: readonly DrawnPart[],
  theme: ChartTheme,
  lit: (row: RecordData) => boolean,
  anyLit: boolean,
  /**
   * Whether a part's name is written on the part (a sunburst's arc), in
   * the ink that stands off its fill; beside it (a tree's node), the
   * series' own ink on the ground is the one to read.
   */
  onMark: boolean,
) {
  let at = 0;
  const node = (entry: HierarchyNode, fill: string): object => {
    const part = parts[at];
    const id = `h${at}`;
    at += 1;
    const children = entry.children ?? [];
    const faint = anyLit && part.leaf && !lit(part.row);
    return {
      id,
      name: part.name,
      value: entry.value,
      itemStyle: {
        color: fill,
        ...(faint ? { opacity: FADED_OPACITY } : {}),
      },
      ...(onMark ? { label: { color: inkOn(theme, fill) } } : {}),
      emphasis: { itemStyle: { color: emphasized(theme, fill) } },
      ...(children.length > 0
        ? {
            children: children.map((child, rank) =>
              node(
                child,
                children.length > 1
                  ? mixColor(
                      theme.ground,
                      fill,
                      1 - (rank / (children.length - 1)) * (1 - PALEST),
                    )
                  : fill,
              ),
            ),
          }
        : {}),
    };
  };
  const first = levelColors(theme, data.nodes.length);
  return data.nodes.map((entry, index) =>
    node(entry, first[index] ?? theme.resolve(color(0))),
  );
}

/** A part's tooltip: its path, its number and its share of the whole. */
function partTooltip(
  parts: readonly DrawnPart[],
  place: number | undefined,
  measured: string,
  fill: (place: number) => string,
): string {
  const part = place === undefined ? undefined : parts[place];
  if (!part || place === undefined) return '';
  return tooltipHtml(part.path, [
    {
      color: fill(place),
      name: measured,
      value: `${part.text} · ${part.share}`,
    },
  ]);
}

/**
 * A sunburst as the library draws it: the first level the inner ring, each
 * deeper level a ring outside its parent, every arc as long as its share;
 * names on the arcs that have room. The library's own zoom into a ring is
 * off: a press on an innermost arc opens the follow-up menu on its group,
 * as a treemap's tile does; an inner arc stands for several groups and is
 * not pressed.
 */
export function sunburstOption(
  data: HierarchyData,
  context: HierarchyContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, column, animate, pickable, highlight } = context;
  const parts = drawnParts(data, context);
  const lit = (row: RecordData) => !highlight || highlight(row);
  const anyLit = highlight
    ? parts.some(part => part.leaf && highlight(part.row))
    : false;
  const nodes = nodesOf(data, parts, theme, lit, anyLit, true);
  const fills = flatFills(nodes);
  const measured = column(spec?.sunburst?.value) ?? '';
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ data: datum }: { data?: { id?: string } }) =>
        partTooltip(parts, partOf(datum), measured, at => fills[at] ?? ''),
    },
    series: [
      {
        type: 'sunburst',
        cursor: pickable ? 'pointer' : 'default',
        nodeClick: false,
        sort: undefined,
        radius: ['12%', '92%'],
        data: nodes,
        itemStyle: { borderColor: theme.ground, borderWidth: 1 },
        label: {
          rotate: 'radial',
          fontSize: 11,
          minAngle: 8,
          overflow: 'truncate',
        },
        emphasis: { focus: 'ancestor' },
      },
    ],
  };
}

/**
 * A tree as the library draws it: the whole broken down left to right,
 * a level a column, every part a node named with its number, the parts of
 * one parent together. Every branch is open and stays open — the library's
 * collapse on a press is off, since a press opens the follow-up menu.
 */
export function treeOption(
  data: HierarchyData,
  context: HierarchyContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight } = context;
  const parts = drawnParts(data, context);
  const lit = (row: RecordData) => !highlight || highlight(row);
  const anyLit = highlight
    ? parts.some(part => part.leaf && highlight(part.row))
    : false;
  const nodes = nodesOf(data, parts, theme, lit, anyLit, false);
  const fills = flatFills(nodes);
  const measured = column(spec?.tree?.value) ?? '';
  const value = spec?.tree?.value;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ data: datum }: { data?: { id?: string } }) =>
        partTooltip(parts, partOf(datum), measured, at => fills[at] ?? ''),
    },
    series: [
      {
        type: 'tree',
        cursor: pickable ? 'pointer' : 'default',
        orient: 'LR',
        left: 16,
        right: 160,
        top: 16,
        bottom: 16,
        roam: false,
        expandAndCollapse: false,
        initialTreeDepth: -1,
        symbolSize: 9,
        // One root the library needs, drawn as nothing: the whole is not a
        // group, and the first level's parts stand in the first column.
        data: [
          {
            name: '',
            value: data.nodes.reduce((sum, node) => sum + node.value, 0),
            itemStyle: { opacity: 0 },
            label: { show: false },
            children: nodes,
          },
        ],
        lineStyle: { color: theme.border, width: 1.5, curveness: 0.5 },
        label: {
          position: 'left',
          verticalAlign: 'middle',
          align: 'right',
          fontSize: 12,
          color: theme.foreground,
          formatter: ({ data: datum }: { data?: { id?: string } }) => {
            const place = partOf(datum);
            const part = place === undefined ? undefined : parts[place];
            return part
              ? `${part.name}  ${label(value, part.value, true)}`
              : '';
          },
        },
        leaves: {
          label: { position: 'right', verticalAlign: 'middle', align: 'left' },
        },
        emphasis: { focus: 'descendant' },
      },
    ],
  };
}

/** Each option node's fill, by its place, in the order drawn. */
function flatFills(nodes: readonly object[]): string[] {
  const fills: string[] = [];
  const walk = (node: {
    itemStyle?: { color?: string };
    children?: object[];
  }) => {
    fills.push(node.itemStyle?.color ?? '');
    for (const child of node.children ?? []) walk(child);
  };
  for (const node of nodes) walk(node);
  return fills;
}

/** One drawn band of a sankey, as the tooltip, the press and the reading take it. */
export interface DrawnBand {
  /** Both ends' group values, by alias. */
  row: RecordData;
  from: string;
  to: string;
  value: number;
  text: string;
}

/** The names of a sankey's nodes and its bands, in the kernel's order. */
export function drawnFlow(
  data: SankeyData,
  { spec, label }: Pick<HierarchyContext, 'spec' | 'label'>,
): { nodes: string[]; bands: DrawnBand[] } {
  const levels = spec?.sankey?.levels ?? [];
  const nodes = data.nodes.map(node => label(levels[node.level], node.group));
  const bands = data.links.map(link => {
    const from = data.nodes[link.from];
    const to = data.nodes[link.to];
    const fromAlias = levels[from.level];
    const toAlias = levels[to.level];
    return {
      row: {
        ...(fromAlias === undefined ? {} : { [fromAlias]: from.group }),
        ...(toAlias === undefined ? {} : { [toAlias]: to.group }),
      },
      from: nodes[link.from],
      to: nodes[link.to],
      value: link.value,
      text: label(spec?.sankey?.value, link.value),
    };
  });
  return { nodes, bands };
}

/** How opaque a band is: the nodes read, and the bands under each other. */
const BAND = 0.4;

/**
 * A sankey as the library draws it: a column per level, left to right, a
 * node per value as tall as what passes through it, a band per pair of
 * neighbouring values as wide as its number, in its source's colour. The
 * first level's nodes take a slot each up to the palette's size and one hue
 * by size past it; later levels are grey. Only a band between the two levels of a two-level sankey
 * is one group of the result, so only that is pressed; a node, or a band of
 * a longer flow, stands for several.
 */
export function sankeyOption(
  data: SankeyData,
  context: HierarchyContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight } = context;
  const { nodes: names, bands } = drawnFlow(data, context);
  const levels = spec?.sankey?.levels ?? [];
  const pressable = pickable && levels.length === 2;
  const byLevel = new Map<number, number[]>();
  data.nodes.forEach((node, index) =>
    byLevel.set(node.level, [...(byLevel.get(node.level) ?? []), index]),
  );
  // The first level wears the palette and its bands carry it on; a later
  // level is a neutral grey — a slot handed to 「微信支付」 after 「App」
  // wore it would say the two are one thing.
  const neutral = mixColor(theme.ground, theme.muted, 0.7);
  const fills: string[] = [];
  for (const [level, indices] of byLevel) {
    const colors = levelColors(theme, indices.length);
    indices.forEach(
      (index, rank) => (fills[index] = level === 0 ? colors[rank] : neutral),
    );
  }
  const anyLit = highlight ? bands.some(band => highlight(band.row)) : false;
  const measured = column(spec?.sankey?.value) ?? '';
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({
        dataType,
        dataIndex,
      }: {
        dataType?: string;
        dataIndex: number;
      }) => {
        if (dataType === 'edge') {
          const band = bands[dataIndex];
          return band
            ? tooltipHtml(`${band.from} → ${band.to}`, [
                {
                  color: fills[data.links[dataIndex].from] ?? '',
                  name: measured,
                  value: band.text,
                },
              ])
            : '';
        }
        const node = data.nodes[dataIndex];
        return node
          ? tooltipHtml(names[dataIndex] ?? '', [
              {
                color: fills[dataIndex] ?? '',
                name: column(levels[node.level]) ?? measured,
                value: label(spec?.sankey?.value, node.value),
              },
            ])
          : '';
      },
    },
    series: [
      {
        type: 'sankey',
        cursor: pressable ? 'pointer' : 'default',
        left: 8,
        right: 120,
        top: 16,
        bottom: 16,
        nodeGap: 10,
        nodeWidth: 14,
        draggable: false,
        layoutIterations: 0,
        data: data.nodes.map((node, index) => ({
          // A value on two levels is two nodes: the name is the place.
          name: `n${index}`,
          value: node.value,
          itemStyle: { color: fills[index], borderWidth: 0 },
        })),
        links: data.links.map((link, index) => ({
          source: `n${link.from}`,
          target: `n${link.to}`,
          value: link.value,
          lineStyle: {
            color: fills[link.from],
            opacity:
              anyLit && !highlight?.(bands[index].row)
                ? BAND * FADED_OPACITY
                : BAND,
          },
        })),
        label: {
          color: theme.foreground,
          fontSize: 12,
          formatter: ({ dataIndex }: { dataIndex: number }) =>
            `${names[dataIndex] ?? ''}  ${label(
              spec?.sankey?.value,
              data.nodes[dataIndex]?.value,
              true,
            )}`,
        },
        lineStyle: { curveness: 0.5 },
        emphasis: { focus: 'adjacency', lineStyle: { opacity: 0.6 } },
      },
    ],
  };
}
