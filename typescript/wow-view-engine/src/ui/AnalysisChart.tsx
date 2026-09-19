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
import { isChartColor } from '../analysis/index.js';
import type {
  AnalysisColumnView,
  CartesianData,
  ChartData,
  FunnelData,
  HeatmapData,
  MetricCardData,
  PieData,
  ScatterData,
} from '../analysis/index.js';
import type {
  AxisSpec,
  CartesianSeries,
  ChartSpec,
  ValueFormat,
} from '../model/index.js';
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from './components/chart.js';
import { cn } from 'cn';
import { displayValue, valueText } from './display.js';
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
 * The colour the spec pinned for the first of `keys` that names one, and the
 * slot otherwise. A key is a series or category as the kernel labels it, never
 * an internal one: the kernel tags a pivot's key by type and this file then
 * exchanges it for `s0`, `s1` …, so a spec could not name either if it tried.
 * Nor is it the text shown, an enum's label or a bucket's day: that follows
 * the language and the definition's wording, and a saved key must not.
 *
 * A spec may reach here unvalidated — the stories pass one straight in — and
 * its value ends up inside a `<style>` element, so the kernel's predicate
 * decides again here rather than being trusted to have run.
 */
function colorOf(
  spec: ChartSpec | undefined,
  index: number,
  ...keys: string[]
): string {
  for (const key of keys) {
    const configured = spec?.colors?.[key];
    if (isChartColor(configured)) return configured;
  }
  return color(index);
}

/**
 * A number as the spec asks for it. `percent` is a ratio the kernel produced
 * — `deltaOf` divides — so it is the only one that scales before it prints.
 */
