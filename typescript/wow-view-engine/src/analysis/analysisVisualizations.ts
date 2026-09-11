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

/** Shared built-in capabilities for selection, settings, validation and rendering. */
export const ANALYSIS_VISUALIZATIONS = [
  {
    value: 'table',
    label: '数据表',
    axes: false,
    series: false,
    continuous: false,
    orientation: false,
    stacked: false,
    donut: false,
  },
  {
    value: 'metric',
    label: '指标卡',
    axes: false,
    series: false,
    continuous: false,
    orientation: false,
    stacked: false,
    donut: false,
  },
  {
    value: 'bar',
    label: '柱状图',
    axes: true,
    series: true,
    continuous: false,
    orientation: true,
    stacked: true,
    donut: false,
  },
  {
    value: 'line',
    label: '折线图',
    axes: true,
    series: true,
    continuous: true,
    orientation: false,
    stacked: false,
    donut: false,
  },
  {
    value: 'area',
    label: '面积图',
    axes: true,
    series: true,
    continuous: true,
    orientation: false,
    stacked: true,
    donut: false,
  },
  {
    value: 'pie',
    label: '饼图',
    axes: true,
    series: false,
    continuous: false,
    orientation: false,
    stacked: false,
    donut: true,
  },
] as const;
export type AnalysisVisualizationType =
  (typeof ANALYSIS_VISUALIZATIONS)[number]['value'];
export function getAnalysisVisualization(value: unknown) {
  return ANALYSIS_VISUALIZATIONS.find(item => item.value === value);
}
