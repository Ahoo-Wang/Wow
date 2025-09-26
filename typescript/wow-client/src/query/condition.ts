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

/**
 * Helper function to detect condition is validate or not
 *
 * @param condition - Condition
 * @returns If condition is validate return true, otherwise return false
 */
export function isValidateCondition(
  condition: Condition | undefined | null,
): condition is Condition {
  return !!condition;
}

/**
 * Condition option keys enumeration
 *
 * Defines standard option keys used in query conditions for special handling.
 */
export class ConditionOptionKey {
  /**
   * Ignore case option key for string comparisons
   */
  static readonly IGNORE_CASE_OPTION_KEY = 'ignoreCase';

  /**
   * Time zone ID option key for date operations
   */
  static readonly ZONE_ID_OPTION_KEY = 'zoneId';

  /**
   * Date pattern option key for date formatting
   */
  static readonly DATE_PATTERN_OPTION_KEY = 'datePattern';
}

/**
 * Condition options interface
 *
 * Represents additional options that can be applied to query conditions,
 * such as case sensitivity, date patterns, and time zones.
 */
export interface ConditionOptions {
  /**
   * Whether to ignore case in string comparisons
   */
  ignoreCase?: boolean;

  /**
   * Date pattern for date formatting
   */
  datePattern?: string;

  /**
   * Time zone ID for date operations
   */
  zoneId?: string;

  /**
   * Additional custom options
   */
  [key: string]: any;
}

/**
 * Helper function to create condition options with ignoreCase flag.
 *
 * @param ignoreCase - Whether to ignore case
 * @returns Condition options or undefined if ignoreCase is undefined
 */
export function ignoreCaseOptions(
  ignoreCase?: boolean,
): ConditionOptions | undefined {
  if (typeof ignoreCase === 'undefined') {
    return undefined;
  }
  return { ignoreCase };
}

/**
 * Helper function to create condition options with date pattern and zone ID.
 *
 * @param datePattern - Date pattern
 * @param zoneId - Time zone ID
 * @returns Condition options or undefined if both parameters are undefined
 */
export function dateOptions(
  datePattern?: string,
  zoneId?: string,
): ConditionOptions | undefined {
  if (typeof datePattern === 'undefined' && typeof zoneId === 'undefined') {
    return undefined;
  }
  const options: ConditionOptions = {};
  if (typeof datePattern !== 'undefined') {
    options.datePattern = datePattern;
  }
  if (typeof zoneId !== 'undefined') {
    options.zoneId = zoneId;
  }
  return options;
}

/**
 * Interface for query conditions.
 *
 * When `operator` is `AND` or `OR` or `NOR`, `children` cannot be empty.
 */
export interface Condition<FIELDS extends string = string> {
  /**
   * Field name for the condition
   */
  field?: FIELDS;

  /**
   * Operator for the condition
   */
  operator?: Operator;

  /**
   * Value for the condition
   */
  value?: any;

  /**
   * Child conditions for logical operators (AND, OR, NOR)
   */
  children?: Condition<FIELDS>[];

  /**
   * Additional options for the condition
   */
  options?: ConditionOptions;
}

/**
 * Interface for objects that have a condition.
 */
export interface ConditionCapable<FIELDS extends string = string> {
  condition: Condition<FIELDS>;
}

/**
 * Deletion state enumeration
 *
 * Represents the different states of deletion for entities.
 */
export enum DeletionState {
  /**
   * Active state - entity is not deleted
   */
  ACTIVE = 'ACTIVE',

  /**
   * Deleted state - entity is deleted
   */
  DELETED = 'DELETED',

  /**
   * All state - includes both active and deleted entities
   */
  ALL = 'ALL',
}

/**
 * Creates an AND condition with the specified conditions.
 *
 * This function combines multiple conditions using the logical AND operator.
 * It provides optimizations such as returning the condition directly when
 * only one is provided, and flattening nested AND conditions.
 *
 * @param conditions - Conditions to combine with AND. If empty, returns an ALL condition.
 *                   If exactly one, returns that condition directly.
 *                   If multiple, combines them into an AND condition with flattening optimization.
 * @returns A condition with AND operator or an optimized condition based on the input
 */
