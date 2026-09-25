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

import { useCallback } from 'react';
import type { GaugeData } from '../../analysis/index.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { chartNotes } from './ChartNotes.js';
import { EChart } from './EChart.js';
import type { FamilyProps } from './family.js';
import { gaugeOption, reachedShare } from './gaugeOption.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A gauge, drawn by ECharts from `gaugeOption`: one number on a scale, and
 * with a target how much of it is reached. It stands for no group, so
 * nothing on it is pressed; a number off the scale is said over it.
 */
export function Gauge({
  data,
  spec,
  className,
  label,
  name,
}: FamilyProps<GaugeData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const share = reachedShare(data, locale);
  const reached =
    share === undefined
      ? undefined
      : messages.label('label.chart.gauge.reached', { share });
  const option = useCallback(
    (theme: ChartTheme) =>
      gaugeOption(data, { spec, label, locale, animate, reached }, theme),
    [data, spec, label, locale, animate, reached],
  );
  const notes = data.beyond
    ? [messages.label(`label.chart.gauge.${data.beyond}`)]
    : [];
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      chunk="statistics"
      legend={chartNotes(notes, 'gauge-notes')}
      data={{
        'data-chart': 'gauge',
        'data-marks': data.value === null ? 0 : 1,
        ...(data.beyond ? { 'data-beyond': data.beyond } : {}),
      }}
    />
  );
}
