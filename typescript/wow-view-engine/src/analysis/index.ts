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
 * The analysis kernel. The configuration is isomorphic to Wow's aggregation
 * protocol, so compilation is a mapping and the interesting work is admission
 * and the shaping each chart family needs.
 */
export * from './bucketChange.js';
export * from './candidates.js';
export * from './capability.js';
export * from './chart.js';
export * from './chartFamilies.js';
export * from './chartOptions.js';
export * from './chartSlots.js';
export * from './chartSwitch.js';
export * from './compile.js';
export * from './defaults.js';
// By name: `drillSpan` is the follow-up hook's and the board's, read from
// `drill.js` itself, and no part of the root entry.
export {
  bucketRange,
  drillConditions,
  drillGroups,
  focusOn,
  groupFor,
  splitBy,
  wallClockAt,
  type BucketRange,
  type DrillContext,
  type DrilledGroup,
} from './drill.js';
export { drillFilter, narrowsTo } from './drillFilter.js';
export * from './fitCharts.js';
export * from './formula.js';
export * from './expand.js';
export * from './granularity.js';
export * from './having.js';
export * from './metricCondition.js';
// By name: how a derived metric's format is worked out (D38) and which
// metrics read off sums are the kernel's own, read from their files.
export {
  formulaFormat,
  metricFieldOf,
  metricFormat,
  metricFunctionOf,
  metricMeasure,
  metricMeasures,
  momentMetrics,
  readsAsItsField,
  type MetricFunction,
} from './metricFormat.js';
export * from './project.js';
export * from './validate.js';
export {
  isAdditiveMetric,
  isChartColor,
  validateChart,
} from './validateChart.js';
