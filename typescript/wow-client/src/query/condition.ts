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

export class Conditions {
  static ignoreCaseOptions(
    ignoreCase?: boolean,
  ): Record<string, any> | undefined {
    if (typeof ignoreCase === 'undefined') {
      return undefined;
    }
    return { ignoreCase };
  }

  static dateOptions(
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

  static and(...conditions: Condition[]): Condition {
    return { operator: Operator.AND, children: conditions };
  }

  static or(...conditions: Condition[]): Condition {
    return { operator: Operator.OR, children: conditions };
  }

  static nor(...conditions: Condition[]): Condition {
    return { operator: Operator.NOR, children: conditions };
  }

  static id(value: string): Condition {
    return { operator: Operator.ID, value: value };
  }

  static ids(value: string[]): Condition {
    return { operator: Operator.IDS, value: value };
  }

  static aggregateId(value: string): Condition {
    return { operator: Operator.AGGREGATE_ID, value: value };
  }

  static aggregateIds(...value: string[]): Condition {
    return { operator: Operator.AGGREGATE_IDS, value: value };
  }

  static tenantId(value: string): Condition {
    return { operator: Operator.TENANT_ID, value: value };
  }

  static ownerId(value: string): Condition {
    return { operator: Operator.OWNER_ID, value: value };
  }

  static deleted(value: DeletionState): Condition {
    return { operator: Operator.DELETED, value: value };
  }

  static active(): Condition {
    return this.deleted(DeletionState.ACTIVE);
  }

  static all(): Condition {
    return {
      operator: Operator.ALL,
    };
  }

  static eq(field: string, value: any): Condition {
    return { field, operator: Operator.EQ, value };
  }

  static ne(field: string, value: any): Condition {
    return { field, operator: Operator.NE, value };
  }

  static gt(field: string, value: any): Condition {
    return { field, operator: Operator.GT, value };
  }

  static lt(field: string, value: any): Condition {
    return { field, operator: Operator.LT, value };
  }

  static gte(field: string, value: any): Condition {
    return { field, operator: Operator.GTE, value };
  }

  static lte(field: string, value: any): Condition {
    return { field, operator: Operator.LTE, value };
  }

  static contains(field: string, value: any, ignoreCase?: boolean): Condition {
    const options: Record<string, any> | undefined =
      this.ignoreCaseOptions(ignoreCase);
    return { field, operator: Operator.CONTAINS, value, options };
  }

  static isIn(field: string, ...value: any[]): Condition {
    return { field, operator: Operator.IN, value };
  }

  static notIn(field: string, ...value: any[]): Condition {
    return { field, operator: Operator.NOT_IN, value };
  }

  static between(field: string, start: any, end: any): Condition {
    return { field, operator: Operator.BETWEEN, value: [start, end] };
  }

  static allIn(field: string, ...value: any[]): Condition {
    return { field, operator: Operator.ALL_IN, value };
  }

  static startsWith(
    field: string,
    value: any,
    ignoreCase?: boolean,
  ): Condition {
    const options: Record<string, any> | undefined =
      this.ignoreCaseOptions(ignoreCase);
    return { field, operator: Operator.STARTS_WITH, value, options };
  }

  static endsWith(field: string, value: any, ignoreCase?: boolean): Condition {
    const options: Record<string, any> | undefined =
      this.ignoreCaseOptions(ignoreCase);
    return { field, operator: Operator.ENDS_WITH, value, options };
  }

  static elemMatch(field: string, value: Condition): Condition {
    return { field, operator: Operator.ELEM_MATCH, children: [value] };
  }

  static isNull(field: string): Condition {
    return { field, operator: Operator.NULL };
  }

  static notNull(field: string): Condition {
    return { field, operator: Operator.NOT_NULL };
  }

  static isTrue(field: string): Condition {
    return { field, operator: Operator.TRUE };
  }

  static isFalse(field: string): Condition {
    return { field, operator: Operator.FALSE };
  }

  static exists(field: string, exists: boolean = true): Condition {
    return { field, operator: Operator.EXISTS, value: exists };
  }

  static today(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.TODAY, options };
  }

  static beforeToday(
    field: string,
    time: any,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.BEFORE_TODAY, value: time, options };
  }

  static tomorrow(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.TOMORROW, options };
  }

  static thisWeek(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.THIS_WEEK, options };
  }

  static nextWeek(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.NEXT_WEEK, options };
  }

  static lastWeek(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.LAST_WEEK, options };
  }

  static thisMonth(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.THIS_MONTH, options };
  }

  static lastMonth(
    field: string,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.LAST_MONTH, options };
  }

  static recentDays(
    field: string,
    days: number,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.RECENT_DAYS, value: days, options };
  }

  static earlierDays(
    field: string,
    days: number,
    datePattern?: string,
    zoneId?: string,
  ): Condition {
    const options = this.dateOptions(datePattern, zoneId);
    return { field, operator: Operator.EARLIER_DAYS, value: days, options };
  }

  static raw(raw: any): Condition {
    return { operator: Operator.RAW, value: raw };
  }
}

