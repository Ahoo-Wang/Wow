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

import { useMemo, type MouseEvent as ReactMouseEvent } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  LineChart,
  ReferenceLine,
  XAxis,
  YAxis,
} from 'recharts';
import type { CartesianData } from '../../analysis/index.js';
import type { CartesianSeries } from '../../model/index.js';
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '../components/chart.js';
import { cn } from 'cn';
import { asImage } from './asImage.js';
import { axisId, domainOf, tickFormatterOf } from './axis.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import type { FamilyProps } from './family.js';
import { colorOf } from './palette.js';
import { TooltipValue } from './TooltipValue.js';

export function Cartesian({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
}: FamilyProps<CartesianData>) {
  // A pivot names its series by raw group values, and the style element
  // interpolates config keys into custom properties: only an identifier is
  // safe there, so every data-valued key is exchanged for a synthetic one
  // while the original stays the label.
  const safeKeys = useMemo(() => {
    const map = new Map<string, string>();
    data.series.forEach((series, index) => map.set(series.key, `s${index}`));
    return map;
  }, [data]);

  const rows = useMemo(
    () =>
      data.points.map(point => ({
        x: label(spec?.cartesian?.x, point.x),
        ...Object.fromEntries(
          Object.entries(point.values).map(([key, value]) => [
            safeKeys.get(key) ?? key,
            value,
          ]),
        ),
      })),
    [data, safeKeys, label, spec],
  );

  const config = useMemo<ChartConfig>(
    () =>
      Object.fromEntries(
        data.series.map((series, index) => [
          safeKeys.get(series.key) ?? series.key,
          {
            // A pivoted series shows its split value as that field shows
            // it; an unpivoted one is its column's title — 「金额 的 合计」,
            // never the alias `amount`, which names the query. The legend and
            // the tooltip both read this label.
            label:
              series.value === undefined
                ? (column(series.metric) ?? series.label)
                : label(spec?.cartesian?.splitBy, series.value),
            // The spec names a pivoted series by its split value as the
            // kernel labels it and an unpivoted one by its metric alias; it
            // may name either.
            color: colorOf(spec, index, series.label, series.metric),
          },
        ]),
      ),
    [data, safeKeys, label, column, spec],
  );

  const horizontal = spec?.cartesian?.orientation === 'horizontal';
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  const referenceLines = spec?.cartesian?.referenceLines ?? [];
  const left = spec?.cartesian?.yAxis?.left;
  const right = spec?.cartesian?.yAxis?.right;
  // A second axis is drawn only when something sits on it: two axes where
  // every series is on the left is a scale nobody asked for.
  const hasRight =
    data.series.some(series => bySeries.get(series.metric)?.axis === 'right') ||
    referenceLines.some(line => line.axis === 'right');
  /**
   * Which axis carries the numbers. Laid out vertically that is Y; laid out
   * horizontally the chart is on its side and it is X, which is why a
   * reference line drawn at `y` used to land on the categories.
   */
  const onNumericAxis = (axis: 'left' | 'right' | undefined) =>
    horizontal ? { xAxisId: axisId(axis) } : { yAxisId: axisId(axis) };
  /**
   * The ticks of a numeric axis read as the metric on it reads: the axis
   * carries one column's numbers, so a money series puts ¥1,234.00 on the
   * ticks and in the tooltip, exactly as the table does. An `AxisSpec.format`
   * still wins, because it is an instruction about this axis.
   */
  const metricOn = (side: 'left' | 'right') =>
    data.series.find(
      series => axisId(bySeries.get(series.metric)?.axis) === side,
    )?.metric;
  const ticksOf = (axis: typeof left, side: 'left' | 'right') =>
    tickFormatterOf(axis) ?? ((value: number) => label(metricOn(side), value));
  // A tooltip row names a series and shows one of its numbers, so the number
  // is read through that series' own metric.
  const byKey = new Map(
    data.series.map(series => [safeKeys.get(series.key) ?? series.key, series]),
  );
  const Chart =
    data.chart === 'line'
      ? LineChart
      : data.chart === 'area'
        ? AreaChart
        : data.chart === 'combo'
          ? ComposedChart
          : BarChart;

  return (
    <ChartContainer
      config={config}
      className={cn('min-h-52 w-full', className)}
    >
      <Chart
        data={rows}
        layout={horizontal ? 'vertical' : 'horizontal'}
        {...asImage(name)}
      >
        <CartesianGrid vertical={false} />
        {horizontal ? (
          <>
            <XAxis
              type="number"
              xAxisId="left"
              domain={domainOf(left)}
              tickFormatter={ticksOf(left, 'left')}
            />
            {hasRight && (
              <XAxis
                type="number"
                xAxisId="right"
                orientation="top"
                domain={domainOf(right)}
                tickFormatter={ticksOf(right, 'right')}
              />
            )}
            <YAxis type="category" dataKey="x" width={96} />
          </>
        ) : (
          <>
            <XAxis dataKey="x" tickLine={false} axisLine={false} />
            <YAxis
              yAxisId="left"
              tickLine={false}
              axisLine={false}
              domain={domainOf(left)}
              tickFormatter={ticksOf(left, 'left')}
            />
            {hasRight && (
              <YAxis
                yAxisId="right"
                orientation="right"
                tickLine={false}
                axisLine={false}
                domain={domainOf(right)}
                tickFormatter={ticksOf(right, 'right')}
              />
            )}
          </>
        )}
        <ChartTooltip
          content={
            <ChartTooltipContent
              formatter={(value, name, item) => (
                <TooltipValue
                  color={item.payload?.fill ?? item.color}
                  name={config[String(name)]?.label ?? name}
                  value={label(byKey.get(String(name))?.metric, value)}
                />
              )}
            />
          }
        />
        {data.series.length > 1 && (
          <ChartLegend content={<ChartLegendContent />} />
        )}
        {data.series.map(series => {
          const configured = bySeries.get(series.metric);
          return mark(
            safeKeys.get(series.key) ?? series.key,
            configured,
            data.chart,
            onNumericAxis(configured?.axis),
            // The group a bar stands for: its category, and the split value
            // when the series is one — named by the aliases the spec put on
            // the axes, which is what the kernel reads a row by.
            onPick &&
              ((index: number, event: MouseEvent) => {
                const point = data.points[index];
                if (!point || !spec?.cartesian) return;
                const { x, splitBy } = spec.cartesian;
                onPick(
                  {
                    [x]: point.x,
                    ...(splitBy === undefined
                      ? {}
                      : { [splitBy]: series.value }),
                  },
                  pointAnchor(event),
                );
              }),
          );
        })}
        {referenceLines.map(line => (
          <ReferenceLine
            key={`${line.axis}-${line.value}`}
            {...onNumericAxis(line.axis)}
            {...(horizontal ? { x: line.value } : { y: line.value })}
            label={line.label}
            strokeDasharray="4 4"
          />
        ))}
      </Chart>
    </ChartContainer>
  );
}

