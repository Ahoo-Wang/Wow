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
 * The chart library, registered piece by piece — the one module that imports
 * `echarts`, and the chunk `EChart.tsx` loads on first use rather than with
 * `/ui` (`loadCharts`).
 *
 * `echarts/core` ships nothing drawn; each chart type, component and the
 * renderer is a module of its own, so the bundle carries only what a family
 * asks for. SVG rather than canvas: the marks are elements, which a browser
 * story can measure, jsdom can render, and a colour can be read back from.
 * Label layout moves a line's value labels apart and keeps a pie's from
 * landing on each other (docs/design/decisions.md D21); which bar labels are
 * written is decided by the plot's size (`cartesianFit`). A funnel is bars
 * (`funnelOption`), so the library's funnel is not registered. A long
 * category axis zooms (`zoomOption`: the slider, and the inside zoom where
 * gestures are allowed); the aria component is registered for its decal
 * patterns alone (`withPatterns`) — its generated description stays off.
 */
import {
  BarChart,
  HeatmapChart,
  LineChart,
  PieChart,
  ScatterChart,
} from 'echarts/charts';
import {
  AriaComponent,
  DataZoomInsideComponent,
  DataZoomSliderComponent,
  GraphicComponent,
  GridComponent,
  VisualMapContinuousComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components';
// `use` registers modules with the library; it is not a React hook.
import { init as initChart, use as register } from 'echarts/core';
import { LabelLayout } from 'echarts/features';
import { SVGRenderer } from 'echarts/renderers';

let registered = false;

/**
 * The library's `init`, with every piece this package draws with registered
 * before the first chart is made.
 *
 * The registration lives inside the one export the package calls rather than
 * at the top of the module: the package declares no side effects but its
 * stylesheet (`package.json` `sideEffects`), so a production build keeps this
 * module's exports and drops a top-level call whose result nobody reads — the
 * built chunk carried `init` and no painter, and the first chart threw
 * 「lg[a] is not a constructor」 on the deployed Storybook (2026-09-23).
 * `scripts/verify-package.mjs` draws a chart through the built chunk so the
 * package cannot ship like that again.
 */
export function init(
  ...args: Parameters<typeof initChart>
): ReturnType<typeof initChart> {
  if (!registered) {
    register([
      BarChart,
      HeatmapChart,
      LineChart,
      PieChart,
      ScatterChart,
      AriaComponent,
      DataZoomInsideComponent,
      DataZoomSliderComponent,
      GraphicComponent,
      VisualMapContinuousComponent,
      GridComponent,
      MarkLineComponent,
      TooltipComponent,
      LabelLayout,
      SVGRenderer,
    ]);
    registered = true;
  }
  return initChart(...args);
}
