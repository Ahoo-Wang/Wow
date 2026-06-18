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

import { describe, expect, it } from 'vitest';
import {
  beforeToday,
  earlierDays,
  lastMonth,
  lastWeek,
  nextWeek,
  Operator,
  recentDays,
  thisMonth,
  thisWeek,
  today,
  tomorrow,
} from '../../../src';

describe('Condition — Date Conditions', () => {
  it('should create TODAY condition without options', () => {
    expect(today('createdAt')).toEqual({
      field: 'createdAt',
      operator: Operator.TODAY,
      options: undefined,
    });
  });

  it('should create TODAY condition with date pattern', () => {
    expect(today('createdAt', 'yyyy-MM-dd')).toEqual({
      field: 'createdAt',
      operator: Operator.TODAY,
      options: { datePattern: 'yyyy-MM-dd' },
    });
  });

  it('should create TODAY condition with zone ID', () => {
    expect(today('createdAt', undefined, 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.TODAY,
      options: { zoneId: 'UTC' },
    });
  });

  it('should create TODAY condition with both options', () => {
    expect(today('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.TODAY,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create BEFORE_TODAY condition', () => {
    expect(
      beforeToday('createdAt', '2023-01-01', 'yyyy-MM-dd', 'UTC'),
    ).toEqual({
      field: 'createdAt',
      operator: Operator.BEFORE_TODAY,
      value: '2023-01-01',
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create TOMORROW condition', () => {
    expect(tomorrow('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.TOMORROW,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create THIS_WEEK condition', () => {
    expect(thisWeek('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.THIS_WEEK,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create NEXT_WEEK condition', () => {
    expect(nextWeek('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.NEXT_WEEK,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create LAST_WEEK condition', () => {
    expect(lastWeek('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.LAST_WEEK,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create THIS_MONTH condition', () => {
    expect(thisMonth('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.THIS_MONTH,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create LAST_MONTH condition', () => {
    expect(lastMonth('createdAt', 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.LAST_MONTH,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create RECENT_DAYS condition', () => {
    expect(recentDays('createdAt', 7, 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.RECENT_DAYS,
      value: 7,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });

  it('should create EARLIER_DAYS condition', () => {
    expect(earlierDays('createdAt', 30, 'yyyy-MM-dd', 'UTC')).toEqual({
      field: 'createdAt',
      operator: Operator.EARLIER_DAYS,
      value: 30,
      options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
    });
  });
});
