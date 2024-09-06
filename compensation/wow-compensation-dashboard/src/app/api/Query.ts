export enum Operator {
  AND = "AND",
  OR = "OR",
  NOR = "NOR",
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

export interface Projection {
  include: string[];
  exclude: string[];
}

export class Projections {
  static all(): Projection {
    return {include: [], exclude: []}
  }

  static includeState(): Projection {
    return {include: ["state"], exclude: []}
  }
}

export interface Condition {
  field: string;
  operator: Operator;
  value: any;
  children: Condition[];
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
    return {field: "", operator: Operator.AND, value: "", children: conditions}
  }

  static or(conditions: Condition[]): Condition {
    return {field: "", operator: Operator.OR, value: "", children: conditions}
  }

  static nor(conditions: Condition[]): Condition {
    return {field: "", operator: Operator.NOR, value: "", children: conditions}
  }

  static id(value: string): Condition {
    return {field: "", operator: Operator.ID, value: value, children: []}
  }

  static ids(value: string[]): Condition {
    return {field: "", operator: Operator.IDS, value: value, children: []}
  }

  static deleted(value: boolean): Condition {
    return {field: "", operator: Operator.DELETED, value: value, children: []}
  }

  static tenantId(value: string): Condition {
    return {field: "", operator: Operator.TENANT_ID, value: value, children: []}
  }

  static all(): Condition {
    return {field: "", operator: Operator.ALL, value: "", children: []}
  }

  static eq(field: string, value: any): Condition {
    return {field, operator: Operator.EQ, value, children: []}
  }

  static ne(field: string, value: any): Condition {
    return {field, operator: Operator.NE, value, children: []}
  }

  static gt(field: string, value: any): Condition {
    return {field, operator: Operator.GT, value, children: []}
  }

  static lt(field: string, value: any): Condition {
    return {field, operator: Operator.LT, value, children: []}
  }

  static gte(field: string, value: any): Condition {
    return {field, operator: Operator.GTE, value, children: []}
  }

  static lte(field: string, value: any): Condition {
    return {field, operator: Operator.LTE, value, children: []}
  }

  static contains(field: string, value: any): Condition {
    return {field, operator: Operator.CONTAINS, value, children: []}
  }

  static in(field: string, value: any): Condition {
    return {field, operator: Operator.IN, value, children: []}
  }

  static notIn(field: string, value: any): Condition {
    return {field, operator: Operator.NOT_IN, value, children: []}
  }

  static between(field: string, start: any, end: any): Condition {
    return {field, operator: Operator.BETWEEN, value: [start, end], children: []}
  }

  static allIn(field: string, value: any[]): Condition {
    return {field, operator: Operator.ALL_IN, value, children: []}
  }

  static startsWith(field: string, value: any): Condition {
    return {field, operator: Operator.STARTS_WITH, value, children: []}
  }

  static elemMatch(field: string, value: Condition): Condition {
    return {field, operator: Operator.ELEM_MATCH, value: "", children: [value]}
  }

  static isNull(field: string): Condition {
    return {field, operator: Operator.NULL, value: "", children: []}
  }

  static notNull(field: string): Condition {
    return {field, operator: Operator.NOT_NULL, value: "", children: []}
  }

  static isTrue(field: string): Condition {
    return {field, operator: Operator.TRUE, value: "", children: []}
  }

  static isFalse(field: string): Condition {
    return {field, operator: Operator.FALSE, value: "", children: []}
  }

  static today(field: string): Condition {
    return {field, operator: Operator.TODAY, value: "", children: []}
  }

  static tomorrow(field: string): Condition {
    return {field, operator: Operator.TOMORROW, value: "", children: []}
  }

  static thisWeek(field: string): Condition {
    return {field, operator: Operator.THIS_WEEK, value: "", children: []}
  }

  static nextWeek(field: string): Condition {
    return {field, operator: Operator.NEXT_WEEK, value: "", children: []}
  }

  static lastWeek(field: string): Condition {
    return {field, operator: Operator.LAST_WEEK, value: "", children: []}
  }

  static thisMonth(field: string): Condition {
    return {field, operator: Operator.THIS_MONTH, value: "", children: []}
  }

  static lastMonth(field: string): Condition {
    return {field, operator: Operator.LAST_MONTH, value: "", children: []}
  }

  static recentDays(field: string, days: number): Condition {
    return {field, operator: Operator.RECENT_DAYS, value: days, children: []}
  }

  static raw(raw: string): Condition {
    return {field: "", operator: Operator.RAW, value: raw, children: []}
  }
}
