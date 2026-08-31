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

import type { PagedList } from "@ahoo-wang/fetcher-wow";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { AlertCircle, RefreshCw } from "lucide-react";
import { useMemo } from "react";
import type { ExecutionFailedState } from "../../generated";
import { formatAge, formatDate } from "../../utils/dates.ts";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { useNow } from "@/hooks/useNow.ts";
import { FailedPagination } from "./FailedPagination.tsx";
import { StatusBadge } from "./StatusBadge.tsx";
import { useI18n } from "@/i18n.tsx";

const columnHelper = createColumnHelper<ExecutionFailedState>();

interface FailedTableProps {
  error?: Error;
  hasActiveFilters?: boolean;
  loading?: boolean;
  onClearFilters?: () => void;
  onPaginationChange?: (page: number, pageSize: number) => void;
  onRetry?: () => void;
  onSelect?: (state: ExecutionFailedState) => void;
  pagedList: PagedList<ExecutionFailedState>;
  pageIndex: number;
  pageSize: number;
  selectedId?: string | null;
  staleError?: Error;
}

export function FailedTable({
  error,
  hasActiveFilters,
  loading,
  onClearFilters,
  onPaginationChange,
  onRetry,
  onSelect,
  pagedList,
  pageIndex,
  pageSize,
  selectedId,
  staleError,
}: FailedTableProps) {
  const now = useNow();
  const { locale, t } = useI18n();
  const columns = useMemo(
    () => [
      columnHelper.accessor("status", {
        header: t("Status"),
        cell: (info) => <StatusBadge status={info.getValue()} />,
      }),
      columnHelper.accessor("id", {
        header: t("Execution ID"),
        cell: (info) => (
          <div className="min-w-0">
            <StatusBadge
              status={info.row.original.status}
              className="failed-table-status-inline mb-1 hidden"
            />
            <button
              type="button"
              disabled={loading}
              aria-label={t("View execution {id}", { id: info.getValue() })}
              className="block w-full truncate text-left font-medium text-slate-800 outline-none hover:text-blue-700 focus-visible:underline disabled:cursor-wait disabled:text-slate-700"
              onClick={(event) => {
                event.stopPropagation();
                if (!loading) {
                  onSelect?.(info.row.original);
                }
              }}
            >
              {info.getValue()}
            </button>
            <div className="mt-1 text-xs text-muted-foreground">
              {formatDate(info.row.original.executeAt, "HH:mm:ss")}
            </div>
          </div>
        ),
      }),
      columnHelper.accessor("function", {
        header: t("Processor / Function"),
        cell: (info) => (
          <div className="min-w-0">
            <div className="truncate text-sm text-slate-700">
              {info.getValue().processorName}
            </div>
            <div className="mt-1 truncate text-xs text-slate-900">
              {info.getValue().name}
            </div>
          </div>
        ),
      }),
      columnHelper.display({
        id: "retry",
        header: t("Retry"),
        cell: ({ row }) => (
          <span className="tabular-nums text-slate-700">
            {row.original.retryState.retries} /{" "}
            {row.original.retrySpec.maxRetries}
          </span>
        ),
      }),
      columnHelper.accessor("executeAt", {
        header: t("Age"),
        cell: (info) => (
          <span
            className="text-xs tabular-nums text-slate-700"
            title={formatDate(info.getValue(), undefined, locale)}
          >
            {formatAge(info.getValue(), now, locale)}
          </span>
        ),
      }),
    ],
    [loading, locale, now, onSelect, t],
  );

  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Table keeps its headless state local to this component.
  const table = useReactTable({
    data: pagedList.list,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: Math.max(1, Math.ceil(pagedList.total / pageSize)),
  });

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-white" aria-busy={loading}>
      {staleError ? (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900"
        >
          <span>
            {t(
              "Refresh failed: {message}. Showing the last loaded page; changes are disabled until refresh succeeds.",
              { message: staleError.message },
            )}
          </span>
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            <RefreshCw />
            {t("Retry")}
          </Button>
        </div>
      ) : null}
      <div className="failed-table-container relative min-h-0 flex-1 overflow-x-hidden overflow-y-auto px-3 pb-7">
        {loading && pagedList.list.length > 0 ? (
          <div
            role="status"
            aria-label={t("Loading page")}
            className="pointer-events-none sticky top-[42px] z-20 h-0.5 overflow-hidden bg-blue-100"
          >
            <div className="h-full w-full animate-pulse bg-blue-500 motion-reduce:animate-none" />
          </div>
        ) : null}
        <Table className="table-fixed">
          <TableHeader className="sticky top-0 z-10 bg-white/95 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="hover:bg-transparent">
                {headerGroup.headers.map((header) => (
                  <TableHead
                    key={header.id}
                    className={cn(
                      "h-[43px] px-2 text-xs font-medium text-slate-500",
                      header.column.id === "status" &&
                        "failed-table-status-column w-[78px]",
                      header.column.id === "id" && "w-[124px]",
                      header.column.id === "function" && "w-[140px]",
                      header.column.id === "retry" &&
                        "failed-table-retry hidden w-[50px] text-center sm:table-cell",
                      header.column.id === "executeAt" &&
                        "failed-table-age hidden w-[67px] text-right sm:table-cell",
                    )}
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext(),
                        )}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {loading && pagedList.list.length === 0
              ? Array.from({ length: 7 }, (_, index) => (
                  <TableRow key={index}>
                    <TableCell colSpan={5} className="h-24 px-2">
                      <Skeleton className="h-10 w-full" />
                    </TableCell>
                  </TableRow>
                ))
              : null}
            {!loading && error ? (
              <TableRow>
                <TableCell
                  colSpan={5}
                  className="h-56 whitespace-normal break-words text-center"
                >
                  <div role="alert" className="mx-auto max-w-sm">
                    <AlertCircle className="mx-auto size-5 text-red-600" />
                    <div className="mt-2 text-sm font-medium text-red-700">
                      {t("Failed to load executions")}
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {error.message}
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      className="mt-4"
                      onClick={onRetry}
                    >
                      <RefreshCw />
                      {t("Retry")}
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ) : null}
            {!loading && !error && table.getRowModel().rows.length === 0 ? (
              <TableRow>
                <TableCell
                  colSpan={5}
                  className="h-56 whitespace-normal break-words text-center"
                >
                  <div className="text-sm font-medium text-slate-700">
                    {t("No failed executions found")}
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    {t("Try another queue or adjust the filters.")}
                  </div>
                  {hasActiveFilters ? (
                    <Button
                      type="button"
                      variant="outline"
                      className="mt-4"
                      aria-label={t("Clear search filters")}
                      onClick={onClearFilters}
                    >
                      {t("Clear search")}
                    </Button>
                  ) : null}
                </TableCell>
              </TableRow>
            ) : null}
            {!error &&
              table.getRowModel().rows.map((row) => {
                const selected = row.original.id === selectedId;
                return (
                  <TableRow
                    key={row.id}
                    data-state={selected ? "selected" : undefined}
                    aria-selected={selected}
                    aria-disabled={loading || undefined}
                    className={cn(
                      "h-[82px] border-b-slate-200",
                      loading ? "cursor-wait" : "cursor-pointer",
                      selected &&
                        "rounded-md outline-1 -outline-offset-1 outline-blue-500 data-[state=selected]:bg-blue-50/70 data-[state=selected]:hover:bg-blue-50/80",
                    )}
                    onClick={() => {
                      if (!loading) {
                        onSelect?.(row.original);
                      }
                    }}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <TableCell
                        key={cell.id}
                        className={cn(
                          "px-2 align-middle",
                          cell.column.id === "status" &&
                            "failed-table-status-column",
                          cell.column.id === "retry" &&
                            "failed-table-retry hidden text-center sm:table-cell",
                          cell.column.id === "executeAt" &&
                            "failed-table-age hidden text-right sm:table-cell",
                        )}
                      >
                        {flexRender(
                          cell.column.columnDef.cell,
                          cell.getContext(),
                        )}
                      </TableCell>
                    ))}
                  </TableRow>
                );
              })}
          </TableBody>
        </Table>
      </div>

      <FailedPagination
        loading={loading}
        onPaginationChange={onPaginationChange}
        pageIndex={pageIndex}
        pageSize={pageSize}
        rowCount={pagedList.list.length}
        total={pagedList.total}
      />
    </div>
  );
}
