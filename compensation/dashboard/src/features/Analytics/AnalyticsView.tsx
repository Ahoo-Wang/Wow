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

import { RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { ExchangeError } from "@ahoo-wang/fetcher";
import { formatAge, formatDate } from "../../utils/dates.ts";
import { Button } from "@/components/ui/button";
import { useNow } from "@/hooks/useNow.ts";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  CompensationTrendChart,
  DistributionChart,
} from "./AnalyticsCharts.tsx";
import type { AnalyticsRange, PressureCluster } from "./analyticsQueries.ts";
import { useEventTrend } from "./useEventTrend.ts";
import {
  type AnalyticsSection,
  useSnapshotAnalytics,
} from "./useSnapshotAnalytics.ts";

const ranges: readonly AnalyticsRange[] = ["24h", "7d", "30d"];

function SectionError({ error }: { error: Error }) {
  const [resolved, setResolved] = useState({ error, message: error.message });
  const message = resolved.error === error ? resolved.message : error.message;

  useEffect(() => {
    if (error instanceof ExchangeError) {
      void error.exchange
        .extractResult<{ errorMsg?: unknown; message?: unknown }>()
        .then((result) => {
          const resultMessage = result.message ?? result.errorMsg;
          if (typeof resultMessage === "string") {
            setResolved({ error, message: resultMessage });
          }
        })
        .catch(() => undefined);
    }
  }, [error]);

  return (
    <p role="alert" className="text-sm text-red-700">
      {message}
    </p>
  );
}

function SectionMeta<T>({ section }: { section: AnalyticsSection<T> }) {
  return (
    <>
      {section.loading && section.data ? (
        <p role="status" className="text-xs text-muted-foreground">
          Refreshing…
        </p>
      ) : null}
      {section.error ? <SectionError error={section.error} /> : null}
      {section.updatedAt ? (
        <p className="text-xs text-muted-foreground">
          Last updated {formatDate(section.updatedAt)}
        </p>
      ) : null}
    </>
  );
}

function formatStatusShare(count: number, total: number): string {
  const percentage = total === 0 ? 0 : Math.round((count / total) * 100);
  return `${count} (${percentage}%)`;
}

