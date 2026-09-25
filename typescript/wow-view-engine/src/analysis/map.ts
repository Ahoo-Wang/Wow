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

import type { MapSpec, RecordData } from '../model/index.js';
import { num } from './chartRows.js';

/** One region of a map: a value of the region dimension and its number. */
export interface MapRegion {
  /** The group value it stands for. */
  group: unknown;
  value: number;
}

export interface MapData {
  type: 'map';
  /** The measured regions, largest first — the order the reading lists them. */
  regions: MapRegion[];
  /** The smallest and the largest measured number: the colour scale's ends. */
  low: number;
  high: number;
  /**
   * How many rows have no number: a region with nothing measured is no
   * shade, and is left out rather than drawn as 0.
   */
  omitted: number;
}

/**
 * A map's regions: each row a region and its number, largest first. Which of
 * them the map has an area for is the map's to say, and the drawing counts
 * the ones it could not place (`drawnRegions` in `/ui`); the scale's ends
 * are measured regions only, never a 0 written in.
 */
export function shapeMap(spec: MapSpec, rows: readonly RecordData[]): MapData {
  const regions: MapRegion[] = [];
  let omitted = 0;
  for (const row of rows) {
    const value = num(row, spec.value);
    if (value === null) omitted += 1;
    else regions.push({ group: row[spec.region], value });
  }
  regions.sort((a, b) => b.value - a.value);
  const values = regions.map(region => region.value);
  return {
    type: 'map',
    regions,
    low: values.length > 0 ? Math.min(...values) : 0,
    high: values.length > 0 ? Math.max(...values) : 0,
    omitted,
  };
}
