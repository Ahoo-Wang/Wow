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

import {
  AlertCircle,
  Braces,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  History,
  RefreshCw,
} from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDate } from "@/utils/dates";
import {
  EXECUTION_HISTORY_PAGE_SIZE,
  type ExecutionEventStream,
  useExecutionHistory,
} from "./useExecutionHistory.ts";
import { useI18n } from "@/i18n.tsx";

interface ExecutionHistoryProps {
  executionId: string;
  refreshToken?: number;
}

function EventStreamCard({ stream }: { stream: ExecutionEventStream }) {
  const { locale, t } = useI18n();
  return (
    <article
      className="overflow-hidden rounded-lg border border-slate-200 bg-white"
      aria-label={t("Event stream version {version}", { version: stream.version })}
    >
      <header className="flex flex-wrap items-start justify-between gap-3 border-b bg-slate-50/80 px-4 py-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-md bg-blue-50 px-2 py-1 text-xs font-semibold text-blue-700 ring-1 ring-blue-200 ring-inset">
              {t("Version {version}", { version: stream.version })}
            </span>
            <span className="text-xs text-slate-500">
              {t(stream.body.length === 1 ? "{count} event" : "{count} events", {
                count: stream.body.length,
              })}
            </span>
          </div>
          <p
            className="mt-2 truncate font-mono text-[11px] text-slate-500"
            title={stream.id}
          >
            {t("Stream {id}", { id: stream.id })}
          </p>
        </div>
        <time className="shrink-0 text-xs tabular-nums text-slate-500">
          {formatDate(stream.createTime, undefined, locale)}
        </time>
      </header>

      <div className="divide-y divide-slate-100">
        {stream.body.map((event) => (
          <div key={event.id} className="px-4 py-3">
            <div className="flex min-w-0 items-start gap-3">
              <Braces className="mt-0.5 size-4 shrink-0 text-blue-600" />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                  <span className="break-all text-sm font-medium text-slate-900">
                    {event.name}
                  </span>
                  <span className="text-[11px] text-slate-500">
                    {t("revision {revision}", { revision: event.revision })}
                  </span>
                </div>
                <p
                  className="mt-1 truncate font-mono text-[11px] text-slate-500"
                  title={event.bodyType}
                >
                  {event.bodyType}
                </p>
                <details className="group mt-2 rounded-md border border-slate-200 bg-slate-50/60">
                  <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-700 outline-none hover:text-blue-700 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-inset">
                    {t("Event payload")}
                  </summary>
                  <pre className="max-h-72 overflow-auto border-t border-slate-200 bg-slate-950 p-3 text-[11px] leading-relaxed whitespace-pre-wrap text-slate-100">
                    {JSON.stringify(event.body, null, 2) ?? "null"}
                  </pre>
                </details>
              </div>
            </div>
          </div>
        ))}
      </div>
    </article>
  );
}

