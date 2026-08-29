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
import { useOutletContext } from "react-router";
import { ExchangeError } from "@ahoo-wang/fetcher";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";
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
  RetryDistributionChart,
} from "./AnalyticsCharts.tsx";
import type {
  PressureCluster,
  RetryDistribution,
} from "./analyticsQueries.ts";
import { useEventTrend } from "./useEventTrend.ts";
import {
  type AnalyticsSection,
  useSnapshotAnalytics,
} from "./useSnapshotAnalytics.ts";
import type { DashboardOutletContext } from "../App/App.tsx";

const recoverabilityDisplay = {
  [RecoverableType.RECOVERABLE]: {
    color: "#16a34a",
    label: "Recoverable",
  },
  [RecoverableType.UNKNOWN]: { color: "#f59e0b", label: "Unknown" },
  [RecoverableType.UNRECOVERABLE]: {
    color: "#dc2626",
    label: "Unrecoverable",
  },
} satisfies Record<RecoverableType, { color: string; label: string }>;
const retryBucketColors = {
  "0": "#64748b",
  "1–2": "#2563eb",
  "3–5": "#f59e0b",
  "6+": "#dc2626",
} satisfies Record<RetryDistribution["buckets"][number]["key"], string>;

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

function SectionMeta<T>({
  section,
  showUpdatedAt = false,
}: {
  section: AnalyticsSection<T>;
  showUpdatedAt?: boolean;
}) {
  return (
    <>
      {section.loading && section.data ? (
        <p role="status" className="text-xs text-muted-foreground">
          Refreshing…
        </p>
      ) : null}
      {section.error ? <SectionError error={section.error} /> : null}
      {showUpdatedAt && section.updatedAt ? (
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

function PressureStatusShare({ cluster }: { cluster: PressureCluster }) {
  const failed = formatStatusShare(cluster.failedCount, cluster.currentCount);
  const prepared = formatStatusShare(
    cluster.preparedCount,
    cluster.currentCount,
  );
  const failedWidth =
    cluster.currentCount === 0
      ? 0
      : (cluster.failedCount / cluster.currentCount) * 100;
  const preparedWidth =
    cluster.currentCount === 0
      ? 0
      : (cluster.preparedCount / cluster.currentCount) * 100;

  return (
    <div aria-label={`Failed ${failed}; Prepared ${prepared}`}>
      <div className="flex items-center gap-2 text-xs tabular-nums">
        <span>{failed}</span>
        <span>{prepared}</span>
      </div>
      <div
        aria-hidden="true"
        className="mt-1 flex h-1.5 overflow-hidden rounded-full bg-slate-100"
      >
        <span className="bg-red-600" style={{ width: `${failedWidth}%` }} />
        <span
          className="bg-blue-600"
          style={{ width: `${preparedWidth}%` }}
        />
      </div>
    </div>
  );
}

function PressureTable({ clusters }: { clusters: PressureCluster[] }) {
  const now = useNow();
  return (
    <Table aria-label="Current failure pressure" className="min-w-[48rem]">
      <TableHeader>
        <TableRow>
          <TableHead>Cluster</TableHead>
          <TableHead className="text-right">Current</TableHead>
          <TableHead>Failed / Prepared</TableHead>
          <TableHead>Oldest</TableHead>
          <TableHead>Next retry</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {clusters.length === 0 ? (
          <TableRow>
            <TableCell colSpan={5} className="h-24 text-center text-muted-foreground">
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
              <TableCell>
                <PressureStatusShare cluster={cluster} />
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

export default function DashboardView() {
  const { outcomesRange } = useOutletContext<DashboardOutletContext>();
  const [refreshToken, setRefreshToken] = useState(0);
  const snapshot = useSnapshotAnalytics(refreshToken);
  const trend = useEventTrend(outcomesRange, refreshToken);

  return (
    <div className="h-full space-y-4 overflow-y-auto p-5">
      <section
        aria-labelledby="dashboard-summary-title"
        className="dashboard-summary"
      >
        <div className="dashboard-section-heading">
          <h2 id="dashboard-summary-title">Current compensation state</h2>
          <span className="dashboard-now">Now</span>
          <div className="dashboard-refresh-status">
            {snapshot.summary.updatedAt ? (
              <span>Updated {formatDate(snapshot.summary.updatedAt)}</span>
            ) : null}
            <Button
              type="button"
              variant="outline"
              aria-label="Refresh dashboard"
              onClick={() => setRefreshToken((value) => value + 1)}
            >
              <RefreshCw /> Refresh
            </Button>
          </div>
        </div>
        <SectionMeta section={snapshot.summary} />
        {snapshot.summary.loading && !snapshot.summary.data ? (
          <Skeleton className="mt-3 h-24 w-full" />
        ) : snapshot.summary.data ? (
          <dl className="dashboard-summary-values">
            <div>
              <dt>Actionable now</dt>
              <dd>{snapshot.summary.data.actionableNow}</dd>
            </div>
            <div>
              <dt>Timed out</dt>
              <dd>{snapshot.summary.data.timedOut}</dd>
            </div>
            <div>
              <dt>Unrecoverable</dt>
              <dd>{snapshot.summary.data.unrecoverable}</dd>
            </div>
          </dl>
        ) : null}
      </section>

      <section aria-labelledby="analytics-pressure-title" className="min-w-0">
        <div className="dashboard-section-heading">
          <h2 id="analytics-pressure-title">
            Current failure pressure — Top 5 clusters
          </h2>
          <span className="dashboard-now">Now</span>
        </div>
        <SectionMeta section={snapshot.pressure} />
        {snapshot.pressure.loading && !snapshot.pressure.data ? (
          <Skeleton className="mt-3 h-64 w-full" />
        ) : snapshot.pressure.data ? (
          <div className="mt-3 rounded-lg border bg-white p-3">
            <PressureTable clusters={snapshot.pressure.data} />
          </div>
        ) : null}
      </section>

      <div
        className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1.7fr)]"
        aria-label="Dashboard signals"
      >
        <section className="rounded-lg border bg-white p-4">
          <SectionMeta section={snapshot.recoverability} />
          {snapshot.recoverability.loading && !snapshot.recoverability.data ? (
            <Skeleton className="mt-3 h-36 w-full" />
          ) : snapshot.recoverability.data ? (
            <DistributionChart
              title="Recoverability"
              description="Now"
              data={snapshot.recoverability.data.map(
                ({ count, recoverable }) => ({
                  ...recoverabilityDisplay[recoverable],
                  count,
                  key: recoverable,
                }),
              )}
            />
          ) : null}
        </section>
        <section className="rounded-lg border bg-white p-4">
          <SectionMeta section={snapshot.retries} />
          {snapshot.retries.loading && !snapshot.retries.data ? (
            <Skeleton className="mt-3 h-36 w-full" />
          ) : snapshot.retries.data?.truncated ? (
            <p className="mt-3 text-sm text-amber-800">
              Retry distribution is truncated and is not charted.
            </p>
          ) : snapshot.retries.data ? (
            <RetryDistributionChart
              data={snapshot.retries.data.buckets.map(({ count, key }) => ({
                color: retryBucketColors[key],
                count,
                key,
                label: `${key} retries`,
              }))}
            />
          ) : null}
        </section>
        <section
          aria-labelledby="analytics-history-title"
          className="rounded-lg border bg-white p-4"
        >
          <h2 id="analytics-history-title" className="text-base font-semibold">
            Compensation outcomes
            <span className="ml-2 font-normal text-muted-foreground">
              — Outcomes window: {outcomesRange}
            </span>
          </h2>
          <SectionMeta section={trend} />
          {trend.loading && !trend.data ? (
            <Skeleton className="mt-3 h-36 w-full" />
          ) : trend.data ? (
            <CompensationTrendChart points={trend.data} />
          ) : null}
        </section>
      </div>
    </div>
  );
}
