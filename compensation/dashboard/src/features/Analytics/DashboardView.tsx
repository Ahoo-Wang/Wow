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

import dayjs from "dayjs";
import { CalendarDays, CircleAlert, RefreshCw } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { ExchangeError } from "@ahoo-wang/fetcher";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";
import type { DateRange } from "react-day-picker";
import { formatAge, formatDate } from "../../utils/dates.ts";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Calendar } from "@/components/ui/calendar";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { useNow } from "@/hooks/useNow.ts";
import { Skeleton } from "@/components/ui/skeleton";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  CompensationTrendChart,
  DistributionChart,
  RetryDistributionChart,
} from "./AnalyticsCharts.tsx";
import type { PressureCluster, RetryDistribution } from "./analyticsQueries.ts";
import {
  createTrendWindow,
  MAX_TREND_DAYS,
  summarizeTrend,
} from "./analyticsQueries.ts";
import { useEventTrend } from "./useEventTrend.ts";
import {
  type AnalyticsSection,
  useSnapshotAnalytics,
} from "./useSnapshotAnalytics.ts";

interface CompleteDateRange {
  from: Date;
  to: Date;
}

function createDefaultDateRange(): CompleteDateRange {
  const to = dayjs(Date.now()).startOf("day");
  return { from: to.subtract(6, "day").toDate(), to: to.toDate() };
}

function formatDateRange({ from, to }: CompleteDateRange): string {
  return `${dayjs(from).format("YYYY-MM-DD")} – ${dayjs(to).format("YYYY-MM-DD")}`;
}

const recoverabilityDisplay = {
  [RecoverableType.RECOVERABLE]: {
    color: "var(--chart-4)",
    label: "Recoverable",
  },
  [RecoverableType.UNKNOWN]: { color: "var(--chart-3)", label: "Unknown" },
  [RecoverableType.UNRECOVERABLE]: {
    color: "var(--chart-1)",
    label: "Unrecoverable",
  },
} satisfies Record<RecoverableType, { color: string; label: string }>;
const retryBucketColors = {
  "0": "var(--chart-5)",
  "1–2": "var(--chart-2)",
  "3–5": "var(--chart-3)",
  "6+": "var(--chart-1)",
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
    <Alert variant="destructive" className="dashboard-section-error">
      <CircleAlert />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}

function SectionMeta<T>({ section }: { section: AnalyticsSection<T> }) {
  return section.error ? <SectionError error={section.error} /> : null;
}

function formatStatusShare(count: number, total: number): string {
  const percentage = total === 0 ? 0 : Math.round((count / total) * 100);
  return `${count} (${percentage}%)`;
}

const percentageFormatter = new Intl.NumberFormat("en-US", {
  maximumFractionDigits: 1,
  style: "percent",
});

