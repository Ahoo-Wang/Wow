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

import { type CSSProperties, useCallback } from 'react';
import { MinusIcon, TrendingDownIcon, TrendingUpIcon } from 'lucide-react';
import { useChartMotion } from './motion.js';
import type { MetricCardData, MetricPeriod } from '../../analysis/index.js';
import { Progress } from '../components/progress.js';
import { cn } from 'cn';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { ChangeBadge, type ChangeBadgeProps } from '../variants.js';
import { formatValue } from './axis.js';
import { EChart } from './EChart.js';
import type { FamilyProps, ValueLabel } from './family.js';
import { spanName } from './periodSpan.js';
import { sparklineOption } from './sparklineOption.js';
import type { ChartTheme } from './theme.js';

/**
 * The comparison, signed. In `percent` mode the kernel divides, so the delta
 * is a ratio: printing it as it stands turned a quarter more than last week
 * into "+0.25". In `delta` mode it is a difference of two headlines, and
 * reads as the headline does (`show`).
 */
function formatDelta(
  delta: number,
  mode: 'delta' | 'percent' | undefined,
  locale: string | undefined,
  show: (value: number) => string,
) {
  const sign = delta > 0 ? '+' : '';
  return `${sign}${mode === 'percent' ? formatValue(delta, 'percent', locale) : show(delta)}`;
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

/**
 * How wide a figure is, in ems, at the widest a page is likely to draw it:
 * each character at its advance in DejaVu Sans Bold — the face Linux falls
 * back to and draws a semibold in — which is wider than Geist and the
 * system stacks everywhere a figure's characters go (its digits 0.696em,
 * its separators 0.38), with a little room over it: a digit or a currency
 * sign 0.72em, a separator 0.42, a sign 0.84, a per cent 1.0, a CJK
 * character (「万」, 「亿」) 1.0, anything else a letter's 0.75. An estimate
 * from above, never a measure: the stylesheet sets the figure no larger
 * than the card's width over it (`styles.css`), so it can only be a little
 * smaller than it had to be, never cut.
 */
function figureEms(text: string): number {
  let ems = 0;
  for (const char of text)
    ems += /[0-9$¥€£₩₹]/.test(char)
      ? 0.72
      : /[.,:'\s]/.test(char)
        ? 0.42
        : /[+\-−–—]/.test(char)
          ? 0.84
          : char === '%' || /[\u3000-\u9fff\uff00-\uffef]/.test(char)
            ? 1
            : 0.75;
  return Math.round(ems * 100) / 100 || 1;
}

/** Which way the change went, and whether that is the good way. */
function directionOf(
  delta: number,
  lowerIsBetter: boolean,
): { direction: 'up' | 'down' | 'flat'; tone: ChangeBadgeProps['tone'] } {
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
 * and whether that is good (`MetricCardSpec.lowerIsBetter`) — coloured by
 * whether it is good, or by its direction where the host's convention says so
 * (`ChangeBadge`, themes.md 2.6), and never by colour alone — then the words
 * that say what it is measured against. Without a period before, or a
 * number in it, it says so rather than leaving a gap that reads as "no
 * change".
 */
/**
 * A change badge that wraps inside the card rather than running past its
 * edge, should it still be wider than the card: on a card under 10rem — half
 * a phone wide, two to a row in a board's narrow reading — the stylesheet
 * shows the share alone (`data-share`, `styles.css`), which fits on one
 * line; a change with no share keeps its amount, and this is what holds it
 * in. Anywhere wider it stays the registry's one-line pill, as it was.
 */
const CHANGE_WRAPS =
  'fve:max-w-full fve:@max-3xs/metric:h-auto fve:@max-3xs/metric:min-h-5 fve:@max-3xs/metric:py-0 fve:@max-3xs/metric:whitespace-normal';

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
      <span
        data-slot="metric-change"
        data-change={
          period.unmatched ? 'unmatched' : change === null ? 'unknown' : 'none'
        }
        className="fve:text-muted-foreground fve:text-sm"
      >
        {messages.label(
          period.unmatched
            ? 'label.chart.change.unmatched'
            : change === null
              ? 'label.chart.change.unknown'
              : 'label.chart.change.none',
        )}
      </span>
    );
  const { direction, tone } = directionOf(change.delta, lowerIsBetter);
  const Icon = DIRECTION_ICON[direction];
  const amount = `${change.delta > 0 ? '+' : ''}${show(change.delta)}`;
  const ratio =
    change.ratio === null
      ? undefined
      : `${change.ratio > 0 ? '+' : ''}${formatValue(change.ratio, 'percent', locale)}`;
  return (
    <span
      data-slot="metric-change"
      data-direction={direction}
      className="fve:flex fve:flex-wrap fve:items-center fve:gap-1.5 fve:text-sm"
    >
      <ChangeBadge
        direction={direction}
        tone={tone}
        className={CHANGE_WRAPS}
        // The share alone, which a card too narrow for the whole change
        // shows in its place (styles.css), and the whole change for a
        // pointer there; a screen reader reads the words themselves.
        {...(ratio === undefined
          ? {}
          : { 'data-share': ratio, title: `${amount} · ${ratio}` })}
      >
        <Icon data-icon="inline-start" />
        {/* Two runs of text, as the badge has always drawn them. */}
        {amount}
        {ratio !== undefined && ` · ${ratio}`}
      </ChangeBadge>
      <span className="fve:text-muted-foreground">
        {messages.label(`label.chart.change.against.${period.unit}`)}
      </span>
    </span>
  );
}

/**
 * The headline against the metric it is compared with (`compare`) — this
 * month against the same days of last month — read as the change against
 * the period before is: signed, coloured by whether it is good, and then
 * what it is measured against, by that metric's name (「较「上月同期
 * GMV」」). Without a number to compare with, a dash, and still what it
 * would have been compared with.
 */
function CompareChange({
  delta,
  unmatched,
  mode,
  against,
  show,
  lowerIsBetter,
}: {
  delta: number | null;
  unmatched: boolean;
  mode: 'delta' | 'percent' | undefined;
  against: string;
  show: (value: number) => string;
  lowerIsBetter: boolean;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const said = (
    <span className="fve:text-muted-foreground">
      {messages.label('label.chart.compare.against', { metric: against })}
    </span>
  );
  if (unmatched)
    return (
      <span
        data-slot="metric-compare"
        data-change="unmatched"
        className="fve:text-muted-foreground fve:text-sm"
      >
        {messages.label('label.chart.compare.unmatched', { metric: against })}
      </span>
    );
  if (delta === null)
    return (
      <span
        data-slot="metric-compare"
        className="fve:flex fve:flex-wrap fve:items-center fve:gap-1.5 fve:text-sm"
      >
        <span className="fve:text-muted-foreground">—</span>
        {said}
      </span>
    );
  const { direction, tone } = directionOf(delta, lowerIsBetter);
  const Icon = DIRECTION_ICON[direction];
  return (
    <span
      data-slot="metric-compare"
      data-direction={direction}
      className="fve:flex fve:flex-wrap fve:items-center fve:gap-1.5 fve:text-sm"
    >
      <ChangeBadge direction={direction} tone={tone} className={CHANGE_WRAPS}>
        <Icon data-icon="inline-start" />
        {formatDelta(delta, mode, locale, show)}
      </ChangeBadge>
      {said}
    </span>
  );
}

export function MetricCard({
  data,
  spec,
  className,
  label,
  column,
  name,
  filled,
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
          filled,
        },
        theme,
      ),
    [trend, label, card, trendName, animate, filled],
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
  // The period the number covers: the bucket, or the part of it the dates
  // hold — one day of a month is that day, not the month.
  const headlineOf = (held: MetricPeriod) =>
    held.span ? spanName(held.span, locale, messages) : periodOf(held.at);
  // Which span the headline covers, said over it: a trend card's number is
  // one period or the whole range, and its sparkline is neither — the two
  // read as one span when nothing on the card tells them apart (audit P1-6).
  const span = period
    ? period.partial
      ? messages.label('label.chart.period.so-far', {
          period: headlineOf(period),
        })
      : headlineOf(period)
    : data.whole
      ? messages.label('label.chart.period.whole')
      : undefined;
  const figure = data.value === null ? '—' : show(data.value);
  return (
    <div
      data-slot="metric-card"
      // Its own width decides the figure's size: a card half a phone wide
      // (two to a row in a board's narrow reading) sets it a step smaller
      // rather than let 「¥16,052.93」 run past its edge.
      className={cn(
        'fve:@container/metric fve:flex fve:flex-col fve:gap-2',
        className,
      )}
    >
      {span !== undefined && (
        <span
          data-slot="metric-period"
          className="fve:text-muted-foreground fve:text-sm"
        >
          {span}
        </span>
      )}
      <span
        data-slot="metric-value"
        className="fve:text-3xl fve:font-semibold fve:tabular-nums"
        // How wide the figure is, in ems, which the stylesheet sizes it by so
        // that it fits the card on one line (`styles.css`).
        style={{ '--_fve-metric-ems': figureEms(figure) } as CSSProperties}
      >
        {figure}
      </span>
      {period && !period.partial && (
        <PeriodChange
          period={period}
          show={show}
          lowerIsBetter={card?.lowerIsBetter === true}
        />
      )}
      {data.cut && (
        <span
          data-slot="metric-cut"
          role="note"
          className="fve:text-muted-foreground fve:text-sm"
        >
          {messages.label('label.chart.period.cut', {
            limit: data.cut.limit,
          })}
        </span>
      )}
      {data.compare && (
        <CompareChange
          delta={data.compare.delta}
          unmatched={data.compare.unmatched === true}
          mode={card?.compare?.mode}
          against={column(card?.compare?.metric) ?? card?.compare?.metric ?? ''}
          show={show}
          lowerIsBetter={card?.lowerIsBetter === true}
        />
      )}
      {period?.skipped !== undefined && (
        <span
          data-slot="metric-skipped"
          className="fve:text-muted-foreground fve:text-xs"
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
          className={cn(
            'fve:[&_[data-slot=progress-track]]:h-2',
            // Without a trend under it, the bar is the card's foot.
            !trend?.length && 'fve:mt-auto',
          )}
        />
      )}
      {/*
        The trend takes whatever height the card has left, so a card sized
        taller than its words — a board panel three rows high, or one a
        reader has dragged — is filled by the line rather than left a
        quarter empty under it. It never gets shorter than a line that still
        reads, which is how tall it is where nothing gives the card a height,
        nor taller than a sparkline should be: past that it stands at the
        card's foot (`mt-auto`), the room left above it.
      */}
      {trend && trend.length > 0 && (
        <EChart
          name={messages.label('label.chart.sparkline', { name })}
          className="fve:mt-auto fve:aspect-auto fve:max-h-40 fve:min-h-16 fve:flex-1"
          option={sparkline}
          data={{ 'data-chart': 'sparkline', 'data-marks': trend.length }}
        />
      )}
    </div>
  );
}
