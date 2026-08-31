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
import { CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts";
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "../../components/ui/chart.tsx";
import { formatDate } from "../../utils/dates.ts";
import { summarizeTrend, type TrendPoint } from "./analyticsQueries.ts";
import { useI18n } from "@/i18n.tsx";

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
  return count > 0 && percentage < 1 ? "<1%" : `${Math.round(percentage)}%`;
}

export function DistributionChart({
  data,
  description,
  title,
}: DistributionChartProps): ReactElement {
  const { t } = useI18n();
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
      <p className="dashboard-chart-total">{t("Total {total}", { total: total.toLocaleString() })}</p>
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
  const { t } = useI18n();
  const total = data.reduce((sum, { count }) => sum + count, 0);
  const rows = data.map((datum) => ({
    ...datum,
    display: `${datum.count} (${percentageLabel(datum.count, total)})`,
  }));
  const maxCount = Math.max(...rows.map(({ count }) => count), 1);

  return (
    <section aria-label={t("Retry distribution")}>
      <h3 className="font-medium">{t("Retry distribution")}</h3>
      {description ? (
        <p className="text-sm text-muted-foreground">{description}</p>
      ) : null}
      <div
        role="img"
        aria-label={t("Retry distribution: {rows}", {
          rows: rows.map(({ display, label }) => `${label} ${display}`).join(", "),
        })}
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
      <p className="dashboard-chart-total">{t("Total {total}", { total: total.toLocaleString() })}</p>
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
  const { locale, t } = useI18n();
  const localizedTrendConfig = {
    newFailures: { color: trendConfig.newFailures.color, label: t("New failures") },
    prepared: { color: trendConfig.prepared.color, label: t("Prepared") },
    retriedFailed: { color: trendConfig.retriedFailed.color, label: t("Retried failed") },
    succeeded: { color: trendConfig.succeeded.color, label: t("Succeeded") },
  } satisfies ChartConfig;
  const totals = summarizeTrend(points);
  const outcomeFlow = [
    {
      color: trendConfig.prepared.color,
      count: totals.prepared,
      key: "prepared",
      label: localizedTrendConfig.prepared.label,
    },
    {
      color: trendConfig.retriedFailed.color,
      count: totals.retriedFailed,
      key: "retriedFailed",
      label: localizedTrendConfig.retriedFailed.label,
    },
    {
      color: trendConfig.succeeded.color,
      count: totals.succeeded,
      key: "succeeded",
      label: localizedTrendConfig.succeeded.label,
    },
  ];
  const maxOutcome = Math.max(...outcomeFlow.map(({ count }) => count), 1);

  return (
    <section
      aria-label={t("Compensation activity")}
      className="dashboard-activity-chart"
    >
      <section className="dashboard-failure-inflow">
        <h3>
          {t("Failure inflow (new failures)")}
          {points.length > 1 ? t(" — daily trend") : ""}
        </h3>
        <p className="dashboard-series-label">
          <span
            aria-hidden="true"
            style={{ backgroundColor: trendConfig.newFailures.color }}
          />
          {t("New failures")}
        </p>
        {points.length > 1 ? (
          <ChartContainer
            config={localizedTrendConfig}
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
                tickFormatter={(bucket: number) =>
                  formatDate(bucket, "MM-DD", locale)
                }
              />
              <YAxis allowDecimals={false} axisLine={false} tickLine={false} />
              <ChartTooltip
                content={
                  <ChartTooltipContent
                    labelFormatter={(_, payload) =>
                      formatDate(
                        Number(payload[0]?.payload.bucket),
                        "MM-DD HH:mm",
                        locale,
                      )
                    }
                  />
                }
              />
              <Line
                dataKey="newFailures"
                dot={{ r: 2.5 }}
                name={localizedTrendConfig.newFailures.label}
                stroke={trendConfig.newFailures.color}
                strokeWidth={2}
                type="monotone"
              />
            </LineChart>
          </ChartContainer>
        ) : (
          <p className="dashboard-inflow-single">
            {t("{count} new failures", { count: totals.newFailures.toLocaleString() })}
          </p>
        )}
      </section>
      <section className="dashboard-outcome-flow">
        <h3>{t("Outcome flow (total in selected range)")}</h3>
        <div
          role="img"
          aria-label={t("Outcome flow: {rows}", {
            rows: outcomeFlow.map(({ count, label }) => `${label} ${count}`).join(", "),
          })}
          className="dashboard-outcome-flow-bars"
        >
          {outcomeFlow.map(({ color, count, key, label }) => (
            <div key={key} className="dashboard-outcome-flow-row">
              <span className="dashboard-outcome-flow-label">
                <span aria-hidden="true" style={{ backgroundColor: color }} />
                {label}
              </span>
              <span className="dashboard-outcome-flow-track">
                <span
                  aria-hidden="true"
                  style={{
                    backgroundColor: color,
                    width: `${(count / maxOutcome) * 100}%`,
                  }}
                />
              </span>
              <span className="font-mono tabular-nums">
                {count.toLocaleString()}
              </span>
            </div>
          ))}
        </div>
      </section>
      <table className="sr-only" aria-label={t("Compensation outcomes data")}>
        <thead>
          <tr>
            <th>{t("Time")}</th>
            <th>{t("New failures")}</th>
            <th>{t("Prepared")}</th>
            <th>{t("Retried failed")}</th>
            <th>{t("Succeeded")}</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.bucket}>
              <td>{formatDate(point.bucket, undefined, locale)}</td>
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
