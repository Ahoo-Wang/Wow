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
 * Label layout is what hides a value label that would land on another
 * (`labelLayout.hideOverlap`), the reason the library was chosen
 * (docs/design/decisions.md D21).
 */
import {
  BarChart,
  FunnelChart,
  HeatmapChart,
  LineChart,
  PieChart,
  ScatterChart,
} from 'echarts/charts';
import {
  GraphicComponent,
  GridComponent,
  VisualMapContinuousComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components';
// `use` registers modules with the library; it is not a React hook.
import { init, use as register } from 'echarts/core';
import { LabelLayout } from 'echarts/features';
import { SVGRenderer } from 'echarts/renderers';

register([
  BarChart,
  FunnelChart,
  HeatmapChart,
  LineChart,
  PieChart,
  ScatterChart,
  GraphicComponent,
  VisualMapContinuousComponent,
  GridComponent,
  MarkLineComponent,
  TooltipComponent,
  LabelLayout,
  SVGRenderer,
]);

export { init };
