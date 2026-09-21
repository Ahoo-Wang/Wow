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

import type { AnalysisColumnView, ChartData } from '../analysis/index.js';
import type { ChartSpec } from '../model/index.js';
import { Cartesian } from './charts/Cartesian.js';
import { useCategoryLabel } from './charts/family.js';
import { Funnel } from './charts/Funnel.js';
import { Heatmap } from './charts/Heatmap.js';
import { MetricCard } from './charts/MetricCard.js';
import { PieSlices } from './charts/PieSlices.js';
import { ScatterPoints } from './charts/ScatterPoints.js';

export interface AnalysisChartProps {
  data: ChartData;
  /** The saved spec; only `combo` and a few axis options still need it. */
  spec?: ChartSpec;
  className?: string;
  /**
   * The result's columns, so a category shows as its field's values do: an
   * enum by its label, a date bucket as its day or its month.
   */
  columns?: readonly AnalysisColumnView[];
}

/**
 * Draws what the kernel already shaped.
 *
 * Every pivot, merge, cumulation and conversion happened in `shapeChart`, so
 * `charts/` only picks marks and colours; a different chart library would
 * replace those files without touching a rule. This one dispatches by family
 * and hands each the same props, the category labeller included.
 */
export function AnalysisChart({
  data,
  spec,
  className,
  columns,
}: AnalysisChartProps) {
  const label = useCategoryLabel(columns);
  const props = { spec, className, label };
  switch (data.type) {
    case 'cartesian':
      return <Cartesian data={data} {...props} />;
    case 'pie':
      return <PieSlices data={data} {...props} />;
    case 'heatmap':
      return <Heatmap data={data} {...props} />;
    case 'scatter':
      return <ScatterPoints data={data} {...props} />;
    case 'funnel':
      return <Funnel data={data} {...props} />;
    case 'metric':
      return <MetricCard data={data} {...props} />;
  }
}