export function ExecutionHistory({
  executionId,
  refreshToken = 0,
}: ExecutionHistoryProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const { error, loadPage, loading, page, retry, settledPageIndex } =
    useExecutionHistory({
      enabled: expanded,
      executionId,
      refreshToken,
    });

  const firstItem =
    page.total === 0
      ? 0
      : (settledPageIndex - 1) * EXECUTION_HISTORY_PAGE_SIZE + 1;
  const lastItem = Math.min(firstItem + page.list.length - 1, page.total);
  const pageCount = Math.ceil(page.total / EXECUTION_HISTORY_PAGE_SIZE);

  return (
    <section
      className="flex flex-none flex-col overflow-hidden rounded-lg border bg-white shadow-sm"
      aria-labelledby="execution-history-title"
    >
      <div className="flex min-h-14 flex-wrap items-center justify-between gap-3 px-4 py-2.5">
        <div className="flex min-w-0 items-center gap-3">
          <History className="size-4 shrink-0 text-slate-500" />
          <div className="min-w-0">
            <h2
              id="execution-history-title"
              className="text-sm font-semibold text-slate-900"
            >
              {t("History")}
            </h2>
            <p className="truncate text-xs text-slate-500">
              {t("EventStream lifecycle records, newest first")}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          {expanded ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("Refresh history")}
              disabled={loading}
              onClick={() => loadPage(settledPageIndex)}
            >
              <RefreshCw className={loading ? "animate-spin" : undefined} />
            </Button>
          ) : null}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            aria-label={expanded ? t("Collapse history") : t("Expand history")}
            aria-expanded={expanded}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? t("Hide") : t("View")}
            {expanded ? <ChevronDown /> : <ChevronRight />}
          </Button>
        </div>
      </div>

      {expanded ? (
        <div className="border-t bg-slate-50/50 p-3 sm:p-4">
          {loading && page.list.length === 0 ? (
            <div
              role="status"
              aria-label={t("Loading execution history")}
              className="space-y-3"
            >
              <Skeleton className="h-28 w-full" />
              <Skeleton className="h-28 w-full" />
            </div>
          ) : null}

          {!loading && error && page.list.length === 0 ? (
            <div
              role="alert"
              className="rounded-lg border border-red-200 bg-red-50 p-6 text-center"
            >
              <AlertCircle className="mx-auto size-5 text-red-600" />
              <p className="mt-2 text-sm font-medium text-red-700">
                {t("Failed to load history")}
              </p>
              <p className="mt-1 text-xs text-red-700/80">{error.message}</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-4 bg-white"
                aria-label={t("Retry history")}
                onClick={retry}
              >
                <RefreshCw />
                {t("Retry")}
              </Button>
            </div>
          ) : null}

          {!loading && !error && page.list.length === 0 ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-8 text-center">
              <AlertCircle className="mx-auto size-5 text-amber-700" />
              <p className="mt-2 text-sm font-medium text-amber-900">
                {t("History unavailable")}
              </p>
              <p className="mt-1 text-xs text-amber-800">
                {t(
                  "The configured event storage did not expose EventStream records for this existing execution. Verify that it supports EventStream queries.",
                )}
              </p>
            </div>
          ) : null}

          {!loading && error && page.list.length > 0 ? (
            <div
              role="alert"
              className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3"
            >
              <div className="flex min-w-0 items-start gap-2">
                <AlertCircle className="mt-0.5 size-4 shrink-0 text-amber-700" />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-amber-900">
                    {t("Failed to load history page")}
                  </p>
                  <p className="text-xs text-amber-800">
                    {t("Showing page {page}. {message}", {
                      page: settledPageIndex,
                      message: error.message,
                    })}
                  </p>
                </div>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="bg-white"
                aria-label={t("Retry history")}
                onClick={retry}
              >
                <RefreshCw />
                {t("Retry")}
              </Button>
            </div>
          ) : null}

          {page.list.length > 0 ? (
            <div className="space-y-3" aria-busy={loading}>
              {loading ? (
                <div
                  role="status"
                  aria-label={t("Loading history page")}
                  className="h-0.5 overflow-hidden bg-blue-100"
                >
                  <div className="h-full w-full animate-pulse bg-blue-500 motion-reduce:animate-none" />
                </div>
              ) : null}
              {page.list.map((stream) => (
                <EventStreamCard key={stream.id} stream={stream} />
              ))}
            </div>
          ) : null}

          {page.total > 0 ? (
            <div className="mt-4 flex items-center justify-between gap-3 border-t pt-3">
              <span className="text-xs tabular-nums text-slate-500">
                {t("{first}–{last} of {total}", {
                  first: firstItem,
                  last: lastItem,
                  total: page.total,
                })}
              </span>
              <div className="flex items-center gap-1.5">
                <Button
                  type="button"
                  variant="outline"
                  size="icon-sm"
                  aria-label={t("Previous history page")}
                  disabled={loading || settledPageIndex <= 1}
                  onClick={() => loadPage(settledPageIndex - 1)}
                >
                  <ChevronLeft />
                </Button>
                <span className="min-w-14 text-center text-xs tabular-nums text-slate-600">
                  {settledPageIndex} / {Math.max(1, pageCount)}
                </span>
                <Button
                  type="button"
                  variant="outline"
                  size="icon-sm"
                  aria-label={t("Next history page")}
                  disabled={loading || settledPageIndex >= pageCount}
                  onClick={() => loadPage(settledPageIndex + 1)}
                >
                  <ChevronRight />
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
