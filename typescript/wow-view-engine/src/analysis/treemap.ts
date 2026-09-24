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

import type { RecordData, TreemapSpec } from '../model/index.js';
import { num, seriesKey } from './chartRows.js';

/**
 * One tile: a group value and its number. An outer tile — a value of the
 * spec's `parent` — holds the tiles of its own rows, and its number is
 * theirs added up.
 */
export interface TreemapTile {
  /** The group value the tile stands for. */
  group: unknown;
  value: number;
  /** The tiles inside an outer one; left out on the innermost level. */
  tiles?: TreemapTile[];
}

export interface TreemapData {
  type: 'treemap';
  /** The outer level, largest first; each holds its tiles, largest first. */
  tiles: TreemapTile[];
  /** Whether the tiles nest: two dimensions, `parent` outside `category`. */
  nested: boolean;
  /**
   * How many rows have no area to draw: a number that is not above zero —
   * a sum that came out negative, a group with nothing — which no tile can
   * be the size of. They stay in the reading table and in the table, and a
   * drawing says how many it left out rather than lying about the whole.
   */
  omitted: number;
}

/**
 * A treemap's tiles: one per row, sized by its number; with a `parent`,
 * gathered into one outer tile per parent value in the order the values
 * first come. Every level is laid out largest first, which is how a tiling
 * reads (the biggest in the corner the eye starts at) and the order the
 * reading table lists them in, so the two agree.
 */
export function shapeTreemap(
  spec: TreemapSpec,
  rows: readonly RecordData[],
): TreemapData {
  let omitted = 0;
  const drawn: { row: RecordData; value: number }[] = [];
  for (const row of rows) {
    const value = num(row, spec.value);
    if (value === null || !(value > 0)) omitted += 1;
    else drawn.push({ row, value });
  }
  const largestFirst = (a: TreemapTile, b: TreemapTile) => b.value - a.value;
  const leaf = ({ row, value }: (typeof drawn)[number]): TreemapTile => ({
    group: row[spec.category],
    value,
  });
  const parent = spec.parent;
  if (parent === undefined)
    return {
      type: 'treemap',
      tiles: drawn.map(leaf).sort(largestFirst),
      nested: false,
      omitted,
    };
  // Keyed by the one spelling that keeps apart what the query kept apart.
  const blocks = new Map<string, TreemapTile & { tiles: TreemapTile[] }>();
  for (const entry of drawn) {
    const outer = entry.row[parent];
    const key = seriesKey(outer);
    const block = blocks.get(key) ?? { group: outer, value: 0, tiles: [] };
    block.value += entry.value;
    block.tiles.push(leaf(entry));
    blocks.set(key, block);
  }
  return {
    type: 'treemap',
    tiles: [...blocks.values()]
      .map(block => ({ ...block, tiles: block.tiles.sort(largestFirst) }))
      .sort(largestFirst),
    nested: true,
    omitted,
  };
}
