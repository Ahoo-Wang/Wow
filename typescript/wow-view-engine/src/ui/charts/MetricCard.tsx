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

import { Line, LineChart } from 'recharts';
import type { MetricCardData } from '../../analysis/index.js';
import { ChartContainer } from '../components/chart.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { formatValue } from './axis.js';
import type { FamilyProps } from './family.js';
import { color } from './palette.js';

/**
 * The comparison, signed. In `percent` mode the kernel divides, so the delta
 * is a ratio: printing it as it stands turned a quarter more than last week
 * into "+0.25".
 */
function formatDelta(delta: number, mode: 'delta' | 'percent' | undefined) {
  const sign = delta > 0 ? '+' : '';
  return `${sign}${formatValue(delta, mode === 'percent' ? 'percent' : undefined)}`;
}

export function MetricCard({
  data,
  spec,
  className,
  label,
}: FamilyProps<MetricCardData>) {
  const messages = useViewMessages();
  const card = spec?.metric;
  return (
    <div
      data-slot="metric-card"
      className={cn('flex flex-col gap-2', className)}
    >
      <span className="text-3xl font-semibold tabular-nums">
        {data.value === null ? '—' : formatValue(data.value, card?.format)}
      </span>
      {data.compare && (
        <span className="text-muted-foreground text-sm">
          {data.compare.delta === null
            ? '—'
            : formatDelta(data.compare.delta, card?.compare?.mode)}
        </span>
      )}
      {data.target !== undefined && data.value !== null && (
        <div className="bg-muted h-2 w-full overflow-hidden rounded-full">
          <div
            className="bg-primary h-full"
            style={{
              width: `${Math.min(100, (data.value / data.target) * 100)}%`,
            }}
          />
        </div>
      )}
      {data.trend && data.trend.length > 0 && (
        <ChartContainer
          config={{
            trend: {
              label: messages.label('label.chart.trend'),
              color: color(0),
            },
          }}
          className="h-16 w-full"
        >
          <LineChart
            data={data.trend.map(point => ({
              x: label(spec?.metric?.trend?.x, point.x),
              trend: point.value,
            }))}
          >
            <Line dataKey="trend" stroke="var(--color-trend)" dot={false} />
          </LineChart>
        </ChartContainer>
      )}
    </div>
  );
}