export function and<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS> {
  if (conditions.length === 0) {
    return all();
  }
  if (conditions.length === 1) {
    return isValidateCondition(conditions[0]) ? conditions[0] : all();
  }
  const andChildren: Condition<FIELDS>[] = [];
  conditions.forEach(condition => {
    if (
      condition?.operator === Operator.ALL ||
      !isValidateCondition(condition)
    ) {
      return;
    }
    if (condition.operator === Operator.AND && condition.children) {
      andChildren.push(...condition.children);
    } else {
      andChildren.push(condition);
    }
  });
  return { operator: Operator.AND, children: andChildren };
}

/**
 * Creates an OR condition with the specified conditions.
 *
 * @param conditions - Conditions to combine with OR
 * @returns A condition with OR operator
 */
export function or<FIELDS extends string = string>(
  ...conditions: Array<Condition<FIELDS> | undefined | null>
): Condition<FIELDS> {
  const validateConditions = conditions?.filter(condition =>
    isValidateCondition(condition),
  );
  if (validateConditions.length === 0) {
    return all();
  }
  return { operator: Operator.OR, children: validateConditions };
}

/**
 * Creates a NOR condition with the specified conditions.
 *
 * @param conditions - Conditions to combine with NOR
 * @returns A condition with NOR operator
 */
export function nor<FIELDS extends string = string>(
  ...conditions: Condition<FIELDS>[]
): Condition<FIELDS> {
  if (conditions.length === 0) {
    return all();
  }
  return { operator: Operator.NOR, children: conditions };
}

/**
 * Creates an ID condition with the specified value.
 *
 * @param value - The ID value to match
 * @returns A condition with ID operator
 */
export function id<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS> {
  return { operator: Operator.ID, value: value };
}

/**
 * Creates an IDS condition with the specified values.
 *
 * @param value - The ID values to match
 * @returns A condition with IDS operator
 */
export function ids<FIELDS extends string = string>(
  value: string[],
): Condition<FIELDS> {
  return { operator: Operator.IDS, value: value };
}

/**
 * Creates an AGGREGATE_ID condition with the specified value.
 *
 * @param value - The aggregate ID value to match
 * @returns A condition with AGGREGATE_ID operator
 */
export function aggregateId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS> {
  return { operator: Operator.AGGREGATE_ID, value: value };
}

/**
 * Creates an AGGREGATE_IDS condition with the specified values.
 *
 * @param value - The aggregate ID values to match
 * @returns A condition with AGGREGATE_IDS operator
 */
export function aggregateIds<FIELDS extends string = string>(
  ...value: string[]
): Condition<FIELDS> {
  return { operator: Operator.AGGREGATE_IDS, value: value };
}

/**
 * Creates a TENANT_ID condition with the specified value.
 *
 * @param value - The tenant ID value to match
 * @returns A condition with TENANT_ID operator
 */
export function tenantId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS> {
  return { operator: Operator.TENANT_ID, value: value };
}

/**
 * Creates an OWNER_ID condition with the specified value.
 *
 * @param value - The owner ID value to match
 * @returns A condition with OWNER_ID operator
 */
export function ownerId<FIELDS extends string = string>(
  value: string,
): Condition<FIELDS> {
  return { operator: Operator.OWNER_ID, value: value };
}

/**
 * Creates a DELETED condition with the specified value.
 *
 * @param value - The deletion state value to match
 * @returns A condition with DELETED operator
 */
export function deleted<FIELDS extends string = string>(
  value: DeletionState,
): Condition<FIELDS> {
  return { operator: Operator.DELETED, value: value };
}

/**
 * Creates an ACTIVE deletion state condition.
 *
 * @returns A condition with DELETED operator set to ACTIVE
 */
