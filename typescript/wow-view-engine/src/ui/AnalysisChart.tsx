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

import { useCallback, useContext, useMemo, useState } from 'react';
import type { AnalysisColumnView, ChartData } from '../analysis/index.js';
import type { ChartSpec, RecordData } from '../model/index.js';
import { Cartesian } from './charts/Cartesian.js';
import { withoutHidden } from './charts/cartesianPlan.js';
import { ChartMenuOpen, ChartSentence } from './charts/EChart.js';
import { ChartImageTarget, type ChartImageSlot } from './charts/image.js';
import { ChartReadingTable } from './charts/ChartReading.js';
import { useDateTicks } from './charts/dateTicks.js';
import {
  useAdds,
  useFilledNote,
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
import { Treemap } from './charts/Treemap.js';
import { Waterfall } from './charts/Waterfall.js';
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
  /**
   * The group a dashboard panel's press set the board's filter to (D22 I):
   * a bar, a line's point or a slice stands out, the rest drawn faint.
   */
  highlight?: (row: RecordData) => boolean;
  /**
   * The follow-up menu is open over the chart (`DrillMenu`): the drawing's
   * tooltip is put away and stays away until it closes (`ChartMenuOpen`).
   */
  menuOpen?: boolean;
  /**
   * Whether a long time axis also zooms by pinch and Ctrl + wheel, not only
   * by its slider — a workbench, where the chart is the page's subject; a
   * dashboard panel or a read-only embedding leaves the wheel to the page
   * (docs/design/analysis-echarts.md 2.2).
   */
  zoomGestures?: boolean;
  /**
   * Where the drawing hands itself over to be taken away as a picture
   * (`useChartImage`, D33 Q58). A metric card is words, not a drawing, and
   * hands nothing over.
   */
  image?: ChartImageSlot;
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
  highlight,
  menuOpen = false,
  zoomGestures = false,
  image,
}: AnalysisChartProps) {
  const messages = useViewMessages();
  const label = useValueLabel(columns);
  const column = useColumnTitle(columns);
  const adds = useAdds(columns);
  const filled = useFilledNote(columns);
  const dateTicks = useDateTicks(columns);
  const { locale } = useSurfaceDisplay();
  const [hidden, toggle] = useHiddenSeries(data);
  // A panel hands its slot down around the body rather than through it.
  const around = useContext(ChartImageTarget);
  // The reading table says what is drawn: a series switched off in the
  // legend leaves it too.
  const reading = useMemo(
    () =>
      readChart(
        data.type === 'cartesian' ? withoutHidden(data, hidden) : data,
        spec,
        { messages, label, column, locale, filled },
      ),
    [data, hidden, spec, messages, label, column, locale, filled],
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
    highlight,
    filled,
    hidden,
    onToggleSeries: toggle,
    zoomGestures,
  };
  return (
    <ChartMenuOpen.Provider value={menuOpen}>
      <ChartSentence.Provider value={reading.sentence}>
        <ChartImageTarget.Provider
          value={data.type === 'metric' ? null : (image ?? around)}
        >
          {family(data, props)}
        </ChartImageTarget.Provider>
      </ChartSentence.Provider>
      <ChartReadingTable reading={reading} />
    </ChartMenuOpen.Provider>
  );
}

/**
 * The series switched off in the legend, and the switch. Transient like a
 * zoom: never saved with the view and gone with the chart. A new result of
 * the same question keeps the reader's choice for the series it still has
 * — a re-run should not bring back the lines just put away — and a key it
 * lacks switches nothing off.
 */
function useHiddenSeries(
  data: ChartData,
): [ReadonlySet<string> | undefined, (key: string) => void] {
  const [switched, setSwitched] = useState<ReadonlySet<string>>(NONE);
  const hidden = useMemo(() => {
    if (switched.size === 0 || data.type !== 'cartesian') return undefined;
    // A derived line is switched on its own key, as a series is.
    const kept = [...data.series, ...(data.derived ?? [])].filter(entry =>
      switched.has(entry.key),
    );
    return kept.length === 0
      ? undefined
      : new Set(kept.map(entry => entry.key));
  }, [data, switched]);
  const toggle = useCallback(
    (key: string) =>
      setSwitched(previous => {
        const next = new Set(previous);
        if (!next.delete(key)) next.add(key);
        return next;
      }),
    [],
  );
  return [hidden, toggle];
}

const NONE: ReadonlySet<string> = new Set();

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
    case 'waterfall':
      return <Waterfall data={data} {...props} />;
    case 'treemap':
      return <Treemap data={data} {...props} />;
  }
}
