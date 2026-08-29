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
  CartesianGrid,
  Line,
  LineChart,
  XAxis,
  YAxis,
} from "recharts";
import {
  ChartContainer,
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
  description?: string;
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
  const labels = data.map(
    ({ count, label }) =>
      `${label} ${count} (${percentageLabel(count, total)})`,
  );

  return (
    <section aria-label={title}>
      <h3 className="font-medium">{title}</h3>
      {description ? (
        <p className="text-sm text-muted-foreground">{description}</p>
      ) : null}
      <div
        role="img"
        aria-label={`${title}: ${labels.join(", ")}`}
        className="mt-3 flex h-7 overflow-hidden rounded-sm bg-muted"
      >
        {data.map(({ color, count, key }) => (
          <span
            key={key}
            aria-hidden="true"
            className="min-w-0 basis-0"
            style={{ backgroundColor: color, flexGrow: count }}
          />
        ))}
      </div>
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
      <p className="dashboard-chart-total">Total {total.toLocaleString()}</p>
    </section>
  );
}

export function RetryDistributionChart({
  data,
  description,
}: {
  data: DistributionDatum[];
  description?: string;
}): ReactElement {
  const total = data.reduce((sum, { count }) => sum + count, 0);
  const rows = data.map((datum) => ({
    ...datum,
    display: `${datum.count} (${percentageLabel(datum.count, total)})`,
  }));
  const maxCount = Math.max(...rows.map(({ count }) => count), 1);

  return (
    <section aria-label="Retry distribution">
      <h3 className="font-medium">Retry distribution</h3>
      {description ? (
        <p className="text-sm text-muted-foreground">{description}</p>
      ) : null}
      <div
        role="img"
        aria-label={`Retry distribution: ${rows
          .map(({ display, label }) => `${label} ${display}`)
          .join(", ")}`}
        className="mt-2 grid gap-1.5"
      >
        {rows.map(({ color, count, display, key, label }) => (
          <div
            key={key}
            className="grid grid-cols-[5.5rem_minmax(0,1fr)_auto] items-center gap-2 text-sm"
          >
            <span className="text-right text-muted-foreground">{label}</span>
            <span className="h-2 overflow-hidden rounded-full bg-muted">
              <span
                className="block h-full rounded-full"
                style={{
                  backgroundColor: color,
                  minWidth: count > 0 ? 2 : 0,
                  width: `${(count / maxCount) * 100}%`,
                }}
              />
            </span>
            <span className="font-mono tabular-nums">{display}</span>
          </div>
        ))}
      </div>
      <p className="dashboard-chart-total">Total {total.toLocaleString()}</p>
    </section>
  );
}

const trendConfig = {
  newFailures: { color: "var(--chart-1)", label: "New failures" },
  prepared: { color: "var(--chart-2)", label: "Prepared" },
  retriedFailed: { color: "var(--chart-3)", label: "Retried failed" },
  succeeded: { color: "var(--chart-4)", label: "Succeeded" },
} satisfies ChartConfig;

export function CompensationTrendChart({
  points,
}: {
  points: TrendPoint[];
}): ReactElement {
  const singlePoint = points.length === 1 ? points[0] : undefined;
  const latestPoint = points.at(-1);

  return (
    <section
      aria-label="Compensation outcomes trend"
      className="dashboard-trend-chart"
    >
      {singlePoint ? (
        <dl
          aria-label={`Compensation outcomes for ${formatDate(singlePoint.bucket, "MM-DD")}`}
          className="dashboard-trend-single grid min-h-0 flex-1 grid-cols-2 gap-2 py-2 text-sm sm:grid-cols-4"
        >
          {Object.entries(trendConfig).map(([key, { color, label }]) => {
            const seriesKey = key as keyof typeof trendConfig;
            return (
              <div
                key={key}
                className="flex min-w-0 flex-col justify-center rounded-md bg-muted/50 px-3 py-2"
              >
                <dt className="flex items-center gap-1.5 text-muted-foreground">
                  <span
                    aria-hidden="true"
                    className="size-2 rounded-sm"
                    style={{ backgroundColor: color }}
                  />
                  {label}
                </dt>
                <dd className="mt-1 text-xl font-semibold tabular-nums">
                  {singlePoint[seriesKey]}
                </dd>
              </div>
            );
          })}
        </dl>
      ) : (
        <>
          {latestPoint ? (
            <dl
              aria-label={`Latest outcomes for ${formatDate(latestPoint.bucket, "MM-DD")}`}
              className="dashboard-trend-summary"
            >
              {Object.entries(trendConfig).map(([key, { color, label }]) => {
                const seriesKey = key as keyof typeof trendConfig;
                return (
                  <div key={key}>
                    <dt>
                      <span
                        aria-hidden="true"
                        style={{ backgroundColor: color }}
                      />
                      {label}
                    </dt>
                    <dd>{latestPoint[seriesKey].toLocaleString()}</dd>
                  </div>
                );
              })}
            </dl>
          ) : null}
          <ChartContainer
            config={trendConfig}
            className="min-h-0 flex-1 w-full py-2 text-sm aspect-auto"
          >
            <LineChart
              accessibilityLayer
              data={points}
              margin={{ left: 0, right: 12, top: 8 }}
            >
              <CartesianGrid vertical={false} />
              <XAxis
                dataKey="bucket"
                axisLine={false}
                tickLine={false}
                tickMargin={8}
                tickFormatter={(bucket: number) => formatDate(bucket, "MM-DD")}
              />
              <YAxis allowDecimals={false} axisLine={false} tickLine={false} />
              <ChartTooltip
                content={
                  <ChartTooltipContent
                    labelFormatter={(_, payload) =>
                      formatDate(Number(payload[0]?.payload.bucket), "MM-DD HH:mm")
                    }
                  />
                }
              />
              {Object.entries(trendConfig).map(([key, { color }]) => (
                <Line
                  key={key}
                  dataKey={key}
                  dot={{ r: 2.5 }}
                  name={trendConfig[key as keyof typeof trendConfig].label}
                  stroke={color}
                  strokeWidth={2}
                  type="monotone"
                />
              ))}
            </LineChart>
          </ChartContainer>
        </>
      )}
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
