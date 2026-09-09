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
  FieldFilter,
  FilterDatePicker,
  FilterDateTimeRange,
  FilterTimeInput,
  InputGroupButton,
  type FilterDateTimeRangeProps,
} from '@ahoo-wang/fetcher-view-engine/react';
import type { FilterExpression } from '@ahoo-wang/fetcher-wow';
import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { useId, useState, type ReactNode } from 'react';

export interface DemoArgs {
  appearance: 'light' | 'dark';
  disabled: boolean;
}

function Frame({
  appearance,
  children,
}: Pick<DemoArgs, 'appearance'> & { children: ReactNode }) {
  return (
    <section
      className="fve-root"
      data-theme={appearance}
      aria-label="日期与时间演示"
      style={{
        width: 'min(736px,100%)',
        boxSizing: 'border-box',
        padding: 24,
        background: 'var(--fve-background)',
        border: '1px solid var(--fve-border)',
        borderRadius: 'var(--fve-radius)',
        display: 'flex',
        flexDirection: 'column',
        gap: 20,
      }}
    >
      {children}
    </section>
  );
}

const dateLabel = (date: Date | undefined) =>
  date
    ? `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    : '未选择日期';

export function DatePickerDemo(args: DemoArgs) {
  const [date, setDate] = useState<Date | undefined>(new Date(2026, 8, 5));
  return (
    <Frame appearance={args.appearance}>
      <FilterDatePicker
        label="日期"
        value={date}
        onValueChange={setDate}
        disabled={args.disabled}
      />
      <output aria-live="polite">{dateLabel(date)}</output>
    </Frame>
  );
}

export function DateRangeDemo(args: DemoArgs & { datetime?: boolean }) {
  const field = {
    field: 'created',
    label: args.datetime ? '创建时间' : '创建日期',
    type: args.datetime ? ('datetime' as const) : ('date' as const),
  };
  const [value, setValue] = useState<FilterDateTimeRangeProps['value']>(
    args.datetime
      ? {
          lowerBound: Date.parse('2026-09-01T01:30:45Z'),
          upperBound: Date.parse('2026-09-03T02:40:50Z'),
        }
      : { lowerBound: '2026-09-01', upperBound: '2026-09-03' },
  );
  const [error, setError] = useState<string>();
  const errorId = useId();
  return (
    <Frame appearance={args.appearance}>
      <FieldFilter
        field={field}
        operator={FilterOperator.BETWEEN}
        operators={[{ value: FilterOperator.BETWEEN, label: '区间' }]}
        onOperatorChange={() => {}}
        disabled={args.disabled}
      >
        <FilterDateTimeRange
          field={field}
          showTime={args.datetime}
          timeZone="Asia/Shanghai"
          value={value}
          invalid={!!error}
          errorId={errorId}
          onValueChange={setValue}
          onValidityChange={(valid, message) =>
            setError(valid ? undefined : message)
          }
          disabled={args.disabled}
        />
      </FieldFilter>
      {error && (
        <p id={errorId} role="alert">
          {error}
        </p>
      )}
      <output
        aria-label="区间输入值"
        aria-live="polite"
        style={{ overflowWrap: 'anywhere' }}
      >
        {JSON.stringify(value)}
      </output>
    </Frame>
  );
}

export function TimeInputDemo(args: DemoArgs & { initial?: string }) {
  const [value, setValue] = useState(args.initial ?? '09:30:45');
  return (
    <Frame appearance={args.appearance}>
      <FilterTimeInput
        label="时间"
        value={value}
        onValueChange={setValue}
        disabled={args.disabled}
      />
      <output aria-label="时间输入值" aria-live="polite">
        {value || '未填写时间'}
      </output>
    </Frame>
  );
}

// This story's host combines a calendar day and clock time in the browser's
// local timezone. Its example data source stores epoch milliseconds.
// undefined means unset; null means a supplied value is invalid.
function queryValue(
  date: Date | undefined,
  time: string,
): number | undefined | null {
  if (date === undefined && time === '') return undefined;
  const match = /^([01]\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?$/.exec(time);
  if (date === undefined || !Number.isFinite(date.getTime()) || !match)
    return null;
  const result = new Date(date);
  const [hour, minute, second] = [
    Number(match[1]),
    Number(match[2]),
    Number(match[3] ?? 0),
  ];
  result.setHours(hour, minute, second, 0);
  if (
    result.getDate() !== date.getDate() ||
    result.getHours() !== hour ||
    result.getMinutes() !== minute
  )
    return null;
  return result.getTime();
}

export function DateTimeFilterDemo(args: DemoArgs & { unset?: boolean }) {
  const [date, setDate] = useState<Date | undefined>(
    args.unset ? undefined : new Date(2026, 8, 1),
  );
  const [time, setTime] = useState(args.unset ? '' : '09:00:00');
  const [operator, setOperator] = useState<
    FilterOperator.GTE | FilterOperator.LTE
  >(FilterOperator.GTE);
  const initial = new Date(2026, 8, 1, 9).getTime();
  const [applied, setApplied] = useState<{
    expression: FilterExpression;
    text: string;
  }>({
    expression: args.unset
      ? filter.matchAll()
      : filter.gte('createTime', initial),
    text: args.unset ? '' : '2026-09-01 09:00:00',
  });
  const value = queryValue(date, time);
  const expression =
    value === null
      ? undefined
      : value === undefined
        ? filter.matchAll()
        : operator === FilterOperator.GTE
          ? filter.gte('createTime', value)
          : filter.lte('createTime', value);
  const pending =
    !expression ||
    JSON.stringify(expression) !== JSON.stringify(applied.expression);
  return (
    <Frame appearance={args.appearance}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
        <strong>筛选条件</strong>
        <span role="status">{pending ? '待查询' : ''}</span>
      </div>
      <FieldFilter
        field={{ field: 'createTime', label: '创建时间' }}
        operator={operator}
        operators={[
          { value: FilterOperator.GTE, label: '大于等于' },
          { value: FilterOperator.LTE, label: '小于等于' },
        ]}
        onOperatorChange={setOperator}
        disabled={args.disabled}
      >
        <FilterDatePicker
          label="创建日期"
          value={date}
          onValueChange={setDate}
          inline
          disabled={args.disabled}
        />
        <FilterTimeInput
          label="创建时刻"
          value={time}
          onValueChange={setTime}
          inline
          disabled={args.disabled}
        />
      </FieldFilter>
      {value === null && (
        <p role="alert" style={{ margin: 0, color: 'var(--fve-destructive)' }}>
          {date === undefined || time === ''
            ? '请补全日期和时间，或清空两项以取消此筛选。'
            : '已填写的日期或时间格式无效（时间精确到秒）。'}
        </p>
      )}
      {value === undefined && <span>未设置值，查询时不限制创建时间。</span>}
      <div>
        <InputGroupButton
          variant="default"
          size="sm"
          disabled={args.disabled || !expression}
          onClick={() => {
            if (expression)
              setApplied({ expression, text: `${dateLabel(date)} ${time}` });
          }}
        >
          查询
        </InputGroupButton>
      </div>
      <output aria-label="已应用条件" aria-live="polite">
        当前已应用：
        {applied.expression.op === FilterOperator.MATCH_ALL
          ? '不限制创建时间'
          : `${applied.expression.op === FilterOperator.GTE ? '大于等于' : '小于等于'} ${applied.text}`}
      </output>
      <details>
        <summary>查看 Wow FilterExpression</summary>
        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {JSON.stringify(applied.expression, null, 2)}
        </pre>
      </details>
    </Frame>
  );
}
