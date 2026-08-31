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

import { ChevronLeft, ChevronRight, SlidersHorizontal } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { useI18n } from "@/i18n.tsx";

interface FailedPaginationProps {
  loading?: boolean;
  onPaginationChange?: (page: number, pageSize: number) => void;
  pageIndex: number;
  pageSize: number;
  rowCount: number;
  total: number;
}

export function FailedPagination({
  loading,
  onPaginationChange,
  pageIndex,
  pageSize,
  rowCount,
  total,
}: FailedPaginationProps) {
  const [jumpPage, setJumpPage] = useState("");
  const { t } = useI18n();
  const firstItem = total === 0 ? 0 : (pageIndex - 1) * pageSize + 1;
  const lastItem = Math.min((pageIndex - 1) * pageSize + rowCount, total);
  const pageCount = Math.ceil(total / pageSize);
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
    <div className="flex h-16 items-center justify-between gap-2 border-t px-3 sm:px-5">
      <span
        role="status"
        aria-live="polite"
        className="text-sm text-slate-600 tabular-nums"
      >
        {t("{first}–{last} of {total}", { first: firstItem, last: lastItem, total })}
      </span>
      <div className="flex items-center gap-1.5">
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="failed-pagination-control"
          aria-label={t("Previous page")}
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
            aria-label={t("Page {page}", { page })}
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
          aria-label={t("Next page")}
          disabled={pageCount === 0 || pageIndex >= pageCount || loading}
          onClick={() => onPaginationChange?.(pageIndex + 1, pageSize)}
        >
          <ChevronRight />
        </Button>
        <Popover>
          <PopoverTrigger
            render={
              <Button
                type="button"
                variant="outline"
                size="icon"
                className="failed-pagination-control"
                aria-label={t("Pagination options")}
                disabled={loading}
              />
            }
          >
            <SlidersHorizontal />
          </PopoverTrigger>
          <PopoverContent align="end" className="w-64">
            <form className="space-y-4" onSubmit={submitPageJump}>
              <div className="space-y-2">
                <Label htmlFor="page-jump">{t("Go to page")}</Label>
                <div className="flex gap-2">
                  <Input
                    id="page-jump"
                    type="number"
                    min={1}
                    max={Math.max(1, pageCount)}
                    value={jumpPage}
                    onChange={(event) => setJumpPage(event.target.value)}
                  />
                  <Button type="submit" disabled={pageCount === 0 || !jumpPage}>
                    {t("Go")}
                  </Button>
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="rows-per-page">{t("Rows per page")}</Label>
                <select
                  id="rows-per-page"
                  aria-label={t("Rows per page")}
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
  );
}
