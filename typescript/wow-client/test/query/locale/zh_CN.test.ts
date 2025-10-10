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
import zh_CN from '../../../src/query/locale/zh_CN';
import { Operator } from '../../../src';
import { OperatorLocale } from '../../../src';

describe('zh_CN locale', () => {
  it('should be a valid OperatorLocale', () => {
    // Type check: zh_CN should satisfy OperatorLocale type
    const locale: OperatorLocale = zh_CN;
    expect(locale).toBeDefined();
  });

  it('should have translations for all operators', () => {
    const operators = Object.values(Operator);
    operators.forEach(operator => {
      expect(zh_CN).toHaveProperty(operator);
      expect(typeof zh_CN[operator]).toBe('string');
      expect(zh_CN[operator].length).toBeGreaterThan(0);
    });
  });

  it('should have correct logical operator translations', () => {
    expect(zh_CN.AND).toBe('与');
    expect(zh_CN.OR).toBe('或');
    expect(zh_CN.NOR).toBe('非或');
  });

  it('should have correct ID related translations', () => {
    expect(zh_CN.ID).toBe('ID 等于');
    expect(zh_CN.IDS).toBe('ID 包含');
    expect(zh_CN.AGGREGATE_ID).toBe('聚合 ID 等于');
    expect(zh_CN.AGGREGATE_IDS).toBe('聚合 ID 包含');
  });

  it('should have correct comparison operator translations', () => {
    expect(zh_CN.EQ).toBe('等于');
    expect(zh_CN.NE).toBe('不等于');
    expect(zh_CN.GT).toBe('大于');
    expect(zh_CN.LT).toBe('小于');
    expect(zh_CN.GTE).toBe('大于等于');
    expect(zh_CN.LTE).toBe('小于等于');
  });

  it('should have correct string matching translations', () => {
    expect(zh_CN.CONTAINS).toBe('包含');
    expect(zh_CN.STARTS_WITH).toBe('以...开头');
    expect(zh_CN.ENDS_WITH).toBe('以...结尾');
  });

  it('should have correct date operator translations', () => {
    expect(zh_CN.TODAY).toBe('今天');
    expect(zh_CN.BEFORE_TODAY).toBe('今天之前');
    expect(zh_CN.TOMORROW).toBe('明天');
    expect(zh_CN.THIS_WEEK).toBe('本周');
    expect(zh_CN.THIS_MONTH).toBe('本月');
  });

  it('should have correct null/boolean translations', () => {
    expect(zh_CN.NULL).toBe('为空');
    expect(zh_CN.NOT_NULL).toBe('不为空');
    expect(zh_CN.TRUE).toBe('为真');
    expect(zh_CN.FALSE).toBe('为假');
    expect(zh_CN.EXISTS).toBe('存在');
  });

  it('should have correct raw operator translation', () => {
    expect(zh_CN.RAW).toBe('原始查询');
  });
});
