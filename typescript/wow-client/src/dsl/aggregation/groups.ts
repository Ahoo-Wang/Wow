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

import { constantName } from '../constantName.js';
import type { QueryField } from '../filter/index.js';
import { aggregationField } from './aliases.js';
import { requireValidExpressionTrees } from './expressionTrees.js';
import type { AggregationExpression } from './types.js';
import { aggregationAlias } from './aliases.js';

/** The kind of an {@link AggregationGroup}, sent as its `type`. */
export enum AggregationGroupType {
  /** One group per distinct value; {@link aggregation.terms}. */
  TERMS = 'terms',
  /** Number buckets of equal width; {@link aggregation.histogram}. */
  HISTOGRAM = 'histogram',
  /** Calendar buckets of a time field; {@link aggregation.dateHistogram}. */
  DATE_HISTOGRAM = 'date-histogram',
  /**
   * One calendar part of a time field, such as the weekday or the hour;
   * {@link aggregation.datePart}. Wow 9.2 and later.
   */
  DATE_PART = 'date-part',
}

/**
 * What an {@link AggregationGroup}'s field is relative to when the query
 * expands elements, sent as its `scope`. Wow 9.9 and later.
 */
export enum AggregationGroupScope {
  /** The innermost expanded element; the default. */
  ELEMENT = 'element',
  /** The root record, so the elements are grouped by a field of their root. */
  ROOT = 'root',
}

/**
 * The calendar unit of a `DATE_HISTOGRAM` bucket, cut in the group's
 * `timeZone`.
 */
export enum AggregationDateUnit {
  YEAR = 'year',
  QUARTER = 'quarter',
  MONTH = 'month',
  WEEK = 'week',
  DAY = 'day',
  HOUR = 'hour',
  MINUTE = 'minute',
  SECOND = 'second',
}

/**
 * The calendar part a `DATE_PART` group takes of each instant, read in the
 * group's `timeZone`. Its keys are integers from a fixed domain, so records
 * from different days, weeks or years fall into one bucket.
 */
export enum AggregationDatePart {
  /** The ISO weekday: 1 is Monday, 7 is Sunday. */
  DAY_OF_WEEK = 'day-of-week',
  /**
   * The day of the month, 1 to 31; days 29 to 31 exist only in the months
   * that have them.
   */
  DAY_OF_MONTH = 'day-of-month',
  /** The hour on the wall clock of the group's `timeZone`, 0 to 23. */
  HOUR_OF_DAY = 'hour-of-day',
  /** The month of the year, 1 (January) to 12. */
  MONTH_OF_YEAR = 'month-of-year',
}

/** What every {@link AggregationGroup} has. */
interface AggregationGroupBase<FIELDS extends string = string> {
  /** The field the rows are grouped by. */
  field: QueryField<FIELDS>;
  /** The name of the group's column in the result rows. */
  alias: string;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
}

/**
 * What a TERMS or HISTOGRAM group groups by: a field, or a computed
 * expression in its place (Wow 9.2 and later), never both. An expression
 * input is an expensive computation the server admits only where
 * `analysis.expressions` is `true`.
 */
export type AggregationGroupInput<FIELDS extends string = string> =
  | {
      /** The field the rows are grouped by. */
      field: QueryField<FIELDS>;
      expression?: undefined;
    }
  | {
      field?: undefined;
      /** The per-row value the rows are grouped by, such as a `DATE_DIFF`. */
      expression: AggregationExpression<FIELDS>;
    };

/**
 * One group per distinct value of a field, or of an expression;
 * {@link aggregation.terms}. `missingKey` needs a field.
 */
export type TermsAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.TERMS;
  /** The name of the group's column in the result rows. */
  alias: string;
  /** The key the rows without a value are grouped under. Wow 9.1 and later. */
  missingKey?: string;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
} & AggregationGroupInput<FIELDS>;

/**
 * Buckets of equal width over a number field, or over an expression;
 * {@link aggregation.histogram}.
 */
