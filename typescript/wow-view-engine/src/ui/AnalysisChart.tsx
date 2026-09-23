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

import { useMemo } from 'react';
import type { AnalysisColumnView, ChartData } from '../analysis/index.js';
import type { ChartSpec } from '../model/index.js';
import { Cartesian } from './charts/Cartesian.js';
import { ChartReadingTable } from './charts/ChartReading.js';
import { useDateTicks } from './charts/dateTicks.js';
import {
  useAdds,
  useColumnTitle,
  useValueLabel,
  type OnPick,
} from './charts/family.js';
import { Funnel } from './charts/Funnel.js';
import { Heatmap } from './charts/Heatmap.js';
import { MetricCard } from './charts/MetricCard.js';
import { PieSlices } from './charts/PieSlices.js';
import { readChart } from './charts/reading.js';
import { ScatterPoints } from './charts/ScatterPoints.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';

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
  /**
   * Makes the marks pressable: a bar, a slice, a cell or a point opens the
   * follow-up menu on the group it stands for. The reading table beside the
   * chart is not: the keyboard's path to the same menu is the table layout,
   * where a row is a row (F10).
   */
  onPick?: OnPick;
  /**
   * The rows are the first groups of more — the result says so beside the
   * chart; a pie also says its shares are of the groups shown.
   */
  cutShort?: boolean;
}

/**
 * Draws what the kernel already shaped.
 *
 * Every pivot, merge, cumulation and conversion happened in `shapeChart`, so
 * `charts/` only picks marks and colours; a different chart library would
 * replace those files without touching a rule. This one dispatches by family
 * and hands each the same props, the category labeller included.
 *
 * It also reads the same projection twice: once as marks, and once as a
 * table nobody looks at. A drawing is one image with a name, so the numbers
 * have to be said somewhere, and saying them off `ChartData` — not off the
 * row projection beside it — is what keeps the two from disagreeing.
 */
export function AnalysisChart({
  data,
  spec,
  className,
  columns,
  onPick,
  cutShort,
}: AnalysisChartProps) {
  const messages = useViewMessages();
  const label = useValueLabel(columns);
  const column = useColumnTitle(columns);
  const adds = useAdds(columns);
  const dateTicks = useDateTicks(columns);
  const { locale } = useSurfaceDisplay();
  const reading = useMemo(
    () => readChart(data, spec, { messages, label, column, locale }),
    [data, spec, messages, label, column, locale],
  );
  const props = {
    spec,
    className,
    label,
    column,
    adds,
    dateTicks,
    name: reading.name,
    onPick,
    cutShort,
  };
  return (
    <>
      {family(data, props)}
      <ChartReadingTable reading={reading} />
    </>
  );
}

/** The one renderer this data asked for. */
function family(
  data: ChartData,
  props: Omit<Parameters<typeof Cartesian>[0], 'data'>,
) {
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
