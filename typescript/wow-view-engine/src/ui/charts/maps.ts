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

import { useSyncExternalStore } from 'react';
import { loadCharts } from './load.js';

/**
 * A GeoJSON feature collection, as far as a map chart reads one: each
 * feature's `properties.name` is the region's name, which the region
 * dimension's values — as their column shows them — are matched against.
 */
export interface ChartMapGeoJson {
  type: 'FeatureCollection';
  features: readonly {
    type: 'Feature';
    properties?: { name?: unknown } | null;
    geometry: unknown;
  }[];
}

/**
 * A map a host offers the map chart (D41). This package ships no map data:
 * which borders a map may show, and whether it may be published at all,
 * is the law of where it is shown — a map of China, for one, is published
 * only with an approval number (审图号) — so the geography is the host's to
 * choose and to answer for. It is loaded the first time a chart draws it,
 * never with the page.
 */
export interface ChartMapSource {
  /** What a chart spec names it by (`MapSpec.map`); unique. */
  name: string;
  /** What the options page calls it; the name when left out. */
  label?: string;
  /** The map's geography, fetched on first use and kept. */
  load(): Promise<ChartMapGeoJson>;
}

/** The maps a map chart may draw, and what they are. */
export interface LoadedChartMap {
  name: string;
  /** Every region name the geography has an area for. */
  regions: ReadonlySet<string>;
}

const maps = new Map<string, ChartMapSource>();
const loaded = new Map<string, Promise<LoadedChartMap>>();
const listeners = new Set<() => void>();
let snapshot: readonly ChartMapSource[] = [];

function changed() {
  snapshot = [...maps.values()];
  for (const listener of listeners) listener();
}

/**
 * Offers a map to every map chart on the page, by name; a second one of the
 * same name takes its place. Answers the way to take it back again.
 */
export function registerChartMap(source: ChartMapSource): () => void {
  maps.set(source.name, source);
  loaded.delete(source.name);
  changed();
  return () => {
    if (maps.get(source.name) !== source) return;
    maps.delete(source.name);
    loaded.delete(source.name);
    changed();
  };
}

/** The maps registered, in the order they were. */
export function chartMaps(): readonly ChartMapSource[] {
  return snapshot;
}

/** The maps registered, kept current as a host adds or takes one back. */
export function useChartMaps(): readonly ChartMapSource[] {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    chartMaps,
    chartMaps,
  );
}

/**
 * A registered map, its geography loaded and handed to the library (the map
 * family's chunk, `loadCharts('geo')`) under its name — once; a failed load
 * is asked for again next time.
 */
export function loadChartMap(name: string): Promise<LoadedChartMap> {
  const source = maps.get(name);
  if (!source)
    return Promise.reject(new Error(`No chart map is registered as ${name}.`));
  const known = loaded.get(name);
  if (known) return known;
  const loading = Promise.all([
    source.load(),
    loadCharts('geo'),
    import('./echartsGeo.js'),
  ])
    .then(([geo, , chunk]) => {
      const regions = new Set<string>();
      for (const feature of geo.features ?? []) {
        const region = feature.properties?.name;
        if (typeof region === 'string') regions.add(region);
      }
      // A geography with no named area is nothing to shade — and the
      // library cannot even lay it out — so it is refused as a failed load.
      if (regions.size === 0)
        throw new Error(`The chart map ${name} has no named region.`);
      chunk.registerGeoMap(name, geo);
      return { name, regions };
    })
    .catch((error: unknown) => {
      loaded.delete(name);
      throw error;
    });
  loaded.set(name, loading);
  return loading;
}