export type HistogramAggregationGroup<FIELDS extends string = string> = {
  type: AggregationGroupType.HISTOGRAM;
  /** The name of the group's column in the result rows. */
  alias: string;
  /** The width of each bucket, greater than 0. */
  interval: number;
  /**
   * What `field` is relative to when the query expands elements: the
   * innermost element (`ELEMENT`, the default) or the root record (`ROOT`).
   * A `ROOT` group names a field, never an expression. Wow 9.9 and later,
   * where `analysis.rootGroups` is `true`.
   */
  scope?: AggregationGroupScope;
} & AggregationGroupInput<FIELDS>;

/**
 * Calendar buckets over a time field in epoch milliseconds;
 * {@link aggregation.dateHistogram}.
 */
export interface DateHistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_HISTOGRAM;
  /** The calendar unit of each bucket. */
  unit: AggregationDateUnit;
  /** The IANA zone the buckets are cut in; `UTC` when absent. */
  timeZone?: string;
  /**
   * Whether the server fills in the empty buckets of the range. Wow 9.1 and
   * later, and only when this is the query's only group.
   */
  dense?: boolean;
}

/**
 * One calendar part of a time field in epoch milliseconds, such as the
 * weekday or the hour; {@link aggregation.datePart}. Its keys in the result
 * rows are integers from the part's domain, such as 1 (Monday) to 7 (Sunday)
 * for `DAY_OF_WEEK`. Wow 9.2 and later.
 */
export interface DatePartAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_PART;
  /** The calendar part each row is grouped by. */
  part: AggregationDatePart;
  /** The IANA zone the part is read in; `UTC` when absent. */
  timeZone?: string;
  /**
   * Whether the server returns every key of the part's domain, filling a
   * key with no records with each metric's empty value. Only when this is
   * the query's only group.
   */
  dense?: boolean;
}

/**
 * One dimension of an {@link AggregationQuery}'s `groupBy`: each result row
 * is one combination of the groups' keys.
 */
export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>
  | DatePartAggregationGroup<FIELDS>;

/** The option every group builder takes. */
export interface AggregationGroupScopeOptions {
  /**
   * What the field is relative to when the query expands elements; the
   * innermost element when absent. `ROOT` needs a field input. Wow 9.9 and
   * later.
   */
  scope?: AggregationGroupScope;
}

/** The options of {@link aggregation.terms}. */
export interface TermsAggregationOptions extends AggregationGroupScopeOptions {
  /** The key rows without a value are grouped under; must not be blank. */
  missingKey?: string;
}

/** The options of {@link aggregation.histogram}. */
export interface HistogramAggregationOptions extends AggregationGroupScopeOptions {
  /** The width of each bucket: finite and greater than 0. */
  interval: number;
}

/** The options of {@link aggregation.dateHistogram}. */
export interface DateHistogramAggregationOptions extends AggregationGroupScopeOptions {
  /** The calendar unit of each bucket. */
  unit: AggregationDateUnit;
  /** The zone the buckets are cut in, an IANA id. Defaults to `UTC`. */
  timeZone?: string;
  /**
   * Whether the server fills in the empty buckets of the range. Only when
   * this is the only group.
   */
  dense?: boolean;
}

/** The options of {@link aggregation.datePart}. */
export interface DatePartAggregationOptions extends AggregationGroupScopeOptions {
  /** The calendar part each row is grouped by. */
  part: AggregationDatePart;
  /** The zone the part is read in, an IANA id. Defaults to `UTC`. */
  timeZone?: string;
  /**
   * Whether the server returns every key of the part's domain, the empty
   * ones filled in. Only when this is the only group.
   */
  dense?: boolean;
}

/**
 * The input of a TERMS or HISTOGRAM group: a field path, or an expression in
 * its place, never both, as Wow's `requireGroupInput` has it.
 */
export function groupInput<FIELDS extends string>(
  input: FIELDS | AggregationExpression<FIELDS>,
  type: string,
): AggregationGroupInput<FIELDS> {
  if (typeof input === 'string') return { field: aggregationField(input) };
  if (input === null || typeof input !== 'object') {
    throw new TypeError(
      `${constantName(type)} group requires exactly one of field and expression.`,
    );
  }
  requireValidExpressionTrees([input]);
  return { expression: input };
}

