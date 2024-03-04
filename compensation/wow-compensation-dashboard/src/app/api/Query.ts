export enum Operator {
  AND = "AND",
  OR = "OR",
  EMPTY = "EMPTY",
  EQ = "EQ",
  NE = "NE",
  GT = "GT",
  LT = "LT",
  GTE = "GTE",
  LTE = "LTE",
  LIKE = "LIKE",
  IN = "IN",
  NOT_IN = "NOT_IN",
  BETWEEN = "BETWEEN",
  ALL = "ALL",
  STATS_WITH = "STATS_WITH",
  ELEM_MATCH = "ELEM_MATCH",
  NULL = "NULL",
  NOT_NULL = "NOT_NULL",
  TRUE = "TRUE",
  FALSE = "FALSE"
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

  static empty(): Condition {
    return {field: "", operator: Operator.EMPTY, value: "", children: []}
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

  static like(field: string, value: any): Condition {
    return {field, operator: Operator.LIKE, value, children: []}
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

  static all(field: string, value: any[]): Condition {
    return {field, operator: Operator.ALL, value, children: []}
  }

  static startsWith(field: string, value: any): Condition {
    return {field, operator: Operator.STATS_WITH, value, children: []}
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
}
