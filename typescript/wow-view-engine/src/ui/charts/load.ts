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

import type * as Library from './echarts.js';

/**
 * The chart chunk, loaded once and kept.
 *
 * `/ui` does not import the chart library: a record view never draws one,
 * and the library is the largest thing the package would otherwise carry.
 * The first chart asks for it; every later one finds it here already and
 * draws in the same render (`loaded`), so a view switched from table to
 * chart does not blink through an empty frame twice.
 *
 * A failed load is not kept: the render boundary around the chart shows its
 * reason, and its retry asks again rather than being handed the same
 * rejection.
 */
export type ChartLibrary = typeof Library;

/**
 * The families registered on demand, each a chunk of its own
 * (analysis-echarts.md §5): the library's modules for a boxplot, a radar or
 * a map are no part of the first chart's cost — a bar chart never waits
 * for them — and only the first chart of such a family loads them.
 */
export type ChartChunk = 'statistics' | 'hierarchy' | 'time';

const CHUNKS: Record<ChartChunk, () => Promise<{ register(): void }>> = {
  statistics: () => import('./echartsStatistics.js'),
  hierarchy: () => import('./echartsHierarchy.js'),
  time: () => import('./echartsTime.js'),
};

let loaded: ChartLibrary | undefined;
let loading: Promise<ChartLibrary> | undefined;
const registered = new Set<ChartChunk>();
const registering = new Map<ChartChunk, Promise<void>>();

/**
 * The library once it has arrived — and, given a `chunk`, once that
 * family's modules are registered too; `undefined` until then.
 */
export function loadedCharts(chunk?: ChartChunk): ChartLibrary | undefined {
  return chunk === undefined || registered.has(chunk) ? loaded : undefined;
}

export function loadCharts(chunk?: ChartChunk): Promise<ChartLibrary> {
  loading ??= import('./echarts.js').then(
    library => (loaded = library),
    (error: unknown) => {
      loading = undefined;
      throw error;
    },
  );
  if (chunk === undefined) return loading;
  const family = registering.get(chunk) ?? loadChunk(chunk);
  registering.set(chunk, family);
  return Promise.all([loading, family]).then(([library]) => library);
}

/** A family's chunk, registered once; a failed load is asked for again. */
function loadChunk(chunk: ChartChunk): Promise<void> {
  return CHUNKS[chunk]().then(
    ({ register }) => {
      register();
      registered.add(chunk);
    },
    (error: unknown) => {
      registering.delete(chunk);
      throw error;
    },
  );
}