function PressureTable({ clusters }: { clusters: PressureCluster[] }) {
  const now = useNow();
  return (
    <Table aria-label="Current failure pressure">
      <TableHeader>
        <TableRow>
          <TableHead>Cluster</TableHead>
          <TableHead className="text-right">Current</TableHead>
          <TableHead className="text-right">Failed</TableHead>
          <TableHead className="text-right">Prepared</TableHead>
          <TableHead>Oldest</TableHead>
          <TableHead>Next retry</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {clusters.length === 0 ? (
          <TableRow>
            <TableCell colSpan={6} className="h-24 text-center text-muted-foreground">
              No active failure clusters
            </TableCell>
          </TableRow>
        ) : (
          clusters.map((cluster) => (
            <TableRow
              key={`${cluster.errorCode}:${cluster.contextName}:${cluster.processorName}:${cluster.functionName}:${cluster.functionKind}`}
            >
              <TableCell>
                <div className="font-medium">{cluster.errorCode}</div>
                <div className="text-xs text-muted-foreground">
                  {cluster.contextName} · {cluster.processorName}/
                  {cluster.functionName} · {cluster.functionKind}
                </div>
              </TableCell>
              <TableCell className="text-right tabular-nums">{cluster.currentCount}</TableCell>
              <TableCell className="text-right tabular-nums">
                {formatStatusShare(cluster.failedCount, cluster.currentCount)}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {formatStatusShare(cluster.preparedCount, cluster.currentCount)}
              </TableCell>
              <TableCell title={formatDate(cluster.oldestExecuteAt ?? undefined)}>
                {cluster.oldestExecuteAt ? formatAge(cluster.oldestExecuteAt, now) : "-"}
              </TableCell>
              <TableCell>{formatDate(cluster.nextRetryAt ?? undefined)}</TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}

export default function AnalyticsView() {
  const [range, setRange] = useState<AnalyticsRange>("7d");
  const [refreshToken, setRefreshToken] = useState(0);
  const snapshot = useSnapshotAnalytics(refreshToken);
  const trend = useEventTrend(range, refreshToken);

  return (
    <div className="space-y-6 p-5">
      <section aria-labelledby="analytics-summary-title">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 id="analytics-summary-title" className="text-lg font-semibold">
            Current compensation state
          </h2>
          <Button
            type="button"
            variant="outline"
            onClick={() => setRefreshToken((value) => value + 1)}
            aria-label="Refresh analytics"
          >
            <RefreshCw />
            Refresh
          </Button>
        </div>
        <SectionMeta section={snapshot.summary} />
        {snapshot.summary.loading && !snapshot.summary.data ? (
          <Skeleton className="mt-3 h-24 w-full" />
        ) : snapshot.summary.data ? (
          <dl className="mt-3 grid gap-3 sm:grid-cols-3">
            <div className="rounded-lg border bg-white p-4">
              <dt className="text-sm text-muted-foreground">Actionable now</dt>
              <dd className="mt-1 text-2xl font-semibold tabular-nums">
                {snapshot.summary.data.actionableNow}
              </dd>
            </div>
            <div className="rounded-lg border bg-white p-4">
              <dt className="text-sm text-muted-foreground">Timed out</dt>
              <dd className="mt-1 text-2xl font-semibold tabular-nums">
                {snapshot.summary.data.timedOut}
              </dd>
            </div>
            <div className="rounded-lg border bg-white p-4">
              <dt className="text-sm text-muted-foreground">Unrecoverable</dt>
              <dd className="mt-1 text-2xl font-semibold tabular-nums">
                {snapshot.summary.data.unrecoverable}
              </dd>
            </div>
          </dl>
        ) : null}
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(20rem,1fr)]">
        <section aria-labelledby="analytics-pressure-title">
          <h2 id="analytics-pressure-title" className="text-lg font-semibold">
            Current failure pressure
          </h2>
          <SectionMeta section={snapshot.pressure} />
          {snapshot.pressure.loading && !snapshot.pressure.data ? (
            <Skeleton className="mt-3 h-64 w-full" />
          ) : snapshot.pressure.data ? (
            <div className="mt-3 rounded-lg border bg-white p-3">
              <PressureTable clusters={snapshot.pressure.data} />
            </div>
          ) : null}
        </section>

        <div className="space-y-6" aria-label="Current distributions">
          <section className="rounded-lg border bg-white p-4">
            <SectionMeta section={snapshot.recoverability} />
            {snapshot.recoverability.loading && !snapshot.recoverability.data ? (
              <Skeleton className="mt-3 h-64 w-full" />
            ) : snapshot.recoverability.data ? (
              <DistributionChart
                title="Recoverability distribution"
                description="Current active failure snapshots"
                data={snapshot.recoverability.data.map(({ count, recoverable }) => ({
                  color:
                    recoverable === "true"
                      ? "#16a34a"
                      : recoverable === "false"
                        ? "#dc2626"
                        : "#f59e0b",
                  count,
                  key: recoverable,
                  label:
                    recoverable === "true"
                      ? "Recoverable"
                      : recoverable === "false"
                        ? "Not recoverable"
                        : "Unknown",
                }))}
              />
            ) : null}
          </section>
          <section className="rounded-lg border bg-white p-4">
            <SectionMeta section={snapshot.retries} />
            {snapshot.retries.loading && !snapshot.retries.data ? (
              <Skeleton className="mt-3 h-64 w-full" />
            ) : snapshot.retries.data?.truncated ? (
              <p className="mt-3 text-sm text-amber-800">
                Retry distribution is truncated and is not charted.
              </p>
            ) : snapshot.retries.data ? (
              <DistributionChart
                title="Retry distribution"
                description="Current active failure snapshots"
                data={snapshot.retries.data.buckets.map(({ count, key }) => ({
                  color: "#2563eb",
                  count,
                  key,
                  label: `${key} retries`,
                }))}
              />
            ) : null}
          </section>
        </div>
      </div>

      <section aria-labelledby="analytics-history-title">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="analytics-history-title" className="text-lg font-semibold">
            Compensation outcomes
          </h2>
          <div className="flex gap-2" aria-label="History range">
            {ranges.map((item) => (
              <Button
                key={item}
                type="button"
                variant={range === item ? "default" : "outline"}
                aria-pressed={range === item}
                onClick={() => setRange(item)}
              >
                {item}
              </Button>
            ))}
          </div>
        </div>
        <SectionMeta section={trend} />
        {trend.loading && !trend.data ? (
          <Skeleton className="mt-3 h-72 w-full" />
        ) : trend.data ? (
          <div className="mt-3 rounded-lg border bg-white p-4">
            <CompensationTrendChart points={trend.data} />
          </div>
        ) : null}
      </section>
    </div>
  );
}
