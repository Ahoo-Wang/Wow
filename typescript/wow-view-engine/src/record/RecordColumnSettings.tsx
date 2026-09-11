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

import { Columns3Icon, GripVerticalIcon, PinIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import { Checkbox } from '../components/ui/checkbox.js';
import { cn } from '../lib/utils.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select.js';
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';
import {
  type RecordColumn,
  type RecordSummaryFunction,
} from '../contracts/viewModel.js';
import {
  RECORD_SUMMARY_LABELS,
  getRecordSummaryFunctions,
} from './recordPresentation.js';
import { getRecordColumnPinning } from './recordColumns.js';
import type { RecordColumnSettingsProps } from './recordReactTypes.js';
import { useRecordColumnOrder } from './table/useRecordColumnOrder.js';

export function RecordColumnSettings({
  definition,
  columns: configuredColumns,
  onChange,
  disabled,
}: RecordColumnSettingsProps) {
  const {
    columns,
    draggedId,
    dropBoundary,
    announcement,
    instructionsId,
    isLocked,
    titleOf,
    endDrag,
    listProps,
    handleProps,
  } = useRecordColumnOrder({
    definition,
    columns: configuredColumns,
    onChange,
    disabled,
  });
  const visibleCount = columns.filter(
    column => column.visible !== false,
  ).length;
  return (
    <Popover
      onOpenChange={open => {
        if (!open) endDrag();
      }}
    >
      <PopoverTrigger
        disabled={disabled}
        render={<Button variant="outline" size="sm" />}
      >
        <Columns3Icon data-icon="inline-start" />
        列设置
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="fve:w-96 fve:max-w-[calc(100vw-2rem)]"
      >
        <PopoverHeader>
          <PopoverTitle>列设置</PopoverTitle>
          <PopoverDescription>
            拖动手柄调整同一区域内的列顺序。主键固定在左侧，操作固定在右侧。
          </PopoverDescription>
        </PopoverHeader>
        <p id={instructionsId} className="fve:sr-only">
          拖动调整顺序，或聚焦手柄后按上、下方向键移动。
        </p>
        <span role="status" aria-atomic="true" className="fve:sr-only">
          {announcement}
        </span>
        <ol
          className="fve:m-0 fve:flex fve:max-h-80 fve:list-none fve:flex-col fve:gap-3 fve:overflow-y-auto fve:p-1"
          {...listProps}
        >
          {columns.map((column, index) => {
            const field =
              column.kind === 'field'
                ? definition.fields.find(field => field.field === column.field)
                : undefined;
            const summaryFunctions = field
              ? getRecordSummaryFunctions(field)
              : [];
            const title = titleOf(column);
            const pinned = getRecordColumnPinning(
              column,
              definition.record.rowKey,
            );
            const locked = isLocked(column);
            const neighbors = [columns[index - 1], columns[index + 1]]
              .filter((item): item is RecordColumn => item !== undefined)
              .map(item =>
                getRecordColumnPinning(item, definition.record.rowKey),
              )
              .filter(side => side !== false);
            const pinSide = neighbors.length === 1 ? neighbors[0] : undefined;
            const pinDisabled = disabled || locked || (!pinned && !pinSide);
            return (
              <li
                key={column.id}
                className="fve:relative fve:flex fve:flex-col fve:gap-1 fve:data-dragging:opacity-50"
                data-dragging={draggedId === column.id || undefined}
              >
                {(dropBoundary === index ||
                  (dropBoundary === columns.length &&
                    index === columns.length - 1)) && (
                  <span
                    aria-hidden="true"
                    data-slot="column-drop-indicator"
                    className={cn(
                      'fve:pointer-events-none fve:absolute fve:inset-x-0 fve:border-t-2 fve:border-primary',
                      dropBoundary === index
                        ? 'fve:-top-1.5'
                        : 'fve:-bottom-1.5',
                    )}
                  />
                )}
                <div className="fve:flex fve:items-center fve:gap-2">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="fve:cursor-grab fve:text-muted-foreground fve:active:cursor-grabbing"
                    aria-label={`拖动调整${title}顺序`}
                    aria-describedby={instructionsId}
                    {...handleProps(index)}
                  >
                    <GripVerticalIcon />
                  </Button>
                  <label className="fve:flex fve:min-w-0 fve:flex-1 fve:items-center fve:gap-2 fve:py-1.5">
                    <Checkbox
                      checked={column.visible !== false}
                      disabled={
                        disabled ||
                        (column.visible !== false && visibleCount === 1)
                      }
                      onCheckedChange={visible =>
                        onChange(
                          columns.map(item =>
                            item.id === column.id ? { ...item, visible } : item,
                          ),
                        )
                      }
                    />
                    <span className="fve:break-words">
                      <span className="fve:sr-only">显示</span>
                      {title}
                    </span>
                  </label>
                  {column.kind === 'field' && summaryFunctions.length > 0 && (
                    <div className="fve:shrink-0">
                      <Select<RecordSummaryFunction, true>
                        multiple
                        value={[...(column.summary ?? [])]}
                        items={summaryFunctions.map(value => ({
                          value,
                          label: RECORD_SUMMARY_LABELS[value],
                        }))}
                        disabled={disabled}
                        onValueChange={value => {
                          if (disabled) return;
                          onChange(
                            columns.map(item =>
                              item.id === column.id
                                ? {
                                    ...item,
                                    summary: value.length
                                      ? summaryFunctions.filter(fn =>
                                          value.includes(fn),
                                        )
                                      : undefined,
                                  }
                                : item,
                            ),
                          );
                        }}
                      >
                        <SelectTrigger
                          aria-label={`${title}汇总方式`}
                          title={column.summary
                            ?.map(fn => RECORD_SUMMARY_LABELS[fn])
                            .join('、')}
                        >
                          <SelectValue placeholder="不汇总">
                            {!column.summary?.length
                              ? '不汇总'
                              : column.summary.length === 1
                                ? RECORD_SUMMARY_LABELS[column.summary[0]]
                                : `汇总 ${column.summary.length}`}
                          </SelectValue>
                        </SelectTrigger>
                        <SelectContent
                          alignItemWithTrigger={false}
                          align="start"
                        >
                          <SelectGroup>
                            {summaryFunctions.map(fn => (
                              <SelectItem key={fn} value={fn}>
                                {RECORD_SUMMARY_LABELS[fn]}
                              </SelectItem>
                            ))}
                          </SelectGroup>
                        </SelectContent>
                      </Select>
                    </div>
                  )}
                  <Button
                    variant={pinned ? 'secondary' : 'ghost'}
                    size="icon-sm"
                    aria-label={`固定${title}`}
                    aria-pressed={pinned !== false}
                    title={
                      locked
                        ? `${title}始终固定在${pinned === 'left' ? '左' : '右'}侧`
                        : pinned
                          ? `取消固定${title}`
                          : pinSide
                            ? `固定${title}到${pinSide === 'left' ? '左' : '右'}侧`
                            : neighbors.length > 1
                              ? '上下列均已固定，不能固定此列'
                              : '仅可固定紧邻已固定列的列'
                    }
                    disabled={pinDisabled}
                    onClick={() => {
                      if (pinDisabled) return;
                      onChange(
                        columns.map(item =>
                          item.id === column.id
                            ? { ...item, pinned: pinned ? false : pinSide! }
                            : item,
                        ),
                      );
                    }}
                  >
                    <PinIcon
                      data-icon="inline-start"
                      fill={pinned ? 'currentColor' : 'none'}
                    />
                  </Button>
                </div>
              </li>
            );
          })}
        </ol>
      </PopoverContent>
    </Popover>
  );
}