/** One drawn series; `combo` takes its mark from the saved spec. */
function mark(
  key: string,
  series: CartesianSeries | undefined,
  chart: CartesianData['chart'],
  /** The numeric axis this series is measured against. */
  axis: { xAxisId: 'left' | 'right' } | { yAxisId: 'left' | 'right' },
  /** A press on the mark at this index; only bars take one. */
  onPress?: (index: number, event: MouseEvent) => void,
) {
  const kind = chart === 'combo' ? (series?.type ?? 'bar') : chart;
  const fill = `var(--color-${key})`;
  if (kind === 'line')
    return (
      <Line
        key={key}
        {...axis}
        dataKey={key}
        stroke={fill}
        dot={false}
        type={series?.smooth === true ? 'monotone' : 'linear'}
      />
    );
  if (kind === 'area')
    return (
      <Area
        key={key}
        {...axis}
        dataKey={key}
        stroke={fill}
        fill={fill}
        fillOpacity={0.2}
        stackId={series?.stack}
        type={series?.smooth === true ? 'monotone' : 'linear'}
      />
    );
  return (
    <Bar
      key={key}
      {...axis}
      dataKey={key}
      fill={fill}
      stackId={series?.stack}
      radius={2}
      className={onPress ? 'cursor-pointer' : undefined}
      onClick={
        onPress &&
        ((_: unknown, index: number, event: ReactMouseEvent) =>
          onPress(index, event.nativeEvent))
      }
    />
  );
}