export function active<FIELDS extends string = string>(): Condition<FIELDS> {
  return deleted(DeletionState.ACTIVE);
}

/**
 * Creates an ALL condition.
 *
 * @returns A condition with ALL operator
 */
export function all<FIELDS extends string = string>(): Condition<FIELDS> {
  return {
    operator: Operator.ALL,
  };
}

/**
 * Creates an EQ (equals) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with EQ operator
 */
export function eq<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.EQ, value };
}

/**
 * Creates a NE (not equals) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with NE operator
 */
export function ne<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.NE, value };
}

/**
 * Creates a GT (greater than) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with GT operator
 */
export function gt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.GT, value };
}

/**
 * Creates a LT (less than) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with LT operator
 */
export function lt<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.LT, value };
}

/**
 * Creates a GTE (greater than or equal) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with GTE operator
 */
export function gte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.GTE, value };
}

/**
 * Creates a LTE (less than or equal) condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @returns A condition with LTE operator
 */
export function lte<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
): Condition<FIELDS> {
  return { field, operator: Operator.LTE, value };
}

/**
 * Creates a CONTAINS condition with the specified field and value.
 *
 * @param field - The field name to search in
 * @param value - The value to search for
 * @param ignoreCase - Whether to ignore case in the search
 * @returns A condition with CONTAINS operator
 */
export function contains<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS> {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.CONTAINS, value, options };
}

/**
 * Creates an IN condition with the specified field and values.
 *
 * @param field - The field name to compare
 * @param value - The values to compare against
 * @returns A condition with IN operator
 */
export function isIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS> {
  return { field, operator: Operator.IN, value };
}

/**
 * Creates a NOT_IN condition with the specified field and values.
 *
 * @param field - The field name to compare
 * @param value - The values to compare against
 * @returns A condition with NOT_IN operator
 */
export function notIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS> {
  return { field, operator: Operator.NOT_IN, value };
}

/**
 * Creates a BETWEEN condition with the specified field and range.
 *
 * @param field - The field name to compare
 * @param start - The start value of the range
 * @param end - The end value of the range
 * @returns A condition with BETWEEN operator
 */
export function between<FIELDS extends string = string>(
  field: FIELDS,
  start: any,
  end: any,
): Condition<FIELDS> {
  return { field, operator: Operator.BETWEEN, value: [start, end] };
}

/**
 * Creates an ALL_IN condition with the specified field and values.
 *
 * @param field - The field name to compare
 * @param value - The values to compare against
 * @returns A condition with ALL_IN operator
 */
export function allIn<FIELDS extends string = string>(
  field: FIELDS,
  ...value: any[]
): Condition<FIELDS> {
  return { field, operator: Operator.ALL_IN, value };
}

/**
 * Creates a STARTS_WITH condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @param ignoreCase - Whether to ignore case in the comparison
 * @returns A condition with STARTS_WITH operator
 */
export function startsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS> {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.STARTS_WITH, value, options };
}

/**
 * Creates an ENDS_WITH condition with the specified field and value.
 *
 * @param field - The field name to compare
 * @param value - The value to compare against
 * @param ignoreCase - Whether to ignore case in the comparison
 * @returns A condition with ENDS_WITH operator
 */
export function endsWith<FIELDS extends string = string>(
  field: FIELDS,
  value: any,
  ignoreCase?: boolean,
): Condition<FIELDS> {
  const options: Record<string, any> | undefined =
    ignoreCaseOptions(ignoreCase);
  return { field, operator: Operator.ENDS_WITH, value, options };
}

/**
 * Creates an ELEM_MATCH condition with the specified field and child condition.
 *
 * @param field - The field name to match elements in
 * @param value - The condition to match elements against
 * @returns A condition with ELEM_MATCH operator
 */
export function elemMatch<FIELDS extends string = string>(
  field: FIELDS,
  value: Condition<FIELDS>,
): Condition<FIELDS> {
  return { field, operator: Operator.ELEM_MATCH, children: [value] };
}

