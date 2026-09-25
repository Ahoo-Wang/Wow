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
  AnalysisViewConfig,
  HierarchySpec,
  RecordData,
  SankeySpec,
} from '../model/index.js';
import { num, seriesKey } from './chartRows.js';
import { forwardInTime, timeGroup } from './timeAxis.js';

/**
 * One part of a hierarchy: a value of its level's dimension, its number —
 * a leaf's own row's, a parent's the sum of its parts — and the group
 * values from the outermost level down to it.
 */
export interface HierarchyNode {
  /** The group value it stands for, on its own level. */
  group: unknown;
  value: number;
  /** Its own and its parents' group values, by alias: what a press drills. */
  path: RecordData;
  /** Its parts, on the next level; left out on the innermost one. */
  children?: HierarchyNode[];
}

export interface HierarchyData {
  type: 'sunburst' | 'tree';
  /** The outermost level's parts, each holding the next. */
  nodes: HierarchyNode[];
  /** How many levels deep: the spec's levels. */
  depth: number;
  /**
   * How many rows have no size to draw: a number that is not above zero,
   * which no part of a whole can be. They stay in the table.
   */
  omitted: number;
}

/**
 * The rows with a size, in the order a level is laid out: largest first,
 * which is how a part-of-whole reads — except over time, which runs
 * forward, as every time axis does.
 */
function sized(
  spec: { levels: readonly string[]; value: string },
  rows: readonly RecordData[],
): { drawn: { row: RecordData; value: number }[]; omitted: number } {
  let omitted = 0;
  const drawn: { row: RecordData; value: number }[] = [];
  for (const row of rows) {
    const value = num(row, spec.value);
    if (value === null || !(value > 0)) omitted += 1;
    else drawn.push({ row, value });
  }
  return { drawn, omitted };
}

/**
 * A sunburst's or a tree's parts: every row a leaf under its parents, one
 * level a dimension, each parent the sum of its parts; each level laid out
 * largest first, a date level earliest first.
 */
export function shapeHierarchy(
  type: 'sunburst' | 'tree',
  spec: HierarchySpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): HierarchyData {
  const { drawn, omitted } = sized(spec, rows);
  type Building = HierarchyNode & { parts?: Map<string, Building> };
  const roots = new Map<string, Building>();
  for (const { row, value } of drawn) {
    let level = roots;
    const path: RecordData = {};
    spec.levels.forEach((alias, depth) => {
      const group = row[alias];
      path[alias] = group;
      const key = seriesKey(group);
      const node: Building = level.get(key) ?? {
        group,
        value: 0,
        path: { ...path },
      };
      node.value += value;
      level.set(key, node);
      if (depth < spec.levels.length - 1) {
        node.parts ??= new Map();
        level = node.parts;
      }
    });
  }
  const ordered = (level: Map<string, Building>, depth: number) => {
    const alias = spec.levels[depth];
    const nodes = [...level.values()];
    const sorted =
      alias !== undefined && timeGroup(config, alias)
        ? forwardInTime(nodes, node => node.group)
        : nodes.sort((a, b) => b.value - a.value);
    return sorted.map(({ parts, ...node }): HierarchyNode =>
      parts ? { ...node, children: ordered(parts, depth + 1) } : node,
    );
  };
  return {
    type,
    nodes: ordered(roots, 0),
    depth: spec.levels.length,
    omitted,
  };
}

/** One node of a sankey: a value of one level's dimension. */
export interface SankeyNode {
  /** Which level, from the left: the index into the spec's levels. */
  level: number;
  /** The group value it stands for. */
  group: unknown;
  /** Its size: the larger of what flows in and what flows out. */
  value: number;
}

/** One band of a sankey, between two nodes of neighbouring levels. */
export interface SankeyLink {
  /** Index into `nodes`: where it flows from. */
  from: number;
  /** Index into `nodes`: where it flows to. */
  to: number;
  value: number;
}

export interface SankeyData {
  type: 'sankey';
  /** Level by level, each level largest first (a date level earliest first). */
  nodes: SankeyNode[];
  /** Largest first. */
  links: SankeyLink[];
  omitted: number;
}

/**
 * A sankey's nodes and bands: every row adds its number to the band between
 * each two neighbouring levels' values; a node is as large as the larger of
 * what flows in and out. A value that appears on two levels is two nodes —
 * a flow runs left to right and never back. Rows not above zero are no
 * band and are counted.
 */
export function shapeSankey(
  spec: SankeySpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): SankeyData {
  const { drawn, omitted } = sized(spec, rows);
  const levels = spec.levels.map(
    () => new Map<string, { group: unknown; into: number; out: number }>(),
  );
  const bands = new Map<
    string,
    { from: string; to: string; level: number; value: number }
  >();
  for (const { row, value } of drawn)
    spec.levels.forEach((alias, level) => {
      const key = seriesKey(row[alias]);
      const node = levels[level].get(key) ?? {
        group: row[alias],
        into: 0,
        out: 0,
      };
      if (level > 0) node.into += value;
      if (level < spec.levels.length - 1) {
        node.out += value;
        const next = seriesKey(row[spec.levels[level + 1]]);
        const band = `${level}:${key.length}:${key}${next}`;
        const entry = bands.get(band) ?? {
          from: key,
          to: next,
          level,
          value: 0,
        };
        entry.value += value;
        bands.set(band, entry);
      }
      levels[level].set(key, node);
    });
  const nodes: SankeyNode[] = [];
  const at = new Map<string, number>();
  levels.forEach((level, index) => {
    const alias = spec.levels[index];
    const entries = [...level.entries()].map(([key, node]) => ({
      key,
      group: node.group,
      value: Math.max(node.into, node.out),
    }));
    const sorted = timeGroup(config, alias)
      ? forwardInTime(entries, entry => entry.group)
      : entries.sort((a, b) => b.value - a.value);
    for (const entry of sorted) {
      at.set(`${index}:${entry.key}`, nodes.length);
      nodes.push({ level: index, group: entry.group, value: entry.value });
    }
  });
  const links = [...bands.values()]
    .map(band => ({
      from: at.get(`${band.level}:${band.from}`)!,
      to: at.get(`${band.level + 1}:${band.to}`)!,
      value: band.value,
    }))
    .sort((a, b) => b.value - a.value);
  return { type: 'sankey', nodes, links, omitted };
}

/**
 * The levels a hierarchy or a flow draws (`fitChartSlots`): the ones the
 * spec lists that are still dimensions, in its order — the analyst's — and
 * every other dimension after them, since a chart consumes every group.
 */
export function chartLevels(
  listed: readonly string[] | undefined,
  groups: readonly string[],
): string[] {
  const kept = [...new Set(listed ?? [])].filter(alias =>
    groups.includes(alias),
  );
  return [...kept, ...groups.filter(alias => !kept.includes(alias))];
}
