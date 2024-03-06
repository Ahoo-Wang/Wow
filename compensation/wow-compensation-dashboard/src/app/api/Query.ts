export enum Operator {
  AND = "AND",
  OR = "OR",
  ID = "ID",
  IDS = "IDS",
  DELETED = "DELETED",
  TENANT_ID = "TENANT_ID",
  ALL = "ALL",
  EQ = "EQ",
  NE = "NE",
  GT = "GT",
  LT = "LT",
  GTE = "GTE",
  LTE = "LTE",
  CONTAINS = "CONTAINS",
  IN = "IN",
  NOT_IN = "NOT_IN",
  BETWEEN = "BETWEEN",
  ALL_IN = "ALL_IN",
  STARTS_WITH = "STARTS_WITH",
  ELEM_MATCH = "ELEM_MATCH",
  NULL = "NULL",
  NOT_NULL = "NOT_NULL",
  TRUE = "TRUE",
  FALSE = "FALSE",
  TODAY = "TODAY",
  TOMORROW = "TOMORROW",
  THIS_WEEK = "THIS_WEEK",
  NEXT_WEEK = "NEXT_WEEK",
  LAST_WEEK = "LAST_WEEK",
  THIS_MONTH = "THIS_MONTH",
  LAST_MONTH = "LAST_MONTH",
  RECENT_DAYS = "RECENT_DAYS",
  RAW = "RAW"
}

export interface Condition {
  field: string;
  operator: Operator;
  value: any;
  children: Condition[];
  not: boolean;
}

export enum SortDirection {
  ASC = "ASC", DESC = "DESC"
}

export interface Sort {
  field: string;
  direction: SortDirection;
}

export interface Pagination {
  index: number;
  size: number;
}


export class Conditions {
  static and(conditions: Condition[]): Condition {
    return {field: "", operator: Operator.AND, value: "", children: conditions, not: false}
  }

  static or(conditions: Condition[]): Condition {
    return {field: "", operator: Operator.OR, value: "", children: conditions, not: false}
  }

  static id(value: string): Condition {
    return {field: "", operator: Operator.ID, value: value, children: [], not: false}
  }

  static ids(value: string[]): Condition {
    return {field: "", operator: Operator.IDS, value: value, children: [], not: false}
  }

  static deleted(value: boolean): Condition {
    return {field: "", operator: Operator.DELETED, value: value, children: [], not: false}
  }

  static tenantId(value: string): Condition {
    return {field: "", operator: Operator.TENANT_ID, value: value, children: [], not: false}
  }

  static all(): Condition {
    return {field: "", operator: Operator.ALL, value: "", children: [], not: false}
  }

  static eq(field: string, value: any): Condition {
    return {field, operator: Operator.EQ, value, children: [], not: false}
  }

  static ne(field: string, value: any): Condition {
    return {field, operator: Operator.NE, value, children: [], not: false}
  }

  static gt(field: string, value: any): Condition {
    return {field, operator: Operator.GT, value, children: [], not: false}
  }

  static lt(field: string, value: any): Condition {
    return {field, operator: Operator.LT, value, children: [], not: false}
  }

  static gte(field: string, value: any): Condition {
    return {field, operator: Operator.GTE, value, children: [], not: false}
  }

  static lte(field: string, value: any): Condition {
    return {field, operator: Operator.LTE, value, children: [], not: false}
  }

  static contains(field: string, value: any): Condition {
    return {field, operator: Operator.CONTAINS, value, children: [], not: false}
  }

  static in(field: string, value: any): Condition {
    return {field, operator: Operator.IN, value, children: [], not: false}
  }

  static notIn(field: string, value: any): Condition {
    return {field, operator: Operator.NOT_IN, value, children: [], not: false}
  }

  static between(field: string, start: any, end: any): Condition {
    return {field, operator: Operator.BETWEEN, value: [start, end], children: [], not: false}
  }

  static allIn(field: string, value: any[]): Condition {
    return {field, operator: Operator.ALL_IN, value, children: [], not: false}
  }

  static startsWith(field: string, value: any): Condition {
    return {field, operator: Operator.STARTS_WITH, value, children: [], not: false}
  }

  static elemMatch(field: string, value: Condition): Condition {
    return {field, operator: Operator.ELEM_MATCH, value: "", children: [value], not: false}
  }

  static isNull(field: string): Condition {
    return {field, operator: Operator.NULL, value: "", children: [], not: false}
  }

  static notNull(field: string): Condition {
    return {field, operator: Operator.NOT_NULL, value: "", children: [], not: false}
  }

  static isTrue(field: string): Condition {
    return {field, operator: Operator.TRUE, value: "", children: [], not: false}
  }

  static isFalse(field: string): Condition {
    return {field, operator: Operator.FALSE, value: "", children: [], not: false}
  }

  static today(field: string): Condition {
    return {field, operator: Operator.TODAY, value: "", children: [], not: false}
  }

  static tomorrow(field: string): Condition {
    return {field, operator: Operator.TOMORROW, value: "", children: [], not: false}
  }

  static thisWeek(field: string): Condition {
    return {field, operator: Operator.THIS_WEEK, value: "", children: [], not: false}
  }

  static nextWeek(field: string): Condition {
    return {field, operator: Operator.NEXT_WEEK, value: "", children: [], not: false}
  }

  static lastWeek(field: string): Condition {
    return {field, operator: Operator.LAST_WEEK, value: "", children: [], not: false}
  }

  static thisMonth(field: string): Condition {
    return {field, operator: Operator.THIS_MONTH, value: "", children: [], not: false}
  }

  static lastMonth(field: string): Condition {
    return {field, operator: Operator.LAST_MONTH, value: "", children: [], not: false}
  }

  static recentDays(field: string, days: number): Condition {
    return {field, operator: Operator.RECENT_DAYS, value: days, children: [], not: false}
  }

  static raw(raw: string): Condition {
    return {field: "", operator: Operator.RAW, value: raw, children: [], not: false}
  }

  static not(condition: Condition): Condition {
    return {
      field: condition.field,
      operator: condition.operator,
      value: condition.value,
      children: condition.children,
      not: true
    }
  }
}
