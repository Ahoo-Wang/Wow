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
import type { FilterLiteral } from '@ahoo-wang/fetcher-wow';
import { TZDate } from '@date-fns/tz';
import { fixedTimeZoneOffset } from '../lib/timeZone.js';
import type {
  FilterDateTimeValue,
  FilterFieldDefinition,
} from './filterModel.js';

export function numeric(value: unknown): number {
  if (
    typeof value === 'string' &&
    /^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
  )
    value = Number(value);
  if (typeof value !== 'number' || !Number.isFinite(value))
    throw new TypeError('请输入完整的有限数值');
  return value;
}

function dateParts(value: unknown): number[] {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    throw new TypeError('日期格式应为 YYYY-MM-DD');
  const parts = value.split('-').map(Number);
  const date = new Date(0);
  date.setUTCFullYear(parts[0], parts[1] - 1, parts[2]);
  if (
    date.getUTCFullYear() !== parts[0] ||
    date.getUTCMonth() !== parts[1] - 1 ||
    date.getUTCDate() !== parts[2]
  )
    throw new TypeError('日期不存在');
  return parts;
}

function datetime(value: unknown, timeZone?: string): number | undefined {
  if (typeof value === 'number') {
    if (
      !Number.isFinite(value) ||
      !Number.isFinite(new TZDate(value, timeZone).getTime())
    )
      throw new TypeError('日期时间戳无效');
    return value;
  }
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some(
      key => key !== 'date' && key !== 'time' && key !== 'offsetMinutes',
    )
  )
    throw new TypeError('请填写日期和时间');
  const { date, time, offsetMinutes } = value as FilterDateTimeValue;
  if (offsetMinutes !== undefined && !Number.isInteger(offsetMinutes))
    throw new TypeError('日期时间偏移必须是整数分钟');
  if (
    (date === undefined || date === '') &&
    (time === undefined || time === '')
  )
    return undefined;
  if (date === undefined || date === '' || time === undefined || time === '')
    throw new TypeError('请补全日期和时间');
  const [year, month, day] = dateParts(date);
  if (
    typeof time !== 'string' ||
    !/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,3})?)?$/.test(time)
  )
    throw new TypeError('时间格式应为 HH:mm 或 HH:mm:ss');
  const [hour, minute, second = '0'] = time.split(':');
  const [seconds, fraction = ''] = second.split('.');
  const parts = [
    year,
    month - 1,
    day,
    Number(hour),
    Number(minute),
    Number(seconds),
    Number(fraction.padEnd(3, '0')),
  ];
  const offset = fixedTimeZoneOffset(timeZone);
  if (offset !== undefined) {
    const date = new Date(0);
    date.setUTCFullYear(parts[0], parts[1], parts[2]);
    date.setUTCHours(parts[3], parts[4], parts[5], parts[6]);
    return date.getTime() - offset * 60_000;
  }
  const zoned = new TZDate(0, timeZone);
  zoned.setFullYear(parts[0], parts[1], parts[2]);
  zoned.setHours(parts[3], parts[4], parts[5], parts[6]);
  const matchesParts = (candidate: Date) =>
    [
      candidate.getFullYear(),
      candidate.getMonth(),
      candidate.getDate(),
      candidate.getHours(),
      candidate.getMinutes(),
      candidate.getSeconds(),
      candidate.getMilliseconds(),
    ].every((part, index) => part === parts[index]);
  if (!Number.isFinite(zoned.getTime()) || !matchesParts(zoned))
    throw new TypeError('日期时间在指定时区不存在');
  if (offsetMinutes !== undefined) {
    const preferred = new TZDate(
      zoned.getTime() + (offsetMinutes - zoned.getTimezoneOffset()) * 60_000,
      timeZone,
    );
    if (matchesParts(preferred)) return preferred.getTime();
  }
  // Resolve a repeated wall time to its earlier occurrence, including half-hour rollbacks.
  const previousOffset = new TZDate(
    zoned.getTime() - 86_400_000,
    timeZone,
  ).getTimezoneOffset();
  const earlier = new TZDate(
    zoned.getTime() + (previousOffset - zoned.getTimezoneOffset()) * 60_000,
    timeZone,
  );
  if (earlier.getTime() < zoned.getTime() && matchesParts(earlier))
    return earlier.getTime();
  return zoned.getTime();
}

export function scalar(
  value: unknown,
  field?: FilterFieldDefinition,
  timeZone?: string,
): FilterLiteral | undefined {
  if (value === undefined || value === null) return value;
  if (typeof value === 'object' && !Array.isArray(value) && 'type' in value) {
    const typed = value as { type: string; value: unknown };
    if (Object.keys(value).some(key => key !== 'type' && key !== 'value'))
      throw new TypeError('标量编辑值无效');
    if (
      ['string', 'number', 'boolean'].includes(typed.type) &&
      typed.value === undefined
    )
      return undefined;
    if (typed.type === 'number') value = numeric(typed.value);
    else if (
      (typed.type === 'string' && typeof typed.value === 'string') ||
      (typed.type === 'boolean' && typeof typed.value === 'boolean')
    )
      value = typed.value;
    else throw new TypeError('标量类型与值不匹配');
  }
  if (field?.options) {
    if (!field.options.some(option => option.value === value))
      throw new TypeError('请选择定义中的枚举值');
  } else {
    switch (field?.type) {
      case 'number':
        if (typeof value !== 'number')
          throw new TypeError('请输入数值类型的值');
        break;
      case 'boolean':
        if (typeof value !== 'boolean') throw new TypeError('请选择布尔值');
        break;
      case 'string':
        if (typeof value !== 'string') throw new TypeError('请输入文本值');
        break;
      case 'date':
        dateParts(value);
        break;
      case 'datetime':
        return datetime(value, timeZone);
    }
  }
  if (
    typeof value !== 'string' &&
    typeof value !== 'boolean' &&
    !(typeof value === 'number' && Number.isFinite(value))
  )
    throw new TypeError('过滤值必须是有效标量');
  return value;
}
