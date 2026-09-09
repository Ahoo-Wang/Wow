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

import { useLayoutEffect, useRef, useState } from 'react';
import { Table } from '../components/ui/table.js';
import { cn } from '../lib/utils.js';
import type { RecordTableProps } from './recordReactTypes.js';
import { RecordTableHeader } from './table/RecordTableHeader.js';
import { RecordTableBody } from './table/RecordTableBody.js';
import { RecordTableSummary } from './table/RecordTableSummary.js';
import { useRecordTable } from './table/useRecordTable.js';

export function RecordTable(props: RecordTableProps) {
  const {
    definition,
    instance,
    querying,
    selectable = false,
    className,
  } = props;
  const containerRef = useRef<HTMLDivElement>(null);
  const [availableWidth, setAvailableWidth] = useState(0);
  useLayoutEffect(() => {
    const element = containerRef.current;
    if (!element || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(([entry]) =>
      setAvailableWidth(entry.contentRect.width),
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const model = useRecordTable({ ...props, availableWidth });
  const {
    visibleColumns,
    withFiller,
    layout: { insufficientWidth, relaxedPinning, contentWidth, fillerWidth },
  } = model;
  const onQueryRetry = props.onQueryRetry
    ? () => {
        containerRef.current?.focus();
        props.onQueryRetry?.();
      }
    : undefined;
  return (
    <div
      ref={containerRef}
      tabIndex={-1}
      aria-label="记录结果"
      className={cn(
        'fve-root fve:isolate fve:min-w-0 fve:max-w-full fve:overflow-hidden fve:rounded-lg fve:border fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-inset fve:focus-visible:ring-ring',
        className,
      )}
      aria-busy={querying || undefined}
    >
      {insufficientWidth ? (
        <p
          role="status"
          className="fve:m-0 fve:px-3 fve:py-2 fve:text-xs fve:text-muted-foreground"
        >
          空间不足，请展开视图或减少显示列。
        </p>
      ) : relaxedPinning ? (
        <p
          role="status"
          className="fve:m-0 fve:px-3 fve:py-2 fve:text-xs fve:text-muted-foreground"
        >
          空间有限，仅固定主键和操作列；其他固定设置在宽度恢复后生效。
        </p>
      ) : null}
      <Table
        className="fve-record-table fve:table-fixed fve:border-separate fve:border-spacing-0"
        style={{ width: contentWidth + (fillerWidth >= 1 ? fillerWidth : 0) }}
        aria-label={instance.title}
      >
        <colgroup>
          {selectable && <col style={{ width: 48 }} />}
          {withFiller(visibleColumns).map(column =>
            column ? (
              <col key={column.id} style={{ width: column.getSize() }} />
            ) : (
              <col key="space" style={{ width: fillerWidth }} />
            ),
          )}
        </colgroup>
        <RecordTableHeader
          definition={definition}
          selectable={selectable}
          model={model}
        />
        <RecordTableBody
          {...props}
          selectable={selectable}
          model={model}
          availableWidth={availableWidth}
          onQueryRetry={onQueryRetry}
        />
        <RecordTableSummary
          definition={definition}
          pageSummary={props.pageSummary}
          allSummary={props.allSummary}
          onSummaryRetry={props.onSummaryRetry}
          model={model}
        />
      </Table>
    </div>
  );
}