// Independent export functions for direct import
export function ignoreCaseOptions(
  ignoreCase?: boolean,
): Record<string, any> | undefined {
  return Conditions.ignoreCaseOptions(ignoreCase);
}

export function dateOptions(
  datePattern?: string,
  zoneId?: string,
): Record<string, any> | undefined {
  return Conditions.dateOptions(datePattern, zoneId);
}

export function and(...conditions: Condition[]): Condition {
  return Conditions.and(...conditions);
}

export function or(...conditions: Condition[]): Condition {
  return Conditions.or(...conditions);
}

export function nor(...conditions: Condition[]): Condition {
  return Conditions.nor(...conditions);
}

export function id(value: string): Condition {
  return Conditions.id(value);
}

export function ids(value: string[]): Condition {
  return Conditions.ids(value);
}

export function aggregateId(value: string): Condition {
  return Conditions.aggregateId(value);
}

export function aggregateIds(...value: string[]): Condition {
  return Conditions.aggregateIds(...value);
}

export function tenantId(value: string): Condition {
  return Conditions.tenantId(value);
}

export function ownerId(value: string): Condition {
  return Conditions.ownerId(value);
}

export function deleted(value: DeletionState): Condition {
  return Conditions.deleted(value);
}

export function active(): Condition {
  return Conditions.active();
}

export function all(): Condition {
  return Conditions.all();
}

export function eq(field: string, value: any): Condition {
  return Conditions.eq(field, value);
}

export function ne(field: string, value: any): Condition {
  return Conditions.ne(field, value);
}

export function gt(field: string, value: any): Condition {
  return Conditions.gt(field, value);
}

export function lt(field: string, value: any): Condition {
  return Conditions.lt(field, value);
}

export function gte(field: string, value: any): Condition {
  return Conditions.gte(field, value);
}

export function lte(field: string, value: any): Condition {
  return Conditions.lte(field, value);
}

export function contains(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  return Conditions.contains(field, value, ignoreCase);
}

export function isIn(field: string, ...value: any[]): Condition {
  return Conditions.isIn(field, ...value);
}

export function notIn(field: string, ...value: any[]): Condition {
  return Conditions.notIn(field, ...value);
}

export function between(field: string, start: any, end: any): Condition {
  return Conditions.between(field, start, end);
}

export function allIn(field: string, ...value: any[]): Condition {
  return Conditions.allIn(field, ...value);
}

export function startsWith(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  return Conditions.startsWith(field, value, ignoreCase);
}

export function endsWith(
  field: string,
  value: any,
  ignoreCase?: boolean,
): Condition {
  return Conditions.endsWith(field, value, ignoreCase);
}

export function elemMatch(field: string, value: Condition): Condition {
  return Conditions.elemMatch(field, value);
}

export function isNull(field: string): Condition {
  return Conditions.isNull(field);
}

export function notNull(field: string): Condition {
  return Conditions.notNull(field);
}

export function isTrue(field: string): Condition {
  return Conditions.isTrue(field);
}

export function isFalse(field: string): Condition {
  return Conditions.isFalse(field);
}

export function exists(field: string, exists: boolean = true): Condition {
  return Conditions.exists(field, exists);
}

export function today(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.today(field, datePattern, zoneId);
}

export function beforeToday(
  field: string,
  time: any,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.beforeToday(field, time, datePattern, zoneId);
}

export function tomorrow(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.tomorrow(field, datePattern, zoneId);
}

export function thisWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.thisWeek(field, datePattern, zoneId);
}

export function nextWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.nextWeek(field, datePattern, zoneId);
}

export function lastWeek(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.lastWeek(field, datePattern, zoneId);
}

export function thisMonth(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.thisMonth(field, datePattern, zoneId);
}

export function lastMonth(
  field: string,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.lastMonth(field, datePattern, zoneId);
}

export function recentDays(
  field: string,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.recentDays(field, days, datePattern, zoneId);
}

export function earlierDays(
  field: string,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition {
  return Conditions.earlierDays(field, days, datePattern, zoneId);
}

export function raw(raw: any): Condition {
  return Conditions.raw(raw);
}
