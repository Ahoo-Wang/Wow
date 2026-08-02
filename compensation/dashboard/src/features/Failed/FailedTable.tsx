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
import {
  AlertCircle,
  ChevronLeft,
  ChevronRight,
  RefreshCw,
  SlidersHorizontal,
} from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import type { ExecutionFailedState } from "../../generated";
import { formatAge, formatDate } from "../../utils/dates.ts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
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
import { StatusBadge } from "./StatusBadge.tsx";

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
  const [jumpPage, setJumpPage] = useState("");
  const now = useNow();
  const columns = useMemo(
    () => [
      columnHelper.accessor("status", {
        header: "Status",
        cell: (info) => <StatusBadge status={info.getValue()} />,
      }),
      columnHelper.accessor("id", {
        header: "Execution ID",
        cell: (info) => (
          <div className="min-w-0">
            <StatusBadge
              status={info.row.original.status}
              className="failed-table-status-inline mb-1 hidden"
            />
            <button
              type="button"
              disabled={loading}
              aria-label={`View execution ${info.getValue()}`}
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
        header: "Processor / Function",
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
        header: "Retry",
        cell: ({ row }) => (
          <span className="tabular-nums text-slate-700">
            {row.original.retryState.retries} /{" "}
            {row.original.retrySpec.maxRetries}
          </span>
        ),
      }),
      columnHelper.accessor("executeAt", {
        header: "Age",
        cell: (info) => (
          <span
            className="text-xs tabular-nums text-slate-700"
            title={formatDate(info.getValue())}
          >
            {formatAge(info.getValue(), now)}
          </span>
        ),
      }),
    ],
    [loading, now, onSelect],
  );

  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Table keeps its headless state local to this component.
  const table = useReactTable({
    data: pagedList.list,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: Math.max(1, Math.ceil(pagedList.total / pageSize)),
  });

  const firstItem = pagedList.total === 0 ? 0 : (pageIndex - 1) * pageSize + 1;
  const lastItem = Math.min(
    (pageIndex - 1) * pageSize + pagedList.list.length,
    pagedList.total,
  );
  const pageCount = Math.ceil(pagedList.total / pageSize);
  const firstVisiblePage =
    pageCount === 0
      ? 0
      : Math.min(Math.max(1, pageIndex - 1), Math.max(1, pageCount - 2));
  const visiblePages =
    pageCount === 0
      ? []
      : Array.from(
          { length: Math.min(3, pageCount) },
          (_, index) => firstVisiblePage + index,
        );

  const submitPageJump = (event: FormEvent) => {
    event.preventDefault();
    const requestedPage = Number(jumpPage);
    if (!Number.isInteger(requestedPage) || pageCount === 0) {
      return;
    }
    onPaginationChange?.(
      Math.min(Math.max(1, requestedPage), pageCount),
      pageSize,
    );
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-white" aria-busy={loading}>
      {staleError ? (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900"
        >
          <span>
            Refresh failed: {staleError.message}. Showing the last loaded page;
            changes are disabled until refresh succeeds.
          </span>
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            <RefreshCw />
            Retry
          </Button>
        </div>
      ) : null}
      <div className="failed-table-container relative min-h-0 flex-1 overflow-x-hidden overflow-y-auto px-3 pb-7">
        {loading && pagedList.list.length > 0 ? (
          <div
            role="status"
            aria-label="Loading page"
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
                      Failed to load executions
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
                      Retry
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
                    No failed executions found
                  </div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    Try another queue or adjust the filters.
                  </div>
                  {hasActiveFilters ? (
                    <Button
                      type="button"
                      variant="outline"
                      className="mt-4"
                      aria-label="Clear search filters"
                      onClick={onClearFilters}
                    >
                      Clear search
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
                        "rounded-md ring-1 ring-inset ring-blue-500 data-[state=selected]:bg-blue-50/70 data-[state=selected]:hover:bg-blue-50/80",
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

      <div className="flex h-16 items-center justify-between gap-2 border-t px-3 sm:px-5">
        <span
          role="status"
          aria-live="polite"
          className="text-sm text-slate-600 tabular-nums"
        >
          {firstItem}–{lastItem} of {pagedList.total}
        </span>
        <div className="flex items-center gap-1.5">
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="failed-pagination-control"
            aria-label="Previous page"
            disabled={pageCount === 0 || pageIndex <= 1 || loading}
            onClick={() => onPaginationChange?.(pageIndex - 1, pageSize)}
          >
            <ChevronLeft />
          </Button>
          {visiblePages.map((page) => (
            <Button
              key={page}
              type="button"
              variant="outline"
              size="icon"
              aria-label={`Page ${page}`}
              aria-current={page === pageIndex ? "page" : undefined}
              className={cn(
                "failed-pagination-control failed-table-page-button",
                page === pageIndex &&
                  "is-current border-blue-500 text-blue-600 hover:bg-blue-50 hover:text-blue-700",
              )}
              disabled={loading}
              onClick={() => onPaginationChange?.(page, pageSize)}
            >
              {page}
            </Button>
          ))}
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="failed-pagination-control"
            aria-label="Next page"
            disabled={pageCount === 0 || pageIndex >= pageCount || loading}
            onClick={() => onPaginationChange?.(pageIndex + 1, pageSize)}
          >
            <ChevronRight />
          </Button>
          <Popover>
            <PopoverTrigger asChild>
              <Button
                type="button"
                variant="outline"
                size="icon"
                className="failed-pagination-control"
                aria-label="Pagination options"
                disabled={loading}
              >
                <SlidersHorizontal />
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end" className="w-64">
              <form className="space-y-4" onSubmit={submitPageJump}>
                <div className="space-y-2">
                  <Label htmlFor="page-jump">Go to page</Label>
                  <div className="flex gap-2">
                    <Input
                      id="page-jump"
                      type="number"
                      min={1}
                      max={Math.max(1, pageCount)}
                      value={jumpPage}
                      onChange={(event) => setJumpPage(event.target.value)}
                    />
                    <Button
                      type="submit"
                      disabled={pageCount === 0 || !jumpPage}
                    >
                      Go
                    </Button>
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="rows-per-page">Rows per page</Label>
                  <select
                    id="rows-per-page"
                    aria-label="Rows per page"
                    className="h-9 w-full rounded-lg border border-input bg-white px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
                    value={pageSize}
                    onChange={(event) =>
                      onPaginationChange?.(1, Number(event.target.value))
                    }
                  >
                    {[10, 20, 50].map((size) => (
                      <option key={size} value={size}>
                        {size}
                      </option>
                    ))}
                  </select>
                </div>
              </form>
            </PopoverContent>
          </Popover>
        </div>
      </div>
    </div>
  );
}
