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

import { useState } from 'react';
import { ImageOffIcon, CheckIcon, CircleIcon } from 'lucide-react';
import { Checkbox } from '../components/ui/checkbox.js';
import { Button } from '../components/ui/button.js';
import { Spinner } from '../components/ui/spinner.js';
import { cn } from '../lib/utils.js';
import { RecordCell } from './table/RecordCell.js';
import { RecordRegion } from './RecordRegion.js';
import { RecordRendererBoundary } from './RecordRendererBoundary.js';
import { getRecordKey, readRecordValue } from './recordValidation.js';
import { formatRecordValue } from './recordValueFormat.js';
import type { RecordColumn } from '../contracts/viewModel.js';
import type { RecordCardListProps } from './recordReactTypes.js';

function Cover({ value, alt }: { value: unknown; alt: string }) {
  const [failed, setFailed] = useState(false);
  let src: string | undefined;
  if (typeof value === 'string' && value.trim()) {
    try {
      if (
        ['http:', 'https:'].includes(
          new URL(value.trim(), 'https://view-engine.invalid/').protocol,
        )
      )
        src = value.trim();
    } catch {
      /* Invalid record data uses the placeholder. */
    }
  }
  return src && !failed ? (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      className="fve:aspect-video fve:w-full fve:object-cover"
    />
  ) : (
    <div
      role="img"
      aria-label={`${alt}：暂无封面`}
      className="fve:flex fve:aspect-video fve:items-center fve:justify-center fve:bg-muted fve:text-muted-foreground"
    >
      <ImageOffIcon aria-hidden="true" />
    </div>
  );
}

