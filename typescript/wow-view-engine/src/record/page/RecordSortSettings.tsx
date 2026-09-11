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

import { useRef } from 'react';
import {
  ArrowDownUpIcon,
  ArrowUpIcon,
  ArrowDownIcon,
  GripVerticalIcon,
  XIcon,
} from 'lucide-react';
import { SortDirection, type FieldSort } from '@ahoo-wang/fetcher-wow';
import {
  Tooltip,
  TooltipProvider,
  TooltipTrigger,
  TooltipContent,
} from '../../components/ui/tooltip.js';
import { Button } from '../../components/ui/button.js';
import {
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
  PopoverDescription,
} from '../../components/ui/popover.js';
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
} from '../../components/ui/select.js';
import { useListOrder } from '../../lib/useListOrder.js';
import { cn } from '../../lib/utils.js';
import type { RecordViewDefinition } from '../../contracts/viewModel.js';

/** Uses the same ordered sort configuration as table headers. */
export function RecordSortSettings({
  definition,
  sort,
  onChange,
  disabled,
}: {
  definition: RecordViewDefinition;
  sort: readonly FieldSort[];
  onChange(sort: FieldSort[]): void;
  disabled?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const fields = definition.fields.filter(field => field.sortable);
  const labelOf = (name: string) =>
    fields.find(field => field.field === name)?.label ?? name;
  const order = useListOrder({
    items: sort.map(item => ({ ...item, id: item.field })),
    titleOf: item => labelOf(item.field),
    disabled,
    onChange: items =>
      onChange(items.map(({ field, direction }) => ({ field, direction }))),
  });
  if (!fields.length) return null;
  const available = fields.filter(
    field => !sort.some(item => item.field === field.field),
  );
  const summary = sort
    .map(
      item =>
        `${labelOf(item.field)} ${item.direction === SortDirection.ASC ? '↑' : '↓'}`,
    )
    .join('、');
  const canAdd = available.length > 0 && sort.length < 32;
  function remove(field?: string) {
    if (disabled) return;
    panel.current?.focus();
    onChange(field ? sort.filter(item => item.field !== field) : []);
  }
  return (
    <TooltipProvider>
      <Popover
        onOpenChange={open => {
          if (!open) order.endDrag();
        }}
      >
        <PopoverTrigger
          render={<Button variant="outline" size="sm" />}
          aria-label={summary ? `排序：${summary}` : '排序：默认'}
        >
          <ArrowDownUpIcon aria-hidden="true" />
          <span className="fve:max-w-48 fve:truncate" title={summary || '排序'}>
            {summary || '排序'}
          </span>
        </PopoverTrigger>
        <PopoverContent
          ref={panel}
          tabIndex={-1}
          align="end"
          className="fve:w-96 fve:max-w-[calc(100vw-2rem)] fve:max-h-[80vh] fve:overflow-y-auto"
        >
          <div className="fve:flex fve:items-center fve:justify-between fve:gap-2">
            <div className="fve:flex fve:items-center fve:gap-2">
              <PopoverTitle className="fve:m-0">记录排序</PopoverTitle>
              <span
                aria-label={`${sort.length} 条排序规则`}
                className="fve:rounded-md fve:bg-muted fve:px-1.5 fve:text-xs fve:text-muted-foreground"
              >
                {sort.length}
              </span>
            </div>
            {sort.length > 0 && (
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() => remove()}
              >
                清除全部
              </Button>
            )}
          </div>
          <PopoverDescription className="fve:m-0">
            从上到下优先排序，拖动可调整。
          </PopoverDescription>
          <p id={order.instructionsId} className="fve:sr-only">
            拖动调整顺序，或聚焦手柄后按上、下方向键移动。
          </p>
          <span role="status" aria-atomic="true" className="fve:sr-only">
            {order.announcement}
          </span>
          {!sort.length && (
            <div className="fve:rounded-md fve:border fve:border-dashed fve:px-3 fve:py-5 fve:text-center fve:text-sm fve:text-muted-foreground">
              尚未设置排序
            </div>
          )}
          <ol
            {...order.listProps}
            aria-label="排序规则"
            className="fve:m-0 fve:grid fve:list-none fve:gap-2 fve:p-0"
          >
            {sort.map((item, index) => {
              const field = fields.find(field => field.field === item.field);
              const numeric = field?.type === 'number';
              const date = field?.type === 'date' || field?.type === 'datetime';
              const ascending = item.direction === SortDirection.ASC;
              const direction = ascending
                ? SortDirection.DESC
                : SortDirection.ASC;
              const ascendingLabel = numeric
                ? '从低到高'
                : date
                  ? '从早到晚'
                  : '正序';
              const descendingLabel = numeric
                ? '从高到低'
                : date
                  ? '从晚到早'
                  : '倒序';
              const hint = `${ascending ? ascendingLabel : descendingLabel}；点击切换为${ascending ? descendingLabel : ascendingLabel}`;
              return (
                <li
                  key={item.field}
                  data-dragging={order.draggedId === item.field || undefined}
                  className="fve:relative fve:grid fve:grid-cols-[1.75rem_minmax(0,1fr)_1.75rem_1.75rem] fve:items-center fve:gap-1 fve:rounded-md fve:border fve:p-1 fve:data-dragging:opacity-50"
                >
                  {(order.dropBoundary === index ||
                    (order.dropBoundary === sort.length &&
                      index === sort.length - 1)) && (
                    <span
                      aria-hidden="true"
                      data-slot="sort-drop-indicator"
                      className={cn(
                        'fve:pointer-events-none fve:absolute fve:inset-x-0 fve:border-t-2 fve:border-primary',
                        order.dropBoundary === index
                          ? 'fve:-top-1'
                          : 'fve:-bottom-1',
                      )}
                    />
                  )}
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    className="fve:cursor-grab fve:text-muted-foreground fve:active:cursor-grabbing"
                    aria-label={`拖动调整${labelOf(item.field)}排序优先级`}
                    aria-describedby={order.instructionsId}
                    {...order.handleProps(index)}
                  >
                    <GripVerticalIcon aria-hidden="true" />
                  </Button>
                  <span
                    className="fve:truncate fve:text-sm"
                    title={labelOf(item.field)}
                  >
                    {labelOf(item.field)}
                  </span>
                  <Tooltip>
                    <TooltipTrigger
                      render={
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          disabled={disabled}
                        />
                      }
                      aria-label={`${labelOf(item.field)}排序：${ascending ? '升序' : '降序'}`}
                      title={hint}
                      onClick={() => {
                        if (disabled) return;
                        onChange(
                          sort.map(current =>
                            current.field === item.field
                              ? { ...current, direction }
                              : current,
                          ),
                        );
                      }}
                    >
                      {ascending ? (
                        <ArrowUpIcon aria-hidden="true" />
                      ) : (
                        <ArrowDownIcon aria-hidden="true" />
                      )}
                    </TooltipTrigger>
                    <TooltipContent>{hint}</TooltipContent>
                  </Tooltip>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    disabled={disabled}
                    aria-label={`移除${labelOf(item.field)}排序`}
                    title="移除排序"
                    onClick={() => remove(item.field)}
                  >
                    <XIcon aria-hidden="true" />
                  </Button>
                </li>
              );
            })}
          </ol>
          <Select
            value={null}
            items={available.map(field => ({
              value: field.field,
              label: field.label,
            }))}
            disabled={disabled || !canAdd}
            onValueChange={field => {
              if (
                field &&
                canAdd &&
                available.some(item => item.field === field)
              )
                onChange([...sort, { field, direction: SortDirection.ASC }]);
            }}
          >
            <SelectTrigger aria-label="添加排序" className="fve:w-full">
              <SelectValue
                placeholder={
                  canAdd
                    ? '＋ 添加排序'
                    : sort.length >= 32
                      ? '最多 32 条排序'
                      : '已添加全部字段'
                }
              />
            </SelectTrigger>
            <SelectContent>
              {available.map(field => (
                <SelectItem key={field.field} value={field.field}>
                  {field.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </PopoverContent>
      </Popover>
    </TooltipProvider>
  );
}
