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

import { useRef, useState } from 'react';
import { GripVerticalIcon, XIcon, Settings2Icon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import { Checkbox } from '../components/ui/checkbox.js';
import {
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
  PopoverDescription,
} from '../components/ui/popover.js';
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectGroup,
  SelectItem,
} from '../components/ui/select.js';
import { useListOrder } from '../lib/useListOrder.js';
import { cn } from '../lib/utils.js';
import { cloneSnapshot } from '../lib/types.js';
import type { RecordCardConfig } from './recordModel.js';
import type { RecordCardSettingsProps } from './recordReactTypes.js';

function FieldChoice({
  label,
  value,
  options,
  onChange,
  disabled,
}: {
  label: string;
  value: string | null;
  options: { value: string; label: string }[];
  onChange(value: string | null): void;
  disabled?: boolean;
}) {
  return (
    <div className="fve:flex fve:flex-col fve:gap-1">
      <span className="fve:text-sm fve:font-medium">{label}</span>
      <Select
        value={value}
        items={options}
        onValueChange={value => {
          if (!disabled) onChange(value);
        }}
        disabled={disabled || options.length === 0}
      >
        <SelectTrigger aria-label={label}>
          <SelectValue
            placeholder={options.length ? '未选择' : '已添加全部字段'}
          >
            {options.find(item => item.value === value)?.label}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {options.map(item => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
    </div>
  );
}

export function RecordCardSettings({
  definition,
  card,
  onChange,
  disabled,
}: RecordCardSettingsProps) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(() =>
    cloneSnapshot<RecordCardConfig>(card),
  );
  const [error, setError] = useState<string | null>(null);
  const addRef = useRef<HTMLDivElement>(null);
  const fields = definition.fields.map(field => ({
    value: field.field,
    label: field.label,
  }));
  const titles = fields.some(field => field.value === definition.rowKey)
    ? fields
    : [{ value: definition.rowKey, label: '记录主键' }, ...fields];
  const order = useListOrder({
    items: draft.fields,
    onChange: fields => setDraft({ ...draft, fields }),
    titleOf: field =>
      field.title ??
      definition.fields.find(item => item.field === field.field)?.label ??
      field.field,
    disabled,
  });
  function apply() {
    if (disabled) return;
    setError(null);
    try {
      onChange(draft);
    } catch (error) {
      setError(error instanceof Error ? error.message : '应用设置失败');
      return;
    }
    setOpen(false);
  }
  return (
    <Popover
      open={open}
      onOpenChange={open => {
        setOpen(open);
        if (!open) order.endDrag();
        if (open) {
          setDraft(cloneSnapshot<RecordCardConfig>(card));
          setError(null);
        }
      }}
    >
      <PopoverTrigger
        disabled={disabled}
        render={<Button variant="outline" size="sm" />}
      >
        <Settings2Icon data-icon="inline-start" />
        卡片设置
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="fve:w-96 fve:max-w-[calc(100vw-2rem)] fve:max-h-[80vh] fve:overflow-y-auto"
      >
        <PopoverTitle>卡片设置</PopoverTitle>
        <PopoverDescription>
          设置标题、封面与摘要字段，拖动手柄调整摘要顺序，应用到当前视图。
        </PopoverDescription>
        <FieldChoice
          disabled={disabled}
          label="标题字段"
          value={draft.title.field}
          options={titles}
          onChange={field => {
            if (field) setDraft({ ...draft, title: { ...draft.title, field } });
          }}
        />
        <FieldChoice
          disabled={disabled}
          label="封面字段"
          value={draft.cover?.field ?? ''}
          options={[
            { value: '', label: '无封面' },
            ...definition.fields
              .filter(field => field.type === 'string')
              .map(field => ({ value: field.field, label: field.label })),
          ]}
          onChange={field => {
            const next = { ...draft };
            if (field) next.cover = { field };
            else delete next.cover;
            setDraft(next);
          }}
        />
        <p id={order.instructionsId} className="fve:sr-only">
          拖动调整顺序，或聚焦手柄后按上、下方向键移动。
        </p>
        <span role="status" aria-atomic="true" className="fve:sr-only">
          {order.announcement}
        </span>
        <ol
          {...order.listProps}
          className="fve:m-0 fve:grid fve:list-none fve:gap-2 fve:p-0"
          aria-label="摘要字段"
        >
          {draft.fields.map((field, index) => (
            <li
              key={field.id}
              className="fve:relative fve:flex fve:items-center fve:gap-1 fve:data-dragging:opacity-50"
              data-dragging={order.draggedId === field.id || undefined}
            >
              {(order.dropBoundary === index ||
                (order.dropBoundary === draft.fields.length &&
                  index === draft.fields.length - 1)) && (
                <span
                  aria-hidden="true"
                  data-slot="card-drop-indicator"
                  className={cn(
                    'fve:pointer-events-none fve:absolute fve:inset-x-0 fve:border-t-2 fve:border-primary',
                    order.dropBoundary === index
                      ? 'fve:-top-1'
                      : 'fve:-bottom-1',
                  )}
                />
              )}
              <Button
                size="icon-sm"
                variant="ghost"
                className="fve:cursor-grab fve:text-muted-foreground fve:active:cursor-grabbing"
                aria-label={`拖动调整摘要 ${index + 1} 顺序`}
                aria-describedby={order.instructionsId}
                {...order.handleProps(index)}
              >
                <GripVerticalIcon />
              </Button>
              <span className="fve:min-w-0 fve:flex-1 fve:break-words">
                {field.title ??
                  definition.fields.find(item => item.field === field.field)
                    ?.label}
              </span>
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={`移除摘要 ${index + 1}`}
                disabled={disabled}
                onClick={() => {
                  if (disabled) return;
                  setDraft({
                    ...draft,
                    fields: draft.fields.filter(item => item.id !== field.id),
                  });
                  addRef.current
                    ?.querySelector<HTMLButtonElement>('button')
                    ?.focus();
                }}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ol>
        <div ref={addRef}>
          <FieldChoice
            disabled={disabled}
            label="添加摘要字段"
            value={null}
            options={fields.filter(
              field => !draft.fields.some(item => item.field === field.value),
            )}
            onChange={field => {
              if (field)
                setDraft({
                  ...draft,
                  fields: [...draft.fields, { id: crypto.randomUUID(), field }],
                });
            }}
          />
        </div>
        {(draft.actions || definition.recordActions?.row) && (
          <label className="fve:flex fve:items-center fve:gap-2">
            <Checkbox
              disabled={disabled}
              checked={
                draft.actions !== undefined && draft.actions.visible !== false
              }
              onCheckedChange={visible => {
                if (disabled) return;
                setDraft({ ...draft, actions: { ...draft.actions, visible } });
              }}
            />
            显示操作区
          </label>
        )}
        {error && (
          <p role="alert" className="fve:text-sm fve:text-destructive">
            {error}
          </p>
        )}
        <div className="fve:flex fve:justify-end fve:gap-2">
          <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
            取消
          </Button>
          <Button size="sm" disabled={disabled} onClick={apply}>
            应用设置
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}