/**
 * Creates a NULL condition with the specified field.
 *
 * @param field - The field name to check
 * @returns A condition with NULL operator
 */
export function isNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS> {
  return { field, operator: Operator.NULL };
}

/**
 * Creates a NOT_NULL condition with the specified field.
 *
 * @param field - The field name to check
 * @returns A condition with NOT_NULL operator
 */
export function notNull<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS> {
  return { field, operator: Operator.NOT_NULL };
}

/**
 * Creates a TRUE condition with the specified field.
 *
 * @param field - The field name to check
 * @returns A condition with TRUE operator
 */
export function isTrue<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS> {
  return { field, operator: Operator.TRUE };
}

/**
 * Creates a FALSE condition with the specified field.
 *
 * @param field - The field name to check
 * @returns A condition with FALSE operator
 */
export function isFalse<FIELDS extends string = string>(
  field: FIELDS,
): Condition<FIELDS> {
  return { field, operator: Operator.FALSE };
}

/**
 * Creates an EXISTS condition with the specified field and existence flag.
 *
 * @param field - The field name to check
 * @param exists - Whether the field should exist (default: true)
 * @returns A condition with EXISTS operator
 */
export function exists<FIELDS extends string = string>(
  field: FIELDS,
  exists: boolean = true,
): Condition<FIELDS> {
  return { field, operator: Operator.EXISTS, value: exists };
}

/**
 * Creates a TODAY condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with TODAY operator
 */
export function today<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.TODAY, options };
}

/**
 * Creates a BEFORE_TODAY condition with the specified field and time.
 *
 * @param field - The field name to check
 * @param time - The time to compare against
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with BEFORE_TODAY operator
 */
export function beforeToday<FIELDS extends string = string>(
  field: FIELDS,
  time: any,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.BEFORE_TODAY, value: time, options };
}

/**
 * Creates a TOMORROW condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with TOMORROW operator
 */
export function tomorrow<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.TOMORROW, options };
}

/**
 * Creates a THIS_WEEK condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with THIS_WEEK operator
 */
export function thisWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.THIS_WEEK, options };
}

/**
 * Creates a NEXT_WEEK condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with NEXT_WEEK operator
 */
export function nextWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.NEXT_WEEK, options };
}

/**
 * Creates a LAST_WEEK condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with LAST_WEEK operator
 */
export function lastWeek<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.LAST_WEEK, options };
}

/**
 * Creates a THIS_MONTH condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with THIS_MONTH operator
 */
export function thisMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.THIS_MONTH, options };
}

/**
 * Creates a LAST_MONTH condition with the specified field.
 *
 * @param field - The field name to check
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with LAST_MONTH operator
 */
export function lastMonth<FIELDS extends string = string>(
  field: FIELDS,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.LAST_MONTH, options };
}

/**
 * Creates a RECENT_DAYS condition with the specified field and number of days.
 *
 * @param field - The field name to check
 * @param days - The number of recent days to include
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with RECENT_DAYS operator
 */
export function recentDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.RECENT_DAYS, value: days, options };
}

/**
 * Creates an EARLIER_DAYS condition with the specified field and number of days.
 *
 * @param field - The field name to check
 * @param days - The number of days to look back
 * @param datePattern - The date pattern to use
 * @param zoneId - The time zone ID to use
 * @returns A condition with EARLIER_DAYS operator
 */
export function earlierDays<FIELDS extends string = string>(
  field: FIELDS,
  days: number,
  datePattern?: string,
  zoneId?: string,
): Condition<FIELDS> {
  const options = dateOptions(datePattern, zoneId);
  return { field, operator: Operator.EARLIER_DAYS, value: days, options };
}

/**
 * Creates a RAW condition with the specified raw value.
 *
 * @param raw - The raw condition value
 * @returns A condition with RAW operator
 */
export function raw<FIELDS extends string = string>(
  raw: any,
): Condition<FIELDS> {
  return { operator: Operator.RAW, value: raw };
}
