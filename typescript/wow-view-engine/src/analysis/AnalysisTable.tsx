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

import { ANALYSIS_LIMITS } from './analysisCapabilities.js';
import { canAddAnalysisSort } from './analysisSort.js';
import { useMemo, useState } from 'react';
import { sameJsonState } from '../lib/snapshot.js';
import { analysisRowKey } from './analysisResult.js';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from '../components/ui/table.js';
import { formatAnalysisValue } from './analysisFormatting.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisPlan,
  AnalysisRow,
  AnalysisViewConfig,
} from './analysisModel.js';
export interface AnalysisTableProps {
  plan: DeepReadonly<AnalysisPlan>;
  rows: DeepReadonly<readonly AnalysisRow[]>;
  sort: DeepReadonly<AnalysisViewConfig['sort']>;
  onSortChange?(sort: AnalysisViewConfig['sort']): void;
  receivedAt?: number;
  stale?: boolean;
  querying?: boolean;
  /** Disable query-producing sort actions while the working input is invalid. */
  sortDisabled?: boolean;
  /** Maximum explicit and implicit dimension sorts combined. Defaults to 32. */
  maxSort?: number;
}
export function AnalysisTable({
  plan,
  rows,
  sort,
  onSortChange,
  receivedAt,
  stale,
  querying,
  sortDisabled = false,
  maxSort = ANALYSIS_LIMITS.maxSort,
}: AnalysisTableProps) {
  const [pagination, setPagination] = useState({ query: plan.query, page: 0 });
  const pageCount = Math.ceil(rows.length / 100);
  const sameQuery = sameJsonState(pagination.query, plan.query);
  const page = sameQuery
    ? Math.min(pagination.page, Math.max(0, pageCount - 1))
    : 0;
  if (!sameQuery || page !== pagination.page)
    setPagination({ query: plan.query, page });
  const dimensions = plan.schema.filter(column => column.role === 'dimension');
  // Query status changes do not invalidate the successfully returned cells.
  const body = useMemo(() => {
    const dimensions = plan.schema.filter(
      column => column.role === 'dimension',
    );
    return (
      <TableBody>
        {rows.length ? (
          rows.slice(page * 100, (page + 1) * 100).map(row => (
            <TableRow key={analysisRowKey(row, dimensions)}>
              {plan.schema.map(column => {
                const value = row[column.alias];
                return (
                  <TableCell key={column.alias}>
                    <span
                      className="fve:block fve:truncate"
                      title={String(value)}
                    >
                      {formatAnalysisValue(value, column, plan.timeZone)}
                    </span>
                  </TableCell>
                );
              })}
            </TableRow>
          ))
        ) : (
          <TableRow>
            <TableCell colSpan={plan.schema.length}>
              没有符合条件的分析结果
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    );
  }, [rows, page, plan]);
  const displayedSort = stale
    ? (plan.query.sort ?? []).map(item => ({
        alias: item.field,
        direction: item.direction,
      }))
    : sort;
  const sortEnabled =
    !!onSortChange &&
    dimensions.length > 0 &&
    !stale &&
    !querying &&
    !sortDisabled;
  function canSort(alias: string) {
    return sortEnabled && canAddAnalysisSort(dimensions, sort, alias, maxSort);
  }
  function toggle(alias: string) {
    if (!canSort(alias)) return;
    const current = sort.find(item => item.alias === alias);
    onSortChange?.(
      current?.direction === SortDirection.DESC
        ? sort.filter(item => item.alias !== alias)
        : current
          ? sort.map(item =>
              item.alias === alias
                ? { ...item, direction: SortDirection.DESC }
                : item,
            )
          : [...sort, { alias, direction: SortDirection.ASC }],
    );
  }
  return (
    <section
      className="fve-root fve:flex fve:min-w-0 fve:flex-col fve:gap-2"
      aria-label="分析结果"
      aria-busy={querying}
    >
      <p role="status">
        {querying ? '正在运行分析。 ' : ''}
        {stale ? '配置已修改，以下为上次运行结果。' : '分析结果'}
        {receivedAt !== undefined
          ? ` · 接收于 ${new Date(receivedAt).toLocaleString('zh-CN')}`
          : ''}
      </p>
      <Table
        className="fve:table-fixed"
        style={{
          minWidth: plan.schema.reduce(
            (width, column) => width + (column.width ?? 160),
            0,
          ),
        }}
      >
        <colgroup>
          {plan.schema.map(column => (
            <col key={column.alias} style={{ width: column.width ?? 160 }} />
          ))}
        </colgroup>
        <TableHeader>
          <TableRow>
            {plan.schema.map(column => {
              const direction = displayedSort.find(
                item => item.alias === column.alias,
              )?.direction;
              return (
                <TableHead
                  key={column.alias}
                  scope="col"
                  style={
                    column.width === undefined
                      ? undefined
                      : { width: column.width, minWidth: column.width }
                  }
                  aria-sort={
                    direction === SortDirection.ASC
                      ? 'ascending'
                      : direction === SortDirection.DESC
                        ? 'descending'
                        : 'none'
                  }
                >
                  {onSortChange && dimensions.length > 0 ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="fve:max-w-full"
                      disabled={!canSort(column.alias)}
                      aria-label={`排序${column.title}`}
                      onClick={() => toggle(column.alias)}
                    >
                      {column.title}
                      {direction === SortDirection.ASC
                        ? ' ↑'
                        : direction === SortDirection.DESC
                          ? ' ↓'
                          : ''}
                    </Button>
                  ) : (
                    column.title
                  )}
                </TableHead>
              );
            })}
          </TableRow>
        </TableHeader>
        {body}
      </Table>
      {rows.length > 100 && (
        <nav
          aria-label="已返回结果分页"
          className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2"
        >
          <p
            aria-live="polite"
            className="fve:text-sm fve:text-muted-foreground"
          >
            已返回结果共 {rows.length} 行，当前 {page * 100 + 1}–
            {Math.min((page + 1) * 100, rows.length)} 行 · 第 {page + 1}/
            {pageCount} 页
          </p>
          <div className="fve:flex fve:gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() =>
                setPagination({ query: plan.query, page: page - 1 })
              }
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= pageCount}
              onClick={() =>
                setPagination({ query: plan.query, page: page + 1 })
              }
            >
              下一页
            </Button>
          </div>
        </nav>
      )}
    </section>
  );
}