function formatPercentage(value: number): string {
  const formatted = percentageFormatter.format(value);
  return value > 0 && formatted === "0%" ? "<0.1%" : formatted;
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
        className="mt-1 flex h-1.5 overflow-hidden rounded-full bg-muted"
      >
        <span className="bg-chart-1" style={{ width: `${failedWidth}%` }} />
        <span className="bg-chart-2" style={{ width: `${preparedWidth}%` }} />
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
            <TableCell
              colSpan={5}
              className="h-24 text-center text-muted-foreground"
            >
              No active failure clusters
            </TableCell>
          </TableRow>
        ) : (
          clusters.map((cluster, index) => (
            <TableRow
              key={`${cluster.errorCode}:${cluster.contextName}:${cluster.processorName}:${cluster.functionName}:${cluster.functionKind}`}
              data-dominant={index === 0 ? "true" : undefined}
            >
              <TableCell data-label="Cluster">
                <div className="font-medium">{cluster.errorCode}</div>
                <div className="text-xs text-muted-foreground">
                  {cluster.contextName} · {cluster.processorName}/
                  <wbr />
                  {cluster.functionName} · {cluster.functionKind}
                </div>
              </TableCell>
              <TableCell
                data-label="Current"
                className="text-right tabular-nums"
              >
                {cluster.currentCount}
              </TableCell>
              <TableCell data-label="Failed / Prepared">
                <PressureStatusShare cluster={cluster} />
              </TableCell>
              <TableCell
                data-label="Oldest"
                title={formatDate(cluster.oldestExecuteAt ?? undefined)}
              >
                {cluster.oldestExecuteAt
                  ? formatAge(cluster.oldestExecuteAt, now)
                  : "-"}
              </TableCell>
              <TableCell data-label="Next retry">
                {formatDate(cluster.nextRetryAt ?? undefined)}
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}

export default function DashboardView() {
  const [appliedRange, setAppliedRange] = useState<CompleteDateRange>(
    createDefaultDateRange,
  );
  const [draftRange, setDraftRange] = useState<DateRange>(appliedRange);
  const [rangeOpen, setRangeOpen] = useState(false);
  const [refreshToken, setRefreshToken] = useState(0);
  const timeZone = useMemo(
    () => Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
    [],
  );
  const window = useMemo(
    () => createTrendWindow(appliedRange.from, appliedRange.to, timeZone),
    [appliedRange, timeZone],
  );
  const rangeLabel = formatDateRange(appliedRange);
  const snapshot = useSnapshotAnalytics(window, refreshToken);
  const trend = useEventTrend(window, refreshToken);
  const sections = [
    snapshot.summary,
    snapshot.pressure,
    snapshot.recoverability,
    snapshot.retries,
    trend,
  ];
  const refreshing = sections.some(
    (section) => section.loading && section.data !== undefined,
  );
  const initialLoading = sections.every(
    (section) => section.loading && !section.data,
  );
  const dashboardUpdatedAt = sections.every(
    (section) => section.updatedAt !== undefined,
  )
    ? Math.min(...sections.map((section) => section.updatedAt!))
    : undefined;

  const selectedActive = snapshot.recoverability.data?.reduce(
    (total, { count }) => total + count,
    0,
  );
  const activeTotal =
    selectedActive !== undefined && snapshot.summary.data
      ? selectedActive +
        snapshot.summary.data.olderThanRange +
        snapshot.summary.data.newerThanRange
      : undefined;
  const summarySubsetsConsistent =
    selectedActive !== undefined &&
    snapshot.summary.data !== undefined &&
    Math.max(
      snapshot.summary.data.actionableNow,
      snapshot.summary.data.timedOut,
      snapshot.summary.data.unrecoverable,
    ) <= selectedActive;
  const scopeInsightsReady =
    summarySubsetsConsistent &&
    selectedActive !== undefined &&
    activeTotal !== undefined &&
    snapshot.summary.updatedAt !== undefined &&
    snapshot.summary.updatedAt === snapshot.recoverability.updatedAt &&
    !snapshot.summary.error &&
    !snapshot.recoverability.error;
  const scopeInsightsLoading =
    !scopeInsightsReady &&
    (snapshot.summary.loading || snapshot.recoverability.loading);
  const selectedCoverage =
    scopeInsightsReady && activeTotal > 0 ? selectedActive / activeTotal : 0;
  const newerThanRange =
    scopeInsightsReady && snapshot.summary.data
      ? snapshot.summary.data.newerThanRange
      : 0;
  const pressureInsightsReady =
    Boolean(snapshot.pressure.data?.length) &&
    Boolean(selectedActive) &&
    snapshot.pressure.data![0].currentCount <= selectedActive! &&
    snapshot.pressure.updatedAt !== undefined &&
    snapshot.pressure.updatedAt === snapshot.recoverability.updatedAt &&
    !snapshot.pressure.error &&
    !snapshot.recoverability.error;
  const trendSummary = summarizeTrend(trend.data ?? []);
  const pressureShare = pressureInsightsReady
    ? snapshot.pressure.data![0].currentCount / selectedActive!
    : 0;
  const pressureTitle = pressureInsightsReady
    ? `Failure concentration · Top cluster ${formatPercentage(pressureShare)}`
    : "Current failure pressure — Top 5 clusters";

  const applyRecentDays = (days: number) => {
    const to = dayjs(Date.now()).startOf("day");
    setAppliedRange({
      from: to.subtract(days - 1, "day").toDate(),
      to: to.toDate(),
    });
    setRangeOpen(false);
  };

  return (
    <div className="dashboard-view">
      {initialLoading ? (
        <span role="status" aria-label="Loading dashboard" className="sr-only">
          Loading dashboard
        </span>
      ) : null}
      <div className="dashboard-toolbar">
        <span className="dashboard-time-range-label">Time range</span>
        <Popover
          open={rangeOpen}
          onOpenChange={(open) => {
            setRangeOpen(open);
            if (open) {
              setDraftRange(appliedRange);
            }
          }}
        >
          <PopoverTrigger
            render={
              <Button
                type="button"
                variant="outline"
                className="dashboard-time-range-button"
                aria-label={`Time range: ${rangeLabel}`}
              />
            }
          >
            <CalendarDays />
            {rangeLabel}
          </PopoverTrigger>
          <PopoverContent
            align="end"
            className="w-[min(21.5rem,calc(100vw-1rem))] p-0 [&_[data-slot=calendar]]:w-full"
          >
            <div className="border-b p-2">
              <ToggleGroup
                aria-label="Date range presets"
                variant="outline"
                spacing={0}
                className="w-full"
              >
                {[
                  ["Today", 1],
                  ["Last 7 days", 7],
                  ["Last 30 days", 30],
                ].map(([label, days]) => (
                  <ToggleGroupItem
                    key={label}
                    value={String(days)}
                    type="button"
                    className="flex-1"
                    onClick={() => applyRecentDays(Number(days))}
                  >
                    {label}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </div>
            <Calendar
              mode="range"
              max={MAX_TREND_DAYS - 1}
              required
              resetOnSelect
              selected={draftRange}
              onSelect={setDraftRange}
              defaultMonth={draftRange.from}
              timeZone={timeZone}
            />
            {draftRange.from && !draftRange.to ? (
              <p className="px-3 pb-2 text-sm text-muted-foreground">
                Select an end date.
              </p>
            ) : null}
            <div className="flex justify-end gap-2 border-t p-2">
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setDraftRange(appliedRange);
                  setRangeOpen(false);
                }}
              >
                Cancel
              </Button>
              <Button
                type="button"
                disabled={!draftRange.from || !draftRange.to}
                onClick={() => {
                  if (draftRange.from && draftRange.to) {
                    setAppliedRange({
                      from: draftRange.from,
                      to: draftRange.to,
                    });
                    setRangeOpen(false);
                  }
                }}
              >
                Apply
              </Button>
            </div>
          </PopoverContent>
        </Popover>
        {dashboardUpdatedAt ? (
          <span>Updated {formatDate(dashboardUpdatedAt)}</span>
        ) : null}
        <Button
          type="button"
          variant="outline"
          aria-busy={refreshing}
          aria-label={refreshing ? "Refreshing dashboard" : "Refresh dashboard"}
          onClick={() => setRefreshToken((value) => value + 1)}
        >
          <RefreshCw className={refreshing ? "animate-spin" : undefined} />
          Refresh
        </Button>
      </div>

      <Card
        size="sm"
        role="region"
        aria-labelledby="dashboard-overview-title"
        className="dashboard-overview"
      >
        <CardHeader className="sr-only">
          <CardTitle>
            <h2 id="dashboard-overview-title">Compensation overview</h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="dashboard-overview-content">
          <section
            role="region"
            aria-label="Backlog exposure"
            className="dashboard-stock"
          >
            <h3 id="dashboard-stock-title">STOCK / Backlog exposure</h3>
            <SectionMeta section={snapshot.summary} />
            {scopeInsightsReady && snapshot.summary.data ? (
              <>
                <dl className="dashboard-stock-metrics">
                  <div>
                    <dt>Selected active</dt>
                    <dd>{selectedActive.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>Older backlog</dt>
                    <dd>
                      {snapshot.summary.data.olderThanRange.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>Unrecoverable</dt>
                    <dd>
                      {snapshot.summary.data.unrecoverable.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>Coverage</dt>
                    <dd>{formatPercentage(selectedCoverage)}</dd>
                  </div>
                </dl>
                <Progress
                  aria-label="Selected active coverage"
                  aria-valuetext={formatPercentage(selectedCoverage)}
                  value={selectedCoverage * 100}
                  className="dashboard-stock-progress"
                />
                <div className="dashboard-stock-scale">
                  <span>
                    {selectedActive.toLocaleString()} selected (
                    {formatPercentage(selectedCoverage)})
                  </span>
                  <span>
                    {snapshot.summary.data.olderThanRange.toLocaleString()} older
                  </span>
                  <span>{newerThanRange.toLocaleString()} newer</span>
                  <span>{activeTotal.toLocaleString()} total</span>
                </div>
                <Separator />
                <dl className="dashboard-stock-secondary">
                  <div>
                    <dt>Actionable now</dt>
                    <dd>
                      {snapshot.summary.data.actionableNow.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>Timed out</dt>
                    <dd>{snapshot.summary.data.timedOut.toLocaleString()}</dd>
                  </div>
                </dl>
              </>
            ) : scopeInsightsLoading ? (
              <Skeleton className="h-44 w-full" />
            ) : (
              <p className="text-sm text-muted-foreground">
                Backlog exposure unavailable.
              </p>
            )}
          </section>
          <section
            role="region"
            aria-label="Compensation effectiveness"
            className="dashboard-flow"
          >
            <h3 id="dashboard-flow-title">FLOW / Compensation effectiveness</h3>
            <SectionMeta section={trend} />
            {trend.data ? (
              <>
                <dl className="dashboard-flow-primary">
                  <div>
                    <dt>New failures</dt>
                    <dd>{trendSummary.newFailures.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>Net backlog</dt>
                    <dd>
                      {trendSummary.netBacklog >= 0 ? "+" : ""}
                      {trendSummary.netBacklog.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>Retry success</dt>
                    <dd>
                      {trendSummary.retrySuccess === null
                        ? "—"
                        : formatPercentage(trendSummary.retrySuccess)}
                    </dd>
                  </div>
                </dl>
                <Separator />
                <dl className="dashboard-flow-secondary">
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-2" />
                      Prepared
                    </dt>
                    <dd>{trendSummary.prepared.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-3" />
                      Retried failed
                    </dt>
                    <dd>{trendSummary.retriedFailed.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-4" />
                      Succeeded
                    </dt>
                    <dd>{trendSummary.succeeded.toLocaleString()}</dd>
                  </div>
                </dl>
              </>
            ) : trend.loading ? (
              <Skeleton className="h-44 w-full" />
            ) : (
              <p className="text-sm text-muted-foreground">
                Compensation effectiveness unavailable.
              </p>
            )}
          </section>
        </CardContent>
      </Card>

      <Card
        size="sm"
        role="region"
        aria-labelledby="dashboard-activity-title"
        className="dashboard-activity"
      >
        <CardHeader className="sr-only">
          <CardTitle>
            <h2 id="dashboard-activity-title">Dashboard activity</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {trend.loading && !trend.data ? (
            <Skeleton className="h-56 w-full" />
          ) : trend.data ? (
            <CompensationTrendChart points={trend.data} />
          ) : (
            <p className="text-sm text-muted-foreground">
              Compensation activity unavailable.
            </p>
          )}
        </CardContent>
      </Card>

      <Card
        size="sm"
        role="region"
        aria-labelledby="dashboard-health-title"
        className="dashboard-health"
      >
        <CardHeader>
          <CardTitle>
            <h2 id="dashboard-health-title">
              Current health for selected execution range
            </h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="dashboard-health-content">
          <section>
            <SectionMeta section={snapshot.recoverability} />
            {snapshot.recoverability.loading &&
            !snapshot.recoverability.data ? (
              <Skeleton className="h-24 w-full" />
            ) : snapshot.recoverability.data ? (
              <DistributionChart
                title="Recoverability composition"
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
          <Separator
            orientation="vertical"
            className="dashboard-health-separator"
          />
          <section>
            <SectionMeta section={snapshot.retries} />
            {snapshot.retries.loading && !snapshot.retries.data ? (
              <Skeleton className="h-24 w-full" />
            ) : snapshot.retries.data?.truncated ? (
              <Alert>
                <AlertDescription>
                  Retry distribution is truncated and is not charted.
                </AlertDescription>
              </Alert>
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
        </CardContent>
      </Card>

      <Card
        size="sm"
        role="region"
        aria-labelledby="dashboard-pressure-title"
        className="dashboard-pressure"
      >
        <CardHeader>
          <CardTitle>
            <h2 id="dashboard-pressure-title">{pressureTitle}</h2>
          </CardTitle>
          {pressureInsightsReady ? (
            <CardAction className="max-sm:col-span-2 max-sm:row-start-2 max-sm:justify-self-start">
              <Badge variant="outline">
                {snapshot.pressure.data!.length === 1
                  ? "1 cluster"
                  : snapshot.pressure.data!.length === 5
                    ? "Top 5 clusters"
                    : `${snapshot.pressure.data!.length} clusters`}{" "}
                · Top cluster {formatPercentage(pressureShare)}
              </Badge>
            </CardAction>
          ) : null}
        </CardHeader>
        <CardContent>
          <SectionMeta section={snapshot.pressure} />
          {snapshot.pressure.loading && !snapshot.pressure.data ? (
            <Skeleton className="h-64 w-full" />
          ) : snapshot.pressure.data ? (
            <div className="dashboard-pressure-table">
              <PressureTable clusters={snapshot.pressure.data} />
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}
