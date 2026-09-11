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

import { AggregationDateUnit } from '@ahoo-wang/fetcher-wow';
import { useMemo } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  XAxis,
  YAxis,
} from 'recharts';
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '../components/ui/chart.js';
import type { DeepReadonly } from '../lib/types.js';
import { fixedTimeZoneOffset } from '../lib/timeZone.js';
import { getAnalysisVisualization } from './analysisVisualizations.js';
import { formatAnalysisValue } from './analysisFormatting.js';
import type { AnalysisPlan, AnalysisRow } from './analysisModel.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import { projectAnalysis } from './analysisProjection.js';
import { AnalysisTable } from './AnalysisTable.js';

export interface AnalysisChartProps {
  plan: DeepReadonly<AnalysisPlan>;
  rows: DeepReadonly<readonly AnalysisRow[]>;
  presentation: DeepReadonly<AnalysisPresentation>;
}
const color = (index: number) => `var(--fve-chart-${(index % 5) + 1})`;
/** Displays only the executed result supplied by the host; never dispatches queries. */
export function AnalysisChart({
  plan,
  rows,
  presentation,
}: AnalysisChartProps) {
  const projected = useMemo(
    () => projectAnalysis(plan, rows, presentation),
    [plan, rows, presentation],
  );
  const table = <AnalysisTable plan={projected.plan} rows={rows} sort={[]} />;
  if (projected.issues.length)
    return (
      <div>
        <p role="status">{projected.issues.join('；')}</p>
        {table}
      </div>
    );
  if (presentation.layout === 'table') return table;
  if (!rows.length) return <p role="status">没有符合条件的分析结果</p>;
  if (presentation.layout === 'metric')
    return (
      <dl
        aria-label="分析指标"
        className="fve:grid fve:grid-cols-1 fve:gap-4 fve:sm:grid-cols-3"
      >
        {projected.series.map(series => (
          <div
            key={series.key}
            className="fve:rounded-xl fve:border fve:bg-background fve:p-5"
          >
            <dt className="fve:text-muted-foreground">{series.title}</dt>
            <dd
              title={String(projected.points[0]?.[series.key])}
              className="fve:mt-2 fve:text-3xl fve:font-semibold fve:tabular-nums"
            >
              {formatAnalysisValue(
                projected.points[0]?.[series.key],
                plan.schema.find(column => column.alias === series.alias),
                plan.timeZone,
              )}
              {series.unit && (
                <span className="fve:ml-2 fve:text-sm fve:font-normal">
                  {series.unit}
                </span>
              )}
            </dd>
          </div>
        ))}
      </dl>
    );
  const config: ChartConfig = Object.fromEntries(
    projected.series.map((s, i) => [
      s.key,
      { label: s.title, color: color(i) },
    ]),
  );
  const metricColumn = plan.schema.find(
    column => column.alias === projected.series[0]?.alias,
  );
  // Normalize before adding: finite metrics can still overflow when summed directly.
  const pieScale =
    presentation.layout === 'pie'
      ? Math.max(...projected.points.map(point => point.s0 as number))
      : 1;
  const pieTotal =
    presentation.layout === 'pie'
      ? projected.points.reduce(
          (sum, point) => sum + (point.s0 as number) / pieScale,
          0,
        )
      : 1;
  const piePoints =
    presentation.layout === 'pie'
      ? projected.points.map(point => ({
          identity: point.identity,
          x: point.x,
          s0: point.s0,
          share: (point.s0 as number) / pieScale / pieTotal,
        }))
      : [];
  const percent = new Intl.NumberFormat('zh-CN', {
    style: 'percent',
    maximumFractionDigits: 1,
  });
  const formatShare = (share: number, raw: number) =>
    raw > 0 && share < 0.001 ? '<0.1%' : percent.format(share);
  const formatMetric = (value: unknown) =>
    formatAnalysisValue(value, metricColumn, plan.timeZone);
  const formatX = (value: unknown, detail = false) => {
    const point = projected.points.find(point => point.x === value);
    const label = point?.[detail ? 'tooltipLabel' : 'label'];
    if (typeof label === 'string') return label;
    const raw =
      projected.x?.valueType === 'number' &&
      typeof value === 'string' &&
      value !== '无值'
        ? Number(value)
        : projected.x?.valueType === 'boolean' &&
            (value === 'true' || value === 'false')
          ? value === 'true'
          : value;
    return formatAnalysisValue(raw, projected.x, plan.timeZone);
  };
  const formatXTick = (value: unknown) => {
    if (
      plan.schema.some(column => column.labelFor === projected.x?.alias) ||
      projected.x?.valueType !== 'datetime' ||
      typeof value !== 'number'
    )
      return formatX(value);
    const offset = fixedTimeZoneOffset(plan.timeZone);
    const unit = projected.x.group?.unit;
    const calendar =
      unit === AggregationDateUnit.YEAR ||
      unit === AggregationDateUnit.QUARTER ||
      unit === AggregationDateUnit.MONTH;
    const subday =
      unit === AggregationDateUnit.HOUR ||
      unit === AggregationDateUnit.MINUTE ||
      unit === AggregationDateUnit.SECOND;
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: offset === undefined ? (plan.timeZone ?? 'UTC') : 'UTC',
      ...(calendar ? { year: 'numeric' as const } : {}),
      ...(unit !== AggregationDateUnit.YEAR
        ? { month: '2-digit' as const }
        : {}),
      ...(!calendar ? { day: '2-digit' as const } : {}),
      ...(subday
        ? { hour: '2-digit' as const, minute: '2-digit' as const }
        : {}),
      ...(unit === AggregationDateUnit.SECOND
        ? { second: '2-digit' as const }
        : {}),
    }).format(value + (offset ?? 0) * 60000);
  };
  const horizontal =
    presentation.layout === 'bar' && presentation.orientation === 'horizontal';
  const integerAxis = projected.series.every(series => {
    const column = plan.schema.find(column => column.alias === series.alias);
    return column?.aggregation === 'COUNT' || column?.format === 'count';
  });
  const axes = (
    <>
      <CartesianGrid vertical={false} />
      {horizontal ? (
        <>
          <XAxis
            type="number"
            allowDecimals={!integerAxis}
            tickFormatter={formatMetric}
          />
          <YAxis
            type="category"
            dataKey="x"
            width={100}
            tickFormatter={formatXTick}
          />
        </>
      ) : (
        <>
          <XAxis
            dataKey="x"
            minTickGap={32}
            type={projected.continuous ? 'number' : 'category'}
            domain={projected.continuous ? ['dataMin', 'dataMax'] : undefined}
            scale={
              projected.x?.valueType === 'datetime' && projected.continuous
                ? 'time'
                : 'auto'
            }
            tickFormatter={formatXTick}
          />
          <YAxis allowDecimals={!integerAxis} tickFormatter={formatMetric} />
        </>
      )}
    </>
  );
  const tooltip = (
    <ChartTooltip
      filterNull={false}
      content={
        <ChartTooltipContent
          labelFormatter={(_label, payload) =>
            formatX(payload[0]?.payload ? payload[0].payload.x : '', true)
          }
          formatter={(value, name, item) => {
            const series =
              presentation.layout === 'pie'
                ? projected.series[0]
                : projected.series.find(series => series.key === item.dataKey);
            const raw =
              presentation.layout === 'pie' ? item.payload?.s0 : value;
            return (
              <>
                <span>
                  {series?.tooltipTitle ??
                    series?.title ??
                    config[String(name)]?.label ??
                    String(name)}
                </span>
                <span title={String(raw)} className="fve:ml-auto fve:font-mono">
                  {formatAnalysisValue(
                    raw,
                    plan.schema.find(column => column.alias === series?.alias),
                    plan.timeZone,
                  )}{' '}
                  {series?.unit}
                  {presentation.layout === 'pie' &&
                    ` · ${formatShare(item.payload.share, raw as number)}`}
                </span>
              </>
            );
          }}
        />
      }
    />
  );
  const legend = <ChartLegend content={<ChartLegendContent />} />;
  const stackId = presentation.stacked ? 'stack' : undefined;
  const visualization = getAnalysisVisualization(presentation.layout)!;
  let chart;
  if (visualization.value === 'pie') {
    chart = (
      <PieChart accessibilityLayer>
        <Pie
          data={piePoints}
          dataKey="share"
          nameKey="x"
          innerRadius={presentation.donut ? '55%' : 0}
          isAnimationActive={false}
        >
          {projected.points.map((p, i) => (
            <Cell key={p.identity} fill={color(i)} />
          ))}
        </Pie>
        {tooltip}
      </PieChart>
    );
  } else if (visualization.value === 'line') {
    chart = (
      <LineChart accessibilityLayer data={projected.points}>
        {axes}
        {tooltip}
        {legend}
        {projected.series.map((s, i) => (
          <Line
            key={s.key}
            dataKey={s.key}
            name={s.title}
            stroke={color(i)}
            type="linear"
            connectNulls={false}
            isAnimationActive={false}
            dot={{ r: 3 }}
          />
        ))}
      </LineChart>
    );
  } else if (visualization.value === 'area') {
    chart = (
      <AreaChart accessibilityLayer data={projected.points} stackOffset="sign">
        {axes}
        {tooltip}
        {legend}
        {projected.series.map((s, i) => (
          <Area
            key={s.key}
            dataKey={s.key}
            name={s.title}
            stroke={color(i)}
            fill={color(i)}
            fillOpacity={0.2}
            type="linear"
            connectNulls={false}
            stackId={stackId}
            isAnimationActive={false}
          />
        ))}
      </AreaChart>
    );
  } else {
    chart = (
      <BarChart
        stackOffset="sign"
        accessibilityLayer
        data={projected.points}
        layout={horizontal ? 'vertical' : 'horizontal'}
      >
        {axes}
        {tooltip}
        {legend}
        {projected.series.map((s, i) => (
          <Bar
            key={s.key}
            dataKey={s.key}
            name={s.title}
            fill={color(i)}
            stackId={stackId}
            isAnimationActive={false}
          />
        ))}
      </BarChart>
    );
  }
  return (
    <section aria-label="分析图表" className="fve:@container/chart fve:min-w-0">
      <p className="fve:mb-2 fve:text-xs fve:text-muted-foreground">
        展示已返回的 {rows.length}{' '}
        个分组；不代表全量合计。使用方向键浏览图表，或切换到底部数据表查看完整值。
        {projected.x?.valueType === 'datetime' &&
          ' 未返回的时间桶不补零；当前时间桶可能尚未结束。'}
      </p>
      <div
        className={
          presentation.layout === 'pie'
            ? 'fve:grid fve:items-center fve:gap-4 fve:@min-[32rem]/chart:grid-cols-[minmax(0,1fr)_minmax(10rem,0.8fr)]'
            : undefined
        }
      >
        <ChartContainer
          config={config}
          className="fve:h-80 fve:w-full fve:aspect-auto"
        >
          {chart}
        </ChartContainer>
        {presentation.layout === 'pie' && (
          <div className="fve:min-w-0">
            <p className="fve:text-sm fve:font-medium">
              {metricColumn?.title}
              {metricColumn?.unit && ` · ${metricColumn.unit}`}
            </p>
            <p className="fve:mb-2 fve:text-xs fve:text-muted-foreground">
              占比仅基于已返回分组，显示值四舍五入。
            </p>
            <ul
              aria-label="分组数值与占比"
              className="fve:m-0 fve:max-h-80 fve:list-none fve:overflow-y-auto fve:p-0"
            >
              {piePoints.map((point, index) => (
                <li
                  key={point.identity}
                  className="fve:flex fve:items-center fve:gap-2 fve:border-b fve:py-2 fve:text-sm fve:last:border-0"
                >
                  <span
                    aria-hidden="true"
                    className="fve:size-2.5 fve:shrink-0 fve:rounded-xs"
                    style={{ background: color(index) }}
                  />
                  <span
                    className="fve:min-w-0 fve:flex-1 fve:truncate"
                    title={formatX(point.x)}
                  >
                    {formatX(point.x)}
                  </span>
                  <span className="fve:flex fve:min-w-0 fve:max-w-36 fve:flex-col fve:text-right fve:tabular-nums">
                    <span
                      className="fve:truncate fve:font-medium"
                      title={String(point.s0)}
                    >
                      {formatMetric(point.s0)}
                    </span>
                    <span className="fve:text-xs fve:text-muted-foreground">
                      {formatShare(point.share, point.s0 as number)}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
}
