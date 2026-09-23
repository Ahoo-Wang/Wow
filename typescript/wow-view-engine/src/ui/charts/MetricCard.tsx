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
import { useChartMotion } from './motion.js';
import type { MetricCardData } from '../../analysis/index.js';
import { ChartContainer } from '../components/chart.js';
import { Progress } from '../components/progress.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { asImage } from './asImage.js';
import { formatValue } from './axis.js';
import type { FamilyProps } from './family.js';
import { color } from './palette.js';

/**
 * The comparison, signed. In `percent` mode the kernel divides, so the delta
 * is a ratio: printing it as it stands turned a quarter more than last week
 * into "+0.25".
 */
function formatDelta(
  delta: number,
  mode: 'delta' | 'percent' | undefined,
  locale: string | undefined,
) {
  const sign = delta > 0 ? '+' : '';
  return `${sign}${formatValue(delta, mode === 'percent' ? 'percent' : undefined, locale)}`;
}

/**
 * How much of the target the value has reached, out of a hundred.
 *
 * A saved target is whatever a configuration put there, zero included, and a
 * bar has to be drawable from any of them: nothing to fall short of is either
 * reached or not, and past the target the bar is full rather than longer than
 * itself.
 */
function reached(value: number, target: number): number {
  if (target === 0) return value === 0 ? 0 : 100;
  return Math.max(0, Math.min(100, (value / target) * 100));
}

export function MetricCard({
  data,
  spec,
  className,
  label,
  name,
}: FamilyProps<MetricCardData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const card = spec?.metric;
  /**
   * The headline number as its own column reads it, so a card of money says
   * ¥10,230.00 where the table under it says the same. `MetricCardSpec.format`
   * still wins: it is an instruction about this card.
   */
  const show = (value: number | string) =>
    card?.format === undefined || typeof value === 'string'
      ? label(card?.metric, value)
      : formatValue(value, card.format, locale);
  return (
    <div
      data-slot="metric-card"
      className={cn('flex flex-col gap-2', className)}
    >
      <span
        data-slot="metric-value"
        className="text-3xl font-semibold tabular-nums"
      >
        {data.value === null ? '—' : show(data.value)}
      </span>
      {data.compare && (
        <span className="text-muted-foreground text-sm">
          {data.compare.delta === null
            ? '—'
            : formatDelta(data.compare.delta, card?.compare?.mode, locale)}
        </span>
      )}
      {/*
        The registry's `Progress`, not a div sized by an inline `width`. A bar
        that fills is a progressbar, and only the real one carries the role,
        the value and the bounds — the div said "how far along the target this
        is" to a pair of eyes and to nothing else. The target itself is the
        one number the drawing knows and the card does not print, so it is
        said here as well as in the reading table. The track is raised from
        the registry's 1px at the call site, as the export progress does; the
        vendored file stays as it ships.
      */}
      {data.target !== undefined && typeof data.value === 'number' && (
        <Progress
          aria-label={messages.label('label.chart.target')}
          aria-valuetext={messages.label('label.chart.target.reached', {
            value: show(data.value),
            target: show(data.target),
          })}
          value={reached(data.value, data.target)}
          className="[&_[data-slot=progress-track]]:h-2"
        />
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
            {...asImage(messages.label('label.chart.sparkline', { name }))}
            data={data.trend.map(point => ({
              x: label(spec?.metric?.trend?.x, point.x),
              trend: point.value,
            }))}
          >
            <Line
              dataKey="trend"
              stroke="var(--color-trend)"
              dot={false}
              isAnimationActive={animate}
            />
          </LineChart>
        </ChartContainer>
      )}
    </div>
  );
}
