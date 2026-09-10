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

import { EllipsisIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import {
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
} from '../../components/ui/popover.js';
import {
  Tooltip,
  TooltipProvider,
  TooltipTrigger,
  TooltipContent,
} from '../../components/ui/tooltip.js';
import {
  type RecordColumn,
  type RecordData,
  type RecordKey,
} from '../recordModel.js';
import type { RecordTableProps } from '../recordReactTypes.js';
import { readRecordValue } from '../recordValidation.js';
import { formatRecordValue } from '../recordValueFormat.js';
import { BUILTIN_CELL_RENDERERS } from '../cells/builtinCellRenderers.js';

export function RecordCell({
  column,
  record,
  rowKey,
  index,
  definition,
  instance,
  appliedFilter,
  extensions,
  refresh,
  compact = false,
}: Pick<
  RecordTableProps,
  'definition' | 'instance' | 'appliedFilter' | 'extensions' | 'refresh'
> & {
  column: RecordColumn;
  record: RecordData;
  rowKey: RecordKey;
  index: number;
  compact?: boolean;
}) {
  if (column.kind === 'actions') {
    const reference = column.renderer ?? definition.recordActions?.row;
    const registry = extensions?.rowActions;
    const Renderer =
      reference &&
      registry &&
      Object.prototype.hasOwnProperty.call(registry, reference.name)
        ? registry[reference.name]
        : undefined;
    if (!Renderer)
      return (
        <span role="alert">
          {reference
            ? `未注册行操作渲染器：${reference.name}`
            : '操作列未配置渲染器'}
        </span>
      );
    const actions = (
      <Renderer
        definition={definition}
        instance={instance}
        filter={appliedFilter}
        sort={instance.config.sort}
        options={reference?.options}
        refresh={refresh}
        record={record}
        rowKey={rowKey}
      />
    );
    if (!compact) return actions;
    const label = `记录 ${rowKey} ${column.title ?? '操作'}`;
    return (
      <Popover>
        <PopoverTrigger
          aria-label={label}
          render={<Button type="button" variant="ghost" size="icon-sm" />}
        >
          <EllipsisIcon aria-hidden="true" />
        </PopoverTrigger>
        <PopoverContent align="end">
          <PopoverTitle>{label}</PopoverTitle>
          <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
            {actions}
          </div>
        </PopoverContent>
      </Popover>
    );
  }
  // The intrinsic row key exists even when it is not an editable definition field.
  const field =
    definition.fields.find(field => field.field === column.field) ??
    (column.field === definition.rowKey
      ? { field: definition.rowKey, label: '记录主键' }
      : undefined);
  if (!field) return <span role="alert">未知字段：{column.field}</span>;
  const value = readRecordValue(record, column.field);
  const reference = column.renderer ?? field.cellRenderer;
  if (reference) {
    const registry = extensions?.cells;
    const Renderer =
      registry && Object.prototype.hasOwnProperty.call(registry, reference.name)
        ? registry[reference.name]
        : Object.prototype.hasOwnProperty.call(
              BUILTIN_CELL_RENDERERS,
              reference.name,
            )
          ? BUILTIN_CELL_RENDERERS[reference.name]
          : undefined;
    if (!Renderer)
      return <span role="alert">未注册单元格渲染器：{reference.name}</span>;
    return (
      <Renderer
        value={value}
        record={record}
        rowKey={rowKey}
        index={index}
        field={field}
        column={column}
        definition={definition}
        instance={instance}
        options={reference.options}
      />
    );
  }
  const text = formatRecordValue(value, field, definition.timeZone);
  if (compact && column.field === definition.rowKey) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger
            render={<span tabIndex={0} />}
            aria-label={text}
            className="fve:flex fve:min-w-0 fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring"
          >
            {text.length > 6 ? (
              <>
                <span className="fve:min-w-0 fve:truncate">
                  {text.slice(0, -4)}
                </span>
                <span className="fve:shrink-0">{text.slice(-4)}</span>
              </>
            ) : (
              text
            )}
          </TooltipTrigger>
          <TooltipContent>{text}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    );
  }
  return (
    <span className="fve:break-words" title={text}>
      {text}
    </span>
  );
}
