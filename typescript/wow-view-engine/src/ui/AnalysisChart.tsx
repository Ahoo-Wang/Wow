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
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  Line,
  LineChart,
  Pie,
  PieChart,
  ReferenceLine,
  Scatter,
  ScatterChart,
  XAxis,
  YAxis,
  ZAxis,
} from 'recharts';
import type {
  CartesianData,
  ChartData,
  FunnelData,
  HeatmapData,
  MetricCardData,
  PieData,
  ScatterData,
} from '../analysis/index.js';
import type { CartesianSeries, ChartSpec } from '../model/index.js';
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from './components/chart.js';
import { cn } from 'cn';

export interface AnalysisChartProps {
  data: ChartData;
  /** The saved spec; only `combo` and a few axis options still need it. */
  spec?: ChartSpec;
  className?: string;
}

/** Five slots, cycled; the theme owns what they look like. */
const PALETTE = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
];

function color(index: number): string {
  return PALETTE[index % PALETTE.length];
}

/**
 * Draws what the kernel already shaped.
 *
 * Every pivot, merge, cumulation and conversion happened in `shapeChart`, so
 * this file only picks marks and colours; a different chart library would
 * replace it without touching a rule.
 */
export function AnalysisChart({ data, spec, className }: AnalysisChartProps) {
  switch (data.type) {
    case 'cartesian':
      return <Cartesian data={data} spec={spec} className={className} />;
    case 'pie':
      return <PieSlices data={data} spec={spec} className={className} />;
    case 'heatmap':
      return <Heatmap data={data} className={className} />;
    case 'scatter':
      return <ScatterPoints data={data} className={className} />;
    case 'funnel':
      return <Funnel data={data} spec={spec} className={className} />;
    case 'metric':
      return <MetricCard data={data} className={className} />;
  }
}

function labelOf(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return value.toString();
  return JSON.stringify(value) ?? '';
}

