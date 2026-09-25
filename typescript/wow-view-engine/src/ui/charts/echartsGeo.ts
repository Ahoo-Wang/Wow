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

/**
 * The map family's chunk — the map series and its geo component —
 * registered the first time a map is drawn (`loadCharts('geo')`), and the
 * one way a host's geography reaches the library (`registerGeoMap`, called
 * by `loadChartMap`). It carries no map data of its own (D41).
 */
import { MapChart } from 'echarts/charts';
import { GeoComponent } from 'echarts/components';
// `use` registers modules with the library; it is not a React hook.
import { registerMap, use as registerModules } from 'echarts/core';
import type { ChartMapGeoJson } from './maps.js';

export function register(): void {
  registerModules([MapChart, GeoComponent]);
}

/** A host's geography, under the name a chart spec draws it by. */
export function registerGeoMap(name: string, geo: ChartMapGeoJson): void {
  registerMap(name, geo as unknown as Parameters<typeof registerMap>[1]);
}