/** A group's `scope`, sent only when given; `ROOT` needs a field input. */
export function groupScope(
  scope: AggregationGroupScope | undefined,
  type: string,
  source: { expression?: unknown },
): { scope?: AggregationGroupScope } {
  if (scope === undefined) return {};
  if (!Object.values(AggregationGroupScope).includes(scope)) {
    throw new TypeError(`${constantName(type)} group scope is invalid.`);
  }
  if (scope === AggregationGroupScope.ROOT && source.expression) {
    throw new TypeError(
      `${constantName(type)} with a ROOT scope requires a field input.`,
    );
  }
  return { scope };
}

/** The builders of the groups of an aggregation query: terms, histograms, date histograms and date parts. */
export const groupBuilders = {
  /**
   * Groups by each distinct value of a field, or of a computed expression.
   * `{ type: 'terms', field, alias, missingKey? }`, or
   * `{ type: 'terms', expression, alias }`.
   *
   * @param input - The field to group by, or an expression in its place
   *   (Wow 9.2 and later, an expensive computation).
   * @param alias - The name of the group column in the result rows.
   * @param options.missingKey - The key of the rows without a value; only
   *   with a field.
   * @param options.scope - What the field is relative to when the query
   *   expands elements: `ROOT` groups the elements by a field of their root
   *   record. Wow 9.9 and later.
   * @throws TypeError when `input` or `alias` is invalid, `missingKey` is
   *   blank, or `missingKey` is given with an expression.
   * @example
   * ```typescript
   * aggregation.terms('state.status', 'status', { missingKey: 'NONE' });
   * aggregation.terms(
   *   aggregation.dateDiff('state.paidAt', 'state.shippedAt', DateDiffUnit.DAY),
   *   'daysToShip',
   * );
   * ```
   */
  terms<FIELDS extends string>(
    input: FIELDS | AggregationExpression<FIELDS>,
    alias: string,
    { missingKey, scope }: TermsAggregationOptions = {},
  ): TermsAggregationGroup<FIELDS> {
    if (
      missingKey !== undefined &&
      (typeof missingKey !== 'string' || !missingKey.trim())
    ) {
      throw new TypeError('terms missingKey must not be blank.');
    }
    const source = groupInput(input, 'terms');
    if (missingKey !== undefined && source.expression) {
      throw new TypeError('terms missingKey requires a field input.');
    }
    return {
      type: AggregationGroupType.TERMS,
      ...(missingKey === undefined ? {} : { missingKey }),
      ...source,
      ...groupScope(scope, 'terms', source),
      alias: aggregationAlias(alias),
    };
  },
  /**
   * Groups a number field, or a computed expression, into buckets of equal
   * width. `{ type: 'histogram', field | expression, interval, alias }`.
   *
   * @param input - The number field to bucket, or an expression in its
   *   place (Wow 9.2 and later, an expensive computation).
   * @param alias - The name of the bucket column in the result rows.
   * @param options.interval - The bucket width.
   * @param options.scope - What the field is relative to when the query
   *   expands elements: `ROOT` groups the elements by a field of their root
   *   record. Wow 9.9 and later.
   * @throws TypeError when `interval` is not finite and greater than 0, or
   *   `field` or `alias` is invalid.
   * @example
   * ```typescript
   * aggregation.histogram('state.amount', 'amountBand', { interval: 100 });
   * ```
   */
  histogram<FIELDS extends string>(
    input: FIELDS | AggregationExpression<FIELDS>,
    alias: string,
    { interval, scope }: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS> {
    if (!Number.isFinite(interval) || interval <= 0) {
      throw new TypeError(
        'histogram interval must be finite and greater than 0.',
      );
    }
    const source = groupInput(input, 'histogram');
    return {
      type: AggregationGroupType.HISTOGRAM,
      ...source,
      ...groupScope(scope, 'histogram', source),
      interval,
      alias: aggregationAlias(alias),
    };
  },
  /**
   * Groups a time field into calendar buckets.
   * `{ type: 'date-histogram', field, unit, alias, timeZone, dense? }`.
   *
   * @param field - The time field (epoch milliseconds) to bucket.
   * @param alias - The name of the bucket column in the result rows.
   * @param options.unit - The calendar unit of a bucket.
   * @param options.timeZone - The zone buckets are cut in. Defaults to `UTC`.
   * @param options.dense - Whether the empty buckets of the range are filled
   *   in.
   * @param options.scope - What the field is relative to when the query
   *   expands elements: `ROOT` groups the elements by a field of their root
   *   record. Wow 9.9 and later.
   * @throws TypeError when `unit` is not an `AggregationDateUnit`,
   *   `timeZone` is blank, `dense` is not a boolean, or `field` or `alias` is
   *   invalid.
   * @example
   * ```typescript
   * aggregation.dateHistogram('createTime', 'day', {
   *   unit: AggregationDateUnit.DAY,
   *   timeZone: 'Asia/Shanghai',
   * });
   * ```
   */
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { unit, timeZone = 'UTC', dense, scope }: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS> {
    if (dense !== undefined && typeof dense !== 'boolean') {
      throw new TypeError('date histogram dense must be boolean.');
    }
    if (!Object.values(AggregationDateUnit).includes(unit)) {
      throw new TypeError('date histogram unit is invalid.');
    }
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date histogram timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_HISTOGRAM,
      ...(dense === undefined ? {} : { dense }),
      field: aggregationField(field),
      ...groupScope(scope, 'date-histogram', {}),
      unit,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
  /**
   * Groups a time field by one calendar part of each instant, such as the
   * weekday or the hour, so records from different days share a bucket.
   * `{ type: 'date-part', field, part, alias, timeZone, dense? }`.
   *
   * The group's keys in the result rows are integers: `DAY_OF_WEEK` is the
   * ISO weekday, 1 (Monday) to 7 (Sunday); `DAY_OF_MONTH` 1 to 31;
   * `HOUR_OF_DAY` 0 to 23 on the wall clock of `timeZone`; `MONTH_OF_YEAR`
   * 1 to 12. With `dense`, every key of that domain comes back, those
   * without records carrying each metric's empty value; `dense` needs the
   * group to be the query's only one. Wow 9.2 and later.
   *
   * @param field - The time field (epoch milliseconds) to read the part of.
   * @param alias - The name of the part column in the result rows.
   * @param options.part - The calendar part to group by.
   * @param options.timeZone - The zone the part is read in. Defaults to
   *   `UTC`.
   * @param options.dense - Whether every key of the part's domain is
   *   returned.
   * @param options.scope - What the field is relative to when the query
   *   expands elements: `ROOT` groups the elements by a field of their root
   *   record. Wow 9.9 and later.
   * @throws TypeError when `part` is not an `AggregationDatePart`,
   *   `timeZone` is blank, `dense` is not a boolean, or `field` or `alias` is
   *   invalid.
   * @example
   * ```typescript
   * aggregation.datePart('createTime', 'weekday', {
   *   part: AggregationDatePart.DAY_OF_WEEK,
   *   timeZone: 'Asia/Shanghai',
   * });
   * ```
   */
  datePart<FIELDS extends string>(
    field: FIELDS,
    alias: string,
    { part, timeZone = 'UTC', dense, scope }: DatePartAggregationOptions,
  ): DatePartAggregationGroup<FIELDS> {
    if (dense !== undefined && typeof dense !== 'boolean') {
      throw new TypeError('date part dense must be boolean.');
    }
    if (!Object.values(AggregationDatePart).includes(part)) {
      throw new TypeError('date part is invalid.');
    }
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date part timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_PART,
      ...(dense === undefined ? {} : { dense }),
      field: aggregationField(field),
      ...groupScope(scope, 'date-part', {}),
      part,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
};
