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
import { zhCN } from "react-day-picker/locale";
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
import { useI18n, type Locale, type Message } from "@/i18n.tsx";

interface CompleteDateRange {
  from: Date;
  to: Date;
}

function createDefaultDateRange(): CompleteDateRange {
  const to = dayjs(Date.now()).startOf("day");
  return { from: to.subtract(6, "day").toDate(), to: to.toDate() };
}

function formatDateRange(
  { from, to }: CompleteDateRange,
  locale: Locale,
): string {
  if (locale === "zh-CN") {
    const formatter = new Intl.DateTimeFormat(locale, { dateStyle: "medium" });
    return `${formatter.format(from)} – ${formatter.format(to)}`;
  }
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
} satisfies Record<RecoverableType, { color: string; label: Message }>;
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

function formatPercentage(value: number, locale: Locale): string {
  const formatted = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 1,
    style: "percent",
  }).format(value);
  return value > 0 && formatted === "0%" ? "<0.1%" : formatted;
}

function PressureStatusShare({ cluster }: { cluster: PressureCluster }) {
  const { t } = useI18n();
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
    <div aria-label={t("Failed {failed}; Prepared {prepared}", { failed, prepared })}>
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
  const { locale, t } = useI18n();
  return (
    <Table aria-label={t("Current failure pressure")} className="min-w-[48rem]">
      <TableHeader>
        <TableRow>
          <TableHead>{t("Cluster")}</TableHead>
          <TableHead className="text-right">{t("Current")}</TableHead>
          <TableHead>{t("Failed / Prepared")}</TableHead>
          <TableHead>{t("Oldest")}</TableHead>
          <TableHead>{t("Next retry")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {clusters.length === 0 ? (
          <TableRow>
            <TableCell
              colSpan={5}
              className="h-24 text-center text-muted-foreground"
            >
              {t("No active failure clusters")}
            </TableCell>
          </TableRow>
        ) : (
          clusters.map((cluster, index) => (
            <TableRow
              key={`${cluster.errorCode}:${cluster.contextName}:${cluster.processorName}:${cluster.functionName}:${cluster.functionKind}`}
              data-dominant={index === 0 ? "true" : undefined}
            >
              <TableCell data-label={t("Cluster")}>
                <div className="font-medium">{cluster.errorCode}</div>
                <div className="text-xs text-muted-foreground">
                  {cluster.contextName} · {cluster.processorName}/
                  <wbr />
                  {cluster.functionName} · {cluster.functionKind}
                </div>
              </TableCell>
              <TableCell
                data-label={t("Current")}
                className="text-right tabular-nums"
              >
                {cluster.currentCount}
              </TableCell>
              <TableCell data-label={t("Failed / Prepared")}>
                <PressureStatusShare cluster={cluster} />
              </TableCell>
              <TableCell
                data-label={t("Oldest")}
                title={formatDate(cluster.oldestExecuteAt ?? undefined, undefined, locale)}
              >
                {cluster.oldestExecuteAt
                  ? formatAge(cluster.oldestExecuteAt, now, locale)
                  : "-"}
              </TableCell>
              <TableCell data-label={t("Next retry")}>
                {formatDate(cluster.nextRetryAt ?? undefined, undefined, locale)}
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}

export default function DashboardView() {
  const { locale, t } = useI18n();
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
  const rangeLabel = formatDateRange(appliedRange, locale);
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
  const activeTotal = snapshot.summary.data?.activeTotal;
  const stockPartitionsConsistent =
    selectedActive !== undefined &&
    snapshot.summary.data !== undefined &&
    !snapshot.summary.data.stockTruncated &&
    snapshot.summary.data.selectedInRange === selectedActive;
  const summarySubsetsConsistent =
    selectedActive !== undefined &&
    snapshot.summary.data !== undefined &&
    Math.max(
      snapshot.summary.data.actionableNow,
      snapshot.summary.data.timedOut,
      snapshot.summary.data.unrecoverable,
    ) <= selectedActive;
  const scopeInsightsReady =
    stockPartitionsConsistent &&
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
    ? t("Failure concentration · Top cluster {percentage}", {
        percentage: formatPercentage(pressureShare, locale),
      })
    : t("Current failure pressure — Top 5 clusters");

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
        <span role="status" aria-label={t("Loading dashboard")} className="sr-only">
          {t("Loading dashboard")}
        </span>
      ) : null}
      <div className="dashboard-toolbar">
        <span className="dashboard-time-range-label">{t("Time range")}</span>
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
                aria-label={t("Time range: {range}", { range: rangeLabel })}
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
                aria-label={t("Date range presets")}
                variant="outline"
                spacing={0}
                className="w-full"
              >
                {([
                  ["Today", 1],
                  ["Last 7 days", 7],
                  ["Last 30 days", 30],
                ] satisfies [Message, number][]).map(([label, days]) => (
                  <ToggleGroupItem
                    key={label}
                    value={String(days)}
                    type="button"
                    className="flex-1"
                    onClick={() => applyRecentDays(Number(days))}
                  >
                    {t(label)}
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
              locale={locale === "zh-CN" ? zhCN : undefined}
            />
            {draftRange.from && !draftRange.to ? (
              <p className="px-3 pb-2 text-sm text-muted-foreground">
                {t("Select an end date.")}
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
                {t("Cancel")}
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
                {t("Apply")}
              </Button>
            </div>
          </PopoverContent>
        </Popover>
        {dashboardUpdatedAt ? (
          <span>
            {t("Updated {date}", {
              date: formatDate(dashboardUpdatedAt, undefined, locale),
            })}
          </span>
        ) : null}
        <Button
          type="button"
          variant="outline"
          aria-busy={refreshing}
          aria-label={refreshing ? t("Refreshing dashboard") : t("Refresh dashboard")}
          onClick={() => setRefreshToken((value) => value + 1)}
        >
          <RefreshCw className={refreshing ? "animate-spin" : undefined} />
          {t("Refresh")}
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
            <h2 id="dashboard-overview-title">{t("Compensation overview")}</h2>
          </CardTitle>
        </CardHeader>
        <CardContent className="dashboard-overview-content">
          <section
            role="region"
            aria-label={t("Backlog exposure")}
            className="dashboard-stock"
          >
            <h3 id="dashboard-stock-title">{t("STOCK / Backlog exposure")}</h3>
            <SectionMeta section={snapshot.summary} />
            {scopeInsightsReady && snapshot.summary.data ? (
              <>
                <dl className="dashboard-stock-metrics">
                  <div>
                    <dt>{t("Selected active")}</dt>
                    <dd>{selectedActive.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>{t("Older backlog")}</dt>
                    <dd>
                      {snapshot.summary.data.olderThanRange.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("Unrecoverable")}</dt>
                    <dd>
                      {snapshot.summary.data.unrecoverable.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("Coverage")}</dt>
                    <dd>{formatPercentage(selectedCoverage, locale)}</dd>
                  </div>
                </dl>
                <Progress
                  aria-label={t("Selected active coverage")}
                  aria-valuetext={formatPercentage(selectedCoverage, locale)}
                  value={selectedCoverage * 100}
                  className="dashboard-stock-progress"
                />
                <div className="dashboard-stock-scale">
                  <span>
                    {t("{count} selected ({percentage})", {
                      count: selectedActive.toLocaleString(),
                      percentage: formatPercentage(selectedCoverage, locale),
                    })}
                  </span>
                  <span>
                    {t("{count} older", {
                      count: snapshot.summary.data.olderThanRange.toLocaleString(),
                    })}
                  </span>
                  <span>{t("{count} newer", { count: newerThanRange.toLocaleString() })}</span>
                  <span>{t("{count} total", { count: activeTotal.toLocaleString() })}</span>
                </div>
                <Separator />
                <dl className="dashboard-stock-secondary">
                  <div>
                    <dt>{t("Actionable now")}</dt>
                    <dd>
                      {snapshot.summary.data.actionableNow.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("Timed out")}</dt>
                    <dd>{snapshot.summary.data.timedOut.toLocaleString()}</dd>
                  </div>
                </dl>
              </>
            ) : scopeInsightsLoading ? (
              <Skeleton className="h-44 w-full" />
            ) : (
              <p className="text-sm text-muted-foreground">
                {t("Backlog exposure unavailable.")}
              </p>
            )}
          </section>
          <section
            role="region"
            aria-label={t("Compensation effectiveness")}
            className="dashboard-flow"
          >
            <h3 id="dashboard-flow-title">{t("FLOW / Compensation effectiveness")}</h3>
            <SectionMeta section={trend} />
            {trend.data ? (
              <>
                <dl className="dashboard-flow-primary">
                  <div>
                    <dt>{t("New failures")}</dt>
                    <dd>{trendSummary.newFailures.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>{t("Net backlog")}</dt>
                    <dd>
                      {trendSummary.netBacklog >= 0 ? "+" : ""}
                      {trendSummary.netBacklog.toLocaleString()}
                    </dd>
                  </div>
                  <div>
                    <dt>{t("Retry success")}</dt>
                    <dd>
                      {trendSummary.retrySuccess === null
                        ? "—"
                        : formatPercentage(trendSummary.retrySuccess, locale)}
                    </dd>
                  </div>
                </dl>
                <Separator />
                <dl className="dashboard-flow-secondary">
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-2" />
                      {t("Prepared")}
                    </dt>
                    <dd>{trendSummary.prepared.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-3" />
                      {t("Retried failed")}
                    </dt>
                    <dd>{trendSummary.retriedFailed.toLocaleString()}</dd>
                  </div>
                  <div>
                    <dt>
                      <span aria-hidden="true" className="bg-chart-4" />
                      {t("Succeeded")}
                    </dt>
                    <dd>{trendSummary.succeeded.toLocaleString()}</dd>
                  </div>
                </dl>
              </>
            ) : trend.loading ? (
              <Skeleton className="h-44 w-full" />
            ) : (
              <p className="text-sm text-muted-foreground">
                {t("Compensation effectiveness unavailable.")}
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
            <h2 id="dashboard-activity-title">{t("Dashboard activity")}</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {trend.loading && !trend.data ? (
            <Skeleton className="h-56 w-full" />
          ) : trend.data ? (
            <CompensationTrendChart points={trend.data} />
          ) : (
            <p className="text-sm text-muted-foreground">
              {t("Compensation activity unavailable.")}
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
              {t("Current health for selected execution range")}
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
                title={t("Recoverability composition")}
                data={snapshot.recoverability.data.map(
                  ({ count, recoverable }) => ({
                    ...recoverabilityDisplay[recoverable],
                    label: t(recoverabilityDisplay[recoverable].label),
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
                  {t("Retry distribution is truncated and is not charted.")}
                </AlertDescription>
              </Alert>
            ) : snapshot.retries.data ? (
              <RetryDistributionChart
                data={snapshot.retries.data.buckets.map(({ count, key }) => ({
                  color: retryBucketColors[key],
                  count,
                  key,
                  label: t("{key} retries", { key }),
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
                  ? t("{count} cluster", { count: 1 })
                  : snapshot.pressure.data!.length === 5
                    ? t("Top 5 clusters")
                    : t("{count} clusters", {
                        count: snapshot.pressure.data!.length,
                      })}{" "}
                · {t("Top cluster {percentage}", {
                  percentage: formatPercentage(pressureShare, locale),
                })}
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
