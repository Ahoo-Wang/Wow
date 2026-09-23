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
import type { FunnelData } from '../../analysis/index.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { EChart } from './EChart.js';
import { conversionHeading, type FamilyProps } from './family.js';
import { funnelOption } from './funnelOption.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A funnel, drawn by ECharts from `funnelOption` (D21): the centred shape
 * Metabase draws rather than the left-aligned bars it replaced, each stage
 * with its name, value and conversion beside it. What the percentages are
 * relative to is said once, over the drawing, where the heading of their
 * column used to stand (`conversionHeading`) — a bare 「25%」 reads as a
 * share of the whole, which it is only against the first stage.
 */
export function Funnel({
  data,
  spec,
  className,
  label,
  column,
  name,
}: FamilyProps<FunnelData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const converts = data.stages.some(stage => stage.conversion !== undefined);
  const conversion = messages.label(
    conversionHeading(spec?.funnel?.conversion),
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      funnelOption(
        data,
        { spec, label, column, locale, conversion, animate },
        theme,
      ),
    [data, spec, label, column, locale, conversion, animate],
  );
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      legend={
        converts
          ? {
              at: 'top',
              node: (
                <span
                  data-slot="funnel-conversion-heading"
                  className="text-muted-foreground"
                >
                  {conversion}
                </span>
              ),
            }
          : undefined
      }
      data={{
        'data-chart': 'funnel',
        'data-marks': data.stages.length,
        'data-orientation': spec?.funnel?.orientation ?? 'vertical',
      }}
    />
  );
}