function Cartesian({
  data,
  spec,
  className,
}: {
  data: CartesianData;
  spec?: ChartSpec;
  className?: string;
}) {
  const rows = useMemo(
    () =>
      data.points.map(point => ({
        x: labelOf(point.x),
        ...point.values,
      })),
    [data],
  );

  const config = useMemo<ChartConfig>(
    () =>
      Object.fromEntries(
        data.series.map((series, index) => [
          series.key,
          { label: series.key, color: color(index) },
        ]),
      ),
    [data],
  );

  const horizontal = spec?.cartesian?.orientation === 'horizontal';
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
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
      <Chart data={rows} layout={horizontal ? 'vertical' : 'horizontal'}>
        <CartesianGrid vertical={false} />
        {horizontal ? (
          <>
            <XAxis type="number" />
            <YAxis type="category" dataKey="x" width={96} />
          </>
        ) : (
          <>
            <XAxis dataKey="x" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} />
          </>
        )}
        <ChartTooltip content={<ChartTooltipContent />} />
        {data.series.length > 1 && (
          <ChartLegend content={<ChartLegendContent />} />
        )}
        {data.series.map(series =>
          mark(series.key, bySeries.get(series.metric), data.chart),
        )}
        {(spec?.cartesian?.referenceLines ?? []).map(line => (
          <ReferenceLine
            key={`${line.axis}-${line.value}`}
            y={line.value}
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
) {
  const kind = chart === 'combo' ? (series?.type ?? 'bar') : chart;
  const fill = `var(--color-${key})`;
  if (kind === 'line')
    return (
      <Line
        key={key}
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
      dataKey={key}
      fill={fill}
      stackId={series?.stack}
      radius={2}
    />
  );
}

function PieSlices({
  data,
  spec,
  className,
}: {
  data: PieData;
  spec?: ChartSpec;
  className?: string;
}) {
  const rows = data.slices.map(slice => ({
    name: slice.other === true ? 'Other' : labelOf(slice.category),
    value: slice.value,
  }));
  const config = Object.fromEntries(
    rows.map((row, index) => [
      row.name,
      { label: row.name, color: color(index) },
    ]),
  ) satisfies ChartConfig;

  return (
    <ChartContainer
      config={config}
      className={cn('min-h-52 w-full', className)}
    >
      <PieChart>
        <ChartTooltip content={<ChartTooltipContent nameKey="name" />} />
        <Pie
          data={rows}
          dataKey="value"
          nameKey="name"
          innerRadius={spec?.pie?.donut === true ? '55%' : 0}
        >
          {rows.map((row, index) => (
            <Cell key={row.name} fill={color(index)} />
          ))}
        </Pie>
        <ChartLegend content={<ChartLegendContent nameKey="name" />} />
      </PieChart>
    </ChartContainer>
  );
}

function ScatterPoints({
  data,
  className,
}: {
  data: ScatterData;
  className?: string;
}) {
  const rows = data.points.map(point => ({
    name: labelOf(point.category),
    x: point.x,
    y: point.y,
    size: point.size ?? 1,
  }));

  return (
    <ChartContainer
      config={{ points: { label: 'Points', color: color(0) } }}
      className={cn('min-h-52 w-full', className)}
    >
      <ScatterChart>
        <CartesianGrid />
        <XAxis type="number" dataKey="x" />
        <YAxis type="number" dataKey="y" />
        <ZAxis type="number" dataKey="size" range={[40, 260]} />
        <ChartTooltip content={<ChartTooltipContent nameKey="name" />} />
        <Scatter data={rows} fill="var(--color-points)" />
      </ScatterChart>
    </ChartContainer>
  );
}

/**
 * A grid rather than a chart library: a heatmap is cells with a background,
 * and every library's version of that costs more than it saves.
 */
function Heatmap({
  data,
  className,
}: {
  data: HeatmapData;
  className?: string;
}) {
  const values = data.cells
    .flat()
    .filter((cell): cell is number => cell !== null);
  const max = values.length > 0 ? Math.max(...values) : 0;
  const min = values.length > 0 ? Math.min(...values) : 0;
  const span = max - min || 1;

  return (
    <div
      data-slot="heatmap"
      className={cn('flex flex-col gap-1 overflow-x-auto', className)}
    >
      {data.ys.map((y, row) => (
        <div key={labelOf(y) || row} className="flex items-center gap-1">
          <span className="text-muted-foreground w-24 shrink-0 truncate text-xs">
            {labelOf(y)}
          </span>
          {data.xs.map((x, column) => {
            const cell = data.cells[row]?.[column] ?? null;
            return (
              <div
                key={labelOf(x) || column}
                title={`${labelOf(y)} · ${labelOf(x)}: ${cell ?? '—'}`}
                className="bg-primary size-8 shrink-0 rounded-sm"
                style={{
                  opacity:
                    cell === null ? 0.06 : 0.15 + ((cell - min) / span) * 0.85,
                }}
              />
            );
          })}
        </div>
      ))}
      <div className="flex items-center gap-1">
        <span className="w-24 shrink-0" />
        {data.xs.map((x, column) => (
          <span
            key={labelOf(x) || column}
            className="text-muted-foreground w-8 shrink-0 truncate text-center text-xs"
          >
            {labelOf(x)}
          </span>
        ))}
      </div>
    </div>
  );
}

function Funnel({
  data,
  spec,
  className,
}: {
  data: FunnelData;
  spec?: ChartSpec;
  className?: string;
}) {
  const widest = Math.max(...data.stages.map(stage => stage.value), 1);
  const horizontal = spec?.funnel?.orientation === 'horizontal';

  return (
    <div
      data-slot="funnel"
      className={cn(
        'gap-2',
        horizontal ? 'flex items-end' : 'flex flex-col',
        className,
      )}
    >
      {data.stages.map(stage => (
        <div
          key={stage.label}
          className={cn(
            'flex gap-2',
            horizontal ? 'flex-col-reverse' : 'items-center',
          )}
        >
          <span className="text-muted-foreground w-28 shrink-0 truncate text-xs">
            {stage.label}
          </span>
          <div
            className="bg-primary/80 flex h-7 items-center justify-end rounded-sm px-2"
            style={{ width: `${Math.max(4, (stage.value / widest) * 100)}%` }}
          >
            <span className="text-primary-foreground text-xs">
              {stage.value}
            </span>
          </div>
          {stage.conversion !== undefined && (
            <span className="text-muted-foreground w-12 shrink-0 text-xs">
              {Math.round(stage.conversion * 100)}%
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

function MetricCard({
  data,
  className,
}: {
  data: MetricCardData;
  className?: string;
}) {
  return (
    <div
      data-slot="metric-card"
      className={cn('flex flex-col gap-2', className)}
    >
      <span className="text-3xl font-semibold tabular-nums">
        {data.value === null ? '—' : data.value.toLocaleString()}
      </span>
      {data.compare && (
        <span className="text-muted-foreground text-sm">
          {data.compare.delta === null
            ? '—'
            : `${data.compare.delta > 0 ? '+' : ''}${data.compare.delta.toLocaleString()}`}
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
          config={{ trend: { label: 'Trend', color: color(0) } }}
          className="h-16 w-full"
        >
          <LineChart
            data={data.trend.map(point => ({
              x: labelOf(point.x),
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
