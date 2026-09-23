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
import { MinusIcon, TrendingDownIcon, TrendingUpIcon } from 'lucide-react';
import { useChartMotion } from './motion.js';
import type { MetricCardData, MetricPeriod } from '../../analysis/index.js';
import type { FieldTone } from '../../model/index.js';
import { Progress } from '../components/progress.js';
import { cn } from 'cn';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { ToneBadge } from '../variants.js';
import { formatValue } from './axis.js';
import { EChart } from './EChart.js';
import type { FamilyProps, ValueLabel } from './family.js';
import { sparklineOption } from './sparklineOption.js';
import type { ChartTheme } from './theme.js';

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

/**
 * A period as the card names it: the bucket as its column reads it — 「2026
 * 年9月22日」, 「2026年9月」 — except a week, whose column reads as the day
 * it starts, which on its own names a day and not a week.
 */
function periodName(
  unit: MetricPeriod['unit'] | undefined,
  key: unknown,
  x: string | undefined,
  label: ValueLabel,
  messages: MessageFormatters,
): string {
  const start = label(x, key);
  return unit === 'WEEK'
    ? messages.label('label.chart.period.week', { start })
    : start;
}

/** Which way the change went, and whether that is the good way. */
function directionOf(
  delta: number,
  lowerIsBetter: boolean,
): { direction: 'up' | 'down' | 'flat'; tone: FieldTone } {
  if (delta === 0) return { direction: 'flat', tone: 'neutral' };
  const up = delta > 0;
  return {
    direction: up ? 'up' : 'down',
    tone: up !== lowerIsBetter ? 'success' : 'danger',
  };
}

const DIRECTION_ICON = {
  up: TrendingUpIcon,
  down: TrendingDownIcon,
  flat: MinusIcon,
};

/**
 * The headline against the period before: the difference in the headline's
 * own format and as a share, the arrow and the tone saying which way it went
 * and whether that is good (`MetricTrend.lowerIsBetter`), then the words
 * that say what it is measured against. Without a period before, or a
 * number in it, it says so rather than leaving a gap that reads as "no
 * change".
 */
function PeriodChange({
  period,
  show,
  lowerIsBetter,
}: {
  period: MetricPeriod;
  show: (value: number) => string;
  lowerIsBetter: boolean;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const change = period.change;
  if (!change)
    return (
      <span data-slot="metric-change" className="text-muted-foreground text-sm">
        {messages.label(
          change === null
            ? 'label.chart.change.unknown'
            : 'label.chart.change.none',
        )}
      </span>
    );
  const { direction, tone } = directionOf(change.delta, lowerIsBetter);
  const Icon = DIRECTION_ICON[direction];
  const sign = change.delta > 0 ? '+' : '';
  const ratio =
    change.ratio === null
      ? undefined
      : `${change.ratio > 0 ? '+' : ''}${formatValue(change.ratio, 'percent', locale)}`;
  return (
    <span
      data-slot="metric-change"
      data-direction={direction}
      className="flex flex-wrap items-center gap-1.5 text-sm"
    >
      <ToneBadge tone={tone} dot={false}>
        <Icon data-icon="inline-start" />
        {`${sign}${show(change.delta)}`}
        {ratio !== undefined && ` · ${ratio}`}
      </ToneBadge>
      <span className="text-muted-foreground">
        {messages.label('label.chart.change.against')}
      </span>
    </span>
  );
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
  const trendName = messages.label('label.chart.trend');
  const trend = data.trend;
  const sparkline = useCallback(
    (theme: ChartTheme) =>
      sparklineOption(
        trend ?? [],
        {
          label,
          x: card?.trend?.x,
          metric: card?.metric,
          name: trendName,
          animate,
        },
        theme,
      ),
    [trend, label, card, trendName, animate],
  );
  /**
   * The headline number as its own column reads it, so a card of money says
   * ¥10,230.00 where the table under it says the same. `MetricCardSpec.format`
   * still wins: it is an instruction about this card.
   */
  const show = (value: number | string) =>
    card?.format === undefined || typeof value === 'string'
      ? label(card?.metric, value)
      : formatValue(value, card.format, locale);
  const period = data.period;
  const periodOf = (key: unknown) =>
    periodName(period?.unit, key, card?.trend?.x, label, messages);
  // Which span the headline covers, said over it: a trend card's number is
  // one period or the whole range, and its sparkline is neither — the two
  // read as one span when nothing on the card tells them apart (audit P1-6).
  const span = period
    ? period.partial
      ? messages.label('label.chart.period.so-far', {
          period: periodOf(period.at),
        })
      : periodOf(period.at)
    : data.whole
      ? messages.label('label.chart.period.whole')
      : undefined;
  return (
    <div
      data-slot="metric-card"
      className={cn('flex flex-col gap-2', className)}
    >
      {span !== undefined && (
        <span
          data-slot="metric-period"
          className="text-muted-foreground text-sm"
        >
          {span}
        </span>
      )}
      <span
        data-slot="metric-value"
        className="text-3xl font-semibold tabular-nums"
      >
        {data.value === null ? '—' : show(data.value)}
      </span>
      {period && !period.partial && (
        <PeriodChange
          period={period}
          show={show}
          lowerIsBetter={card?.trend?.lowerIsBetter === true}
        />
      )}
      {data.compare && (
        <span className="text-muted-foreground text-sm">
          {data.compare.delta === null
            ? '—'
            : formatDelta(data.compare.delta, card?.compare?.mode, locale)}
        </span>
      )}
      {period?.skipped !== undefined && (
        <span
          data-slot="metric-skipped"
          className="text-muted-foreground text-xs"
        >
          {messages.label('label.chart.period.skipped', {
            period: periodOf(period.skipped),
          })}
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
      {trend && trend.length > 0 && (
        <EChart
          name={messages.label('label.chart.sparkline', { name })}
          className="aspect-auto h-16 min-h-0"
          option={sparkline}
          data={{ 'data-chart': 'sparkline', 'data-marks': trend.length }}
        />
      )}
    </div>
  );
}