export function RecordCardList(props: RecordCardListProps) {
  const {
    definition,
    instance,
    rows,
    querying,
    queryError,
    onQueryRetry,
    selectable,
    selectedRowKeys,
    onSelectionChange,
    className,
    renderCard,
  } = props;
  const presentation = instance.config.presentation;
  if (presentation.layout !== 'card')
    throw new Error('RecordCardList 需要 card 布局');
  const { card } = presentation;
  const keys = rows.map(record =>
    getRecordKey(record, definition.record.rowKey),
  );
  const selected = keys.filter(key => selectedRowKeys.includes(key));
  return (
    <div
      className={cn('fve-root fve:min-w-0 fve:border-t fve:p-3', className)}
      aria-busy={querying || undefined}
    >
      {querying && (
        <p
          role="status"
          className="fve:flex fve:items-center fve:justify-center fve:gap-2 fve:p-6"
        >
          <Spinner />
          正在查询
        </p>
      )}
      {!querying && queryError && (
        <div
          role="alert"
          className="fve:mb-3 fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:text-destructive"
        >
          <span>{queryError}</span>
          {rows.length > 0 && <span>显示上次查询结果</span>}
          {onQueryRetry && (
            <Button variant="outline" size="sm" onClick={onQueryRetry}>
              重试查询
            </Button>
          )}
        </div>
      )}
      {!querying && !queryError && !rows.length && (
        <p
          role="status"
          className="fve:p-8 fve:text-center fve:text-muted-foreground"
        >
          暂无数据
        </p>
      )}
      {selectable && rows.length > 0 && (
        <label className="fve:mb-3 fve:flex fve:items-center fve:gap-2 fve:text-sm">
          <Checkbox
            checked={selected.length === keys.length}
            indeterminate={selected.length > 0 && selected.length < keys.length}
            onCheckedChange={checked => onSelectionChange(checked ? keys : [])}
          />
          选择本页全部记录
        </label>
      )}
      <ol
        aria-label="记录卡片"
        className="fve:m-0 fve:grid fve:list-none fve:gap-3 fve:p-0"
        style={{
          gridTemplateColumns:
            'repeat(auto-fill, minmax(min(100%, 18rem), 1fr))',
        }}
      >
        {rows.map((record, index) => {
          const rowKey = keys[index];
          const value = readRecordValue(record, card.title.field);
          const missing =
            value === null ||
            value === undefined ||
            (typeof value === 'string' && !value.trim());
          const titleField = definition.fields.find(
            field => field.field === card.title.field,
          );
          const title = missing
            ? String(rowKey)
            : formatRecordValue(value, titleField, definition.timeZone);
          const cell = (column: RecordColumn) => (
            <RecordRendererBoundary
              label={column.title ?? '卡片字段'}
              resetKey={[record, card, props.extensions, instance, definition]}
            >
              <RecordCell
                {...props}
                record={record}
                rowKey={rowKey}
                index={index}
                column={column}
              />
            </RecordRendererBoundary>
          );
          const cover = card.cover
            ? readRecordValue(record, card.cover.field)
            : undefined;
          const defaultContent = (
            <>
              {card.cover && (
                <Cover
                  key={typeof cover === 'string' ? cover : ''}
                  value={cover}
                  alt={title}
                />
              )}
              <div className="fve:flex fve:flex-1 fve:flex-col fve:gap-3 fve:p-4">
                <div
                  className={cn(
                    'fve:flex fve:items-start fve:gap-2',
                    selectable && !card.cover && 'fve:pr-8',
                  )}
                >
                  <h2 className="fve:m-0 fve:min-w-0 fve:break-words fve:font-semibold">
                    {missing ? title : cell({ ...card.title, kind: 'field' })}
                  </h2>
                </div>
                {card.fields.length > 0 && (
                  <dl className="fve:m-0 fve:grid fve:gap-2">
                    {card.fields.map(field => (
                      <div
                        key={field.id}
                        className="fve:grid fve:min-w-0 fve:gap-1"
                      >
                        <dt className="fve:text-xs fve:text-muted-foreground">
                          {field.title ??
                            definition.fields.find(
                              item => item.field === field.field,
                            )?.label}
                        </dt>
                        <dd className="fve:m-0 fve:min-w-0 fve:break-words fve:text-sm">
                          {cell({ ...field, kind: 'field' })}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
                {card.actions && card.actions.visible !== false && (
                  <div className="fve:mt-auto fve:flex fve:flex-wrap fve:gap-2 fve:border-t fve:pt-3">
                    {cell({
                      id: 'actions',
                      kind: 'actions',
                      title: '记录操作',
                      renderer: card.actions.renderer,
                    })}
                  </div>
                )}
              </div>
            </>
          );
          return (
            <li
              key={JSON.stringify(rowKey)}
              className={cn(
                'fve:relative fve:flex fve:min-w-0 fve:flex-col fve:overflow-hidden fve:rounded-lg fve:border fve:bg-background fve:text-foreground',
                selectedRowKeys.includes(rowKey) &&
                  'fve:border-primary fve:ring-1 fve:ring-primary',
              )}
            >
              {selectable && (
                <Button
                  className="fve:absolute fve:right-2 fve:top-2 fve:z-10"
                  size="icon-sm"
                  variant={
                    selectedRowKeys.includes(rowKey) ? 'default' : 'outline'
                  }
                  aria-label={`选择记录 ${rowKey}`}
                  title={
                    selectedRowKeys.includes(rowKey) ? '取消选择' : '选择记录'
                  }
                  aria-pressed={selectedRowKeys.includes(rowKey)}
                  onClick={() =>
                    onSelectionChange(
                      selectedRowKeys.includes(rowKey)
                        ? selectedRowKeys.filter(key => key !== rowKey)
                        : [...selectedRowKeys, rowKey],
                    )
                  }
                >
                  {selectedRowKeys.includes(rowKey) ? (
                    <CheckIcon aria-hidden="true" />
                  ) : (
                    <CircleIcon aria-hidden="true" />
                  )}
                </Button>
              )}
              <RecordRegion
                label="卡片内容"
                render={renderCard}
                resetKey={[
                  renderCard,
                  record,
                  instance,
                  definition,
                  props.extensions,
                  selectedRowKeys.includes(rowKey),
                ]}
                context={{
                  definition,
                  instance,
                  record,
                  rowKey,
                  index,
                  selected: selectedRowKeys.includes(rowKey),
                  defaultContent,
                  refresh: props.refresh,
                }}
              />
            </li>
          );
        })}
      </ol>
    </div>
  );
}
