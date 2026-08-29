/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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

import type { ReactElement } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  Line,
  LineChart,
  Pie,
  PieChart,
  XAxis,
  YAxis,
} from "recharts";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "../../components/ui/chart.tsx";
import { formatDate } from "../../utils/dates.ts";
import type { TrendPoint } from "./analyticsQueries.ts";

interface DistributionDatum {
  color: string;
  count: number;
  key: string;
  label: string;
}

interface DistributionChartProps {
  data: DistributionDatum[];
  description: string;
  title: string;
}

function percentageLabel(count: number, total: number): string {
  if (total === 0) {
    return "0%";
  }
  const percentage = (count / total) * 100;
  return count > 0 && percentage < 1
    ? "<1%"
    : `${Math.round(percentage)}%`;
}

export function DistributionChart({
  data,
  description,
  title,
}: DistributionChartProps): ReactElement {
  const total = data.reduce((sum, { count }) => sum + count, 0);
  const config = Object.fromEntries(
    data.map(({ color, key, label }) => [key, { color, label }]),
  ) satisfies ChartConfig;

  return (
    <section aria-label={title}>
      <h3 className="font-medium">{title}</h3>
      <p className="text-sm text-muted-foreground">{description}</p>
      <ChartContainer config={config} className="h-44 w-full aspect-auto">
        <PieChart accessibilityLayer>
          <Pie data={data} dataKey="count" nameKey="label">
            {data.map(({ color, key }) => (
              <Cell key={key} fill={color} />
            ))}
          </Pie>
        </PieChart>
      </ChartContainer>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        {data.map(({ color, count, key, label }) => {
          return (
            <div key={key} className="flex items-center justify-between gap-2">
              <dt className="flex items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className="size-2 rounded-full"
                  style={{ backgroundColor: color }}
                />
                {label}
              </dt>
              <dd className="font-mono tabular-nums">
                {count} ({percentageLabel(count, total)})
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

export function RetryDistributionChart({
  data,
}: {
  data: DistributionDatum[];
}): ReactElement {
  const total = data.reduce((sum, { count }) => sum + count, 0);
  const rows = data.map((datum) => ({
    ...datum,
    display: `${datum.count} (${percentageLabel(datum.count, total)})`,
  }));
  const config = {
    count: { color: "#2563eb", label: "Executions" },
  } satisfies ChartConfig;

  return (
    <section aria-label="Retry distribution">
      <h3 className="font-medium">Retry distribution</h3>
      <p className="text-sm text-muted-foreground">
        Current active failure snapshots
      </p>
      <ChartContainer config={config} className="h-44 w-full aspect-auto">
        <BarChart
          accessibilityLayer
          data={rows}
          layout="vertical"
          margin={{ left: 0, right: 88 }}
        >
          <XAxis type="number" hide />
          <YAxis
            dataKey="label"
            type="category"
            width={72}
            axisLine={false}
            tickLine={false}
          />
          <Bar dataKey="count" minPointSize={2} radius={4} maxBarSize={18}>
            {rows.map(({ color, key }) => (
              <Cell key={key} fill={color} />
            ))}
            <LabelList
              dataKey="display"
              position="right"
              className="fill-foreground font-mono tabular-nums"
              fontSize={11}
            />
          </Bar>
        </BarChart>
      </ChartContainer>
      <table className="sr-only" aria-label="Retry distribution data">
        <thead>
          <tr>
            <th>Retries</th>
            <th>Count</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ display, key, label }) => (
            <tr key={key}>
              <td>{label}</td>
              <td>{display}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

const trendConfig = {
  newFailures: { color: "#dc2626", label: "New failures" },
  prepared: { color: "#2563eb", label: "Prepared" },
  retriedFailed: { color: "#f59e0b", label: "Retried failed" },
  succeeded: { color: "#16a34a", label: "Succeeded" },
} satisfies ChartConfig;

export function CompensationTrendChart({
  points,
}: {
  points: TrendPoint[];
}): ReactElement {
  return (
    <section aria-label="Compensation outcomes trend">
      <ChartContainer config={trendConfig} className="h-56 w-full aspect-auto">
        <LineChart accessibilityLayer data={points}>
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="bucket"
            tickFormatter={(bucket: number) => formatDate(bucket, "MM-DD HH:mm")}
          />
          <YAxis allowDecimals={false} />
          <ChartTooltip
            content={
              <ChartTooltipContent
                labelFormatter={(_, payload) =>
                  formatDate(Number(payload[0]?.payload.bucket), "MM-DD HH:mm")
                }
              />
            }
          />
          <ChartLegend content={<ChartLegendContent />} />
          {Object.entries(trendConfig).map(([key, { color }]) => (
            <Line
              key={key}
              dataKey={key}
              dot={false}
              name={trendConfig[key as keyof typeof trendConfig].label}
              stroke={color}
              type="monotone"
            />
          ))}
        </LineChart>
      </ChartContainer>
      <table className="sr-only" aria-label="Compensation outcomes data">
        <thead>
          <tr>
            <th>Time</th>
            <th>New failures</th>
            <th>Prepared</th>
            <th>Retried failed</th>
            <th>Succeeded</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.bucket}>
              <td>{formatDate(point.bucket)}</td>
              <td>{point.newFailures}</td>
              <td>{point.prepared}</td>
              <td>{point.retriedFailed}</td>
              <td>{point.succeeded}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
