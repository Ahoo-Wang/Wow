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
  Tooltip,
  TooltipProvider,
  TooltipTrigger,
  TooltipContent,
} from '../../components/ui/tooltip.js';
import {
  TableCell,
  TableHead,
  TableFooter,
  TableRow,
} from '../../components/ui/table.js';
import { cn } from '../../lib/utils.js';
import { type RecordSummaryFunction } from '../recordModel.js';
import { RECORD_SUMMARY_LABELS } from '../recordPresentation.js';
import { formatRecordNumber } from '../recordValueFormat.js';
import type { RecordTableProps } from '../recordReactTypes.js';
import type { RecordTableModel } from './recordTableTypes.js';
import { RecordSummaryScope } from './RecordSummaryScope.js';

const summaryOrder = Object.keys(
  RECORD_SUMMARY_LABELS,
) as RecordSummaryFunction[];

export function RecordTableSummary({
  definition,
  pageSummary,
  allSummary,
  onSummaryRetry,
  model,
}: Pick<
  RecordTableProps,
  'definition' | 'pageSummary' | 'allSummary' | 'onSummaryRetry'
> & {
  model: RecordTableModel;
}) {
  const {
    byId,
    visibleColumns,
    columnStyle,
    columnClassName,
    withFiller,
    layout: { summaryLabelColumnCount, summaryLabelSpan, summaryLabelWidth },
  } = model;
  const hasSummary = visibleColumns.some(column => {
    const configured = byId.get(column.id);
    return configured?.kind === 'field' && !!configured.summary?.length;
  });
  if (!hasSummary) return null;
  const summaries = [
    { label: '本页', result: pageSummary },
    { label: '所有', result: allSummary },
  ];
  return (
    <TooltipProvider>
      <TableFooter aria-label="汇总">
        {summaries.map(({ label, result }) => (
          <TableRow
            key={label}
            aria-label={`${label}汇总`}
            aria-busy={result?.status === 'loading' || undefined}
          >
            {summaryLabelSpan > 0 && (
              <TableHead
                scope="row"
                aria-label={label}
                colSpan={summaryLabelSpan}
                data-pinned="start"
                style={{ left: 0, width: summaryLabelWidth }}
                className="fve:h-auto fve:px-1 fve:py-2 fve:align-middle fve:text-xs fve:leading-5 fve:font-normal fve:text-muted-foreground"
              >
                <RecordSummaryScope
                  label={label}
                  result={result}
                  onRetry={label === '所有' ? onSummaryRetry : undefined}
                />
              </TableHead>
            )}
            {withFiller(visibleColumns).map((column, index) => {
              if (index < summaryLabelColumnCount) return null;
              if (!column) return <TableCell key="space" aria-hidden="true" />;
              const configured = byId.get(column.id)!;
              const field =
                configured.kind === 'field'
                  ? definition.fields.find(
                      field => field.field === configured.field,
                    )
                  : undefined;
              const scopeCell =
                !summaryLabelSpan && column.id === visibleColumns[0]?.id;
              const Cell = scopeCell ? TableHead : TableCell;
              return (
                <Cell
                  key={column.id}
                  scope={scopeCell ? 'row' : undefined}
                  aria-label={scopeCell ? label : undefined}
                  className={cn(
                    'fve:h-auto fve:py-2 fve:font-normal',
                    scopeCell ? 'fve:align-middle' : 'fve:align-top',
                    columnClassName(column),
                  )}
                  style={columnStyle(column)}
                  data-pinned={column.getIsPinned() || undefined}
                >
                  {scopeCell && (
                    <RecordSummaryScope
                      label={label}
                      result={result}
                      onRetry={label === '所有' ? onSummaryRetry : undefined}
                    />
                  )}
                  {configured.kind === 'field' &&
                    field &&
                    summaryOrder
                      .filter(summary => configured.summary?.includes(summary))
                      .map(summary => {
                        const value = result?.values[configured.id]?.[summary];
                        const success =
                          result?.status === 'success' &&
                          typeof value === 'number';
                        const metricLabel = `${configured.title ?? field.label}${RECORD_SUMMARY_LABELS[summary]}`;
                        const formatted = success
                          ? formatRecordNumber(value, field)
                          : undefined;
                        return (
                          <div
                            key={summary}
                            role="group"
                            aria-label={metricLabel}
                            className="fve:grid fve:grid-cols-[auto_minmax(0,1fr)] fve:items-baseline fve:gap-2 fve:leading-5"
                          >
                            <span className="fve:whitespace-nowrap fve:text-xs fve:font-normal fve:text-muted-foreground">
                              {RECORD_SUMMARY_LABELS[summary]}
                            </span>
                            {success ? (
                              <Tooltip>
                                <TooltipTrigger
                                  render={<span tabIndex={0} />}
                                  aria-label={`${label}${metricLabel}：${formatted}`}
                                  className="fve:min-w-0 fve:truncate fve:text-right fve:font-medium fve:tabular-nums fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring"
                                >
                                  {formatted}
                                </TooltipTrigger>
                                <TooltipContent>
                                  原值：{String(value)}
                                </TooltipContent>
                              </Tooltip>
                            ) : (
                              <span className="fve:min-w-0 fve:truncate fve:text-right fve:font-medium fve:tabular-nums">
                                {result?.status === 'loading' ? null : '—'}
                              </span>
                            )}
                          </div>
                        );
                      })}
                </Cell>
              );
            })}
          </TableRow>
        ))}
      </TableFooter>
    </TooltipProvider>
  );
}