function formatValue(value: number, format?: ValueFormat): string {
  if (format === 'percent')
    return `${(value * 100).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
  if (format === 'compact')
    return value.toLocaleString(undefined, { notation: 'compact' });
  return value.toLocaleString();
}

/** A bound the spec pinned; the other end is left to the data. */
function domainOf(axis: AxisSpec | undefined) {
  if (!axis || (axis.min === undefined && axis.max === undefined))
    return undefined;
  return [axis.min ?? 'auto', axis.max ?? 'auto'] as [
    number | 'auto',
    number | 'auto',
  ];
}

function tickFormatterOf(axis: AxisSpec | undefined) {
  if (!axis?.format) return undefined;
  return (value: number) => formatValue(value, axis.format);
}

/** Which numeric axis a series or a line belongs to; the left one by default. */
function axisId(axis: 'left' | 'right' | undefined): 'left' | 'right' {
  return axis === 'right' ? 'right' : 'left';
}

/**
 * Draws what the kernel already shaped.
 *
 * Every pivot, merge, cumulation and conversion happened in `shapeChart`, so
 * this file only picks marks and colours; a different chart library would
 * replace it without touching a rule.
 */
export function AnalysisChart({
  data,
  spec,
  className,
  columns,
}: AnalysisChartProps) {
  const label = useCategoryLabel(columns);
  const props = { spec, className, label };
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

/** A category as its column shows it, by the alias the chart reads it from. */
type CategoryLabel = (alias: string | undefined, value: unknown) => string;

function useCategoryLabel(
  columns: readonly AnalysisColumnView[] | undefined,
): CategoryLabel {
  const display = useSurfaceDisplay();
  const messages = useViewMessages();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    // As the analysis table shows the same value: what the field's kind
    // names first, then a number in its format and a boolean in words.
    return (alias, value) => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return (
        (column && displayValue(value, column, display)) ??
        valueText(value, messages, column?.numberFormat)
      );
    };
  }, [columns, display, messages]);
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
  label,
}: {
  data: CartesianData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
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
            // A pivoted series shows its split value as that field shows it;
            // an unpivoted one is its metric alias.
            label:
              series.value === undefined
                ? series.label
                : label(spec?.cartesian?.splitBy, series.value),
            // The spec names a pivoted series by its split value as the
            // kernel labels it and an unpivoted one by its metric alias; it
            // may name either.
            color: colorOf(spec, index, series.label, series.metric),
          },
        ]),
      ),
    [data, safeKeys, label, spec],
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
            <XAxis
              type="number"
              xAxisId="left"
              domain={domainOf(left)}
              tickFormatter={tickFormatterOf(left)}
            />
            {hasRight && (
              <XAxis
                type="number"
                xAxisId="right"
                orientation="top"
                domain={domainOf(right)}
                tickFormatter={tickFormatterOf(right)}
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
              tickFormatter={tickFormatterOf(left)}
            />
            {hasRight && (
              <YAxis
                yAxisId="right"
                orientation="right"
                tickLine={false}
                axisLine={false}
                domain={domainOf(right)}
                tickFormatter={tickFormatterOf(right)}
              />
            )}
          </>
        )}
        <ChartTooltip content={<ChartTooltipContent />} />
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
    />
  );
}

function PieSlices({
  data,
  spec,
  className,
  label,
}: {
  data: PieData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
  const messages = useViewMessages();
  // Same rule as the cartesian series: a category value becomes an identifier
  // before it can reach the style element, and stays a label.
  const rows = data.slices.map((slice, index) => ({
    key: `p${index}`,
    name:
      slice.other === true
        ? messages.label('label.chart.other')
        : label(spec?.pie?.category, slice.category),
    value: slice.value,
    // A slice is named by its category as the kernel labels it — `null` is
    // the empty string there, where `String(...)` would have looked it up
    // under `null` — rather than as the legend shows it. The merged
    // remainder is no category anyone could have coloured, so it keeps its
    // slot whatever the spec says.
    color:
      slice.other === true
        ? color(index)
        : colorOf(spec, index, labelOf(slice.category)),
  }));
  const config = Object.fromEntries(
    rows.map(row => [row.key, { label: row.name, color: row.color }]),
  ) satisfies ChartConfig;

  return (
    <ChartContainer
      config={config}
      className={cn('min-h-52 w-full', className)}
    >
      <PieChart>
        <ChartTooltip content={<ChartTooltipContent nameKey="key" />} />
        <Pie
          data={rows}
          dataKey="value"
          nameKey="key"
          innerRadius={spec?.pie?.donut === true ? '55%' : 0}
        >
          {rows.map(row => (
            <Cell key={row.key} fill={row.color} />
          ))}
        </Pie>
        <ChartLegend content={<ChartLegendContent nameKey="key" />} />
      </PieChart>
    </ChartContainer>
  );
}

function ScatterPoints({
  data,
  spec,
  className,
  label,
}: {
  data: ScatterData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
  const messages = useViewMessages();
  const rows = data.points.map(point => ({
    name: label(spec?.scatter?.category, point.category),
    x: point.x,
    y: point.y,
    size: point.size ?? 1,
  }));

  return (
    <ChartContainer
      config={{
        points: {
          label: messages.label('label.chart.points'),
          color: color(0),
        },
      }}
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
  spec,
  className,
  label,
}: {
  data: HeatmapData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
  const messages = useViewMessages();
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
            {label(spec?.heatmap?.y, y)}
          </span>
          {data.xs.map((x, column) => {
            const cell = data.cells[row]?.[column] ?? null;
            return (
              <div
                key={labelOf(x) || column}
                title={messages.label('label.chart.cell', {
                  y: label(spec?.heatmap?.y, y),
                  x: label(spec?.heatmap?.x, x),
                  value: cell ?? messages.label('label.summary.unavailable'),
                })}
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
            {label(spec?.heatmap?.x, x)}
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
  label,
}: {
  data: FunnelData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
  const widest = Math.max(...data.stages.map(stage => stage.value), 1);
  // Stages taken from a group are named by its values, which show as the
  // group's column does; metric stages carry the labels they were given.
  const stages = spec?.funnel?.stages;
  const category = stages?.from === 'group' ? stages.category : undefined;
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
            {category === undefined
              ? stage.label
              : label(category, stage.label)}
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

/**
 * The comparison, signed. In `percent` mode the kernel divides, so the delta
 * is a ratio: printing it as it stands turned a quarter more than last week
 * into "+0.25".
 */
function formatDelta(delta: number, mode: 'delta' | 'percent' | undefined) {
  const sign = delta > 0 ? '+' : '';
  return `${sign}${formatValue(delta, mode === 'percent' ? 'percent' : undefined)}`;
}

function MetricCard({
  data,
  spec,
  className,
  label,
}: {
  data: MetricCardData;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}) {
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
