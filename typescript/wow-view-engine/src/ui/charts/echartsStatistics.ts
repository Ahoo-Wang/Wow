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
 * The statistical families' chunk — a boxplot, a gauge, a radar and
 * parallel axes — registered with the library the first time one of them
 * is drawn (`loadCharts('statistics')`), so a bar chart never pays for
 * them. Registered inside the one export `load.ts` calls, for the reason
 * `echarts.ts` gives: a top-level call nobody reads is dropped by a build.
 */
import {
  BoxplotChart,
  GaugeChart,
  ParallelChart,
  RadarChart,
} from 'echarts/charts';
import { ParallelComponent, RadarComponent } from 'echarts/components';
// `use` registers modules with the library; it is not a React hook.
import { use as registerModules } from 'echarts/core';

export function register(): void {
  registerModules([
    BoxplotChart,
    GaugeChart,
    ParallelChart,
    RadarChart,
    ParallelComponent,
    RadarComponent,
  ]);
}
