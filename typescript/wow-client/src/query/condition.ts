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

import { Operator } from './operator';

export interface Condition {
  field?: string;
  operator?: Operator;
  value?: any;
  /**
   * When `operator` is `AND` or `OR` or `NOR`, `children` cannot be empty.
   */
  children?: Condition[];
  options?: Record<string, any>;
}

export interface ConditionCapable {
  condition: Condition;
}

export enum DeletionState {
  ACTIVE = 'ACTIVE',
  DELETED = 'DELETED',
  ALL = 'ALL',
}

export enum ConditionOptionKey {
  IGNORE_CASE_OPTION_KEY = 'ignoreCase',
  ZONE_ID_OPTION_KEY = 'zoneId',
  DATE_PATTERN_OPTION_KEY = 'datePattern',
}

// Helper functions
export function ignoreCaseOptions(
  ignoreCase?: boolean,
): Record<string, any> | undefined {
  if (typeof ignoreCase === 'undefined') {
    return undefined;
  }
  return { ignoreCase };
}

export function dateOptions(
  datePattern?: string,
  zoneId?: string,
): Record<string, any> | undefined {
  if (typeof datePattern === 'undefined' && typeof zoneId === 'undefined') {
    return undefined;
  }
  const options: Record<string, any> = {};
  if (typeof datePattern !== 'undefined') {
    options.datePattern = datePattern;
  }
  if (typeof zoneId !== 'undefined') {
    options.zoneId = zoneId;
  }
  return options;
}

// Export functions
export function and(...conditions: Condition[]): Condition {
  return { operator: Operator.AND, children: conditions };
}

export function or(...conditions: Condition[]): Condition {
  return { operator: Operator.OR, children: conditions };
}

export function nor(...conditions: Condition[]): Condition {
  return { operator: Operator.NOR, children: conditions };
}

export function id(value: string): Condition {
  return { operator: Operator.ID, value: value };
}

export function ids(value: string[]): Condition {
  return { operator: Operator.IDS, value: value };
}

export function aggregateId(value: string): Condition {
  return { operator: Operator.AGGREGATE_ID, value: value };
}

export function aggregateIds(...value: string[]): Condition {
  return { operator: Operator.AGGREGATE_IDS, value: value };
}

export function tenantId(value: string): Condition {
  return { operator: Operator.TENANT_ID, value: value };
}

export function ownerId(value: string): Condition {
  return { operator: Operator.OWNER_ID, value: value };
}

export function deleted(value: DeletionState): Condition {
  return { operator: Operator.DELETED, value: value };
}

export function active(): Condition {
  return deleted(DeletionState.ACTIVE);
}

export function all(): Condition {
  return {
    operator: Operator.ALL,
  };
}

export function eq(field: string, value: any): Condition {
  return { field, operator: Operator.EQ, value };
}

export function ne(field: string, value: any): Condition {
  return { field, operator: Operator.NE, value };
}

export function gt(field: string, value: any): Condition {
  return { field, operator: Operator.GT, value };
}

export function lt(field: string, value: any): Condition {
  return { field, operator: Operator.LT, value };
}

export function gte(field: string, value: any): Condition {
  return { field, operator: Operator.GTE, value };
}

export function lte(field: string, value: any): Condition {
  return { field, operator: Operator.LTE, value };
}

export function contains(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.CONTAINS, value, options };
}

export function isIn(field: string, ...value: any[]): Condition {
  return { field, operator: Operator.IN, value };
}

export function notIn(field: string, ...value: any[]): Condition {
  return { field, operator: Operator.NOT_IN, value };
}

export function between(field: string, start: any, end: any): Condition {
  return { field, operator: Operator.BETWEEN, value: [start, end] };
}

export function allIn(field: string, ...value: any[]): Condition {
  return { field, operator: Operator.ALL_IN, value };
}

export function startsWith(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.STARTS_WITH, value, options };
}

export function endsWith(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.ENDS_WITH, value, options };
}

export function elemMatch(field: string, value: Condition): Condition {
  return { field, operator: Operator.ELEM_MATCH, children: [value] };
}

export function isNull(field: string): Condition {
  return { field, operator: Operator.NULL };
}

export function notNull(field: string): Condition {
  return { field, operator: Operator.NOT_NULL };
}

export function isTrue(field: string): Condition {
  return { field, operator: Operator.TRUE };
}

export function isFalse(field: string): Condition {
  return { field, operator: Operator.FALSE };
}

export function exists(field: string, exists: boolean = true): Condition {
  return { field, operator: Operator.EXISTS, value: exists };
}

export function today(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.TODAY, options };
}

export function beforeToday(
  field: string,
  time: any,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.BEFORE_TODAY, value: time, options };
}

export function tomorrow(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.TOMORROW, options };
}

export function thisWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.THIS_WEEK, options };
}

export function nextWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.NEXT_WEEK, options };
}

export function lastWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.LAST_WEEK, options };
}

export function thisMonth(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.THIS_MONTH, options };
}

export function lastMonth(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.LAST_MONTH, options };
}

export function recentDays(
  field: string,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.RECENT_DAYS, value: days, options };
}

export function earlierDays(
  field: string,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.EARLIER_DAYS, value: days, options };
}

export function raw(raw: any): Condition {
  return { operator: Operator.RAW, value: raw };
}
