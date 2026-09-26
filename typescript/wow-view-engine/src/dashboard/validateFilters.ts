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

import {
  ANALYSIS_DATE_UNITS,
  MAX_DASHBOARD_FILTERS,
  filterTypeOf,
  isFieldName,
  type AnalysisDateUnit,
  type DashboardField,
  type DashboardViewConfig,
  type Issue,
  type IssuePath,
  type RuntimeLimits,
} from '../model/index.js';
import {
  issue,
  isPlainObject,
  type FieldKindRegistry,
} from '../filter/index.js';
import { filterValueIssues, isBlankFilterValue } from './filters.js';

/**
 * A board's filters as the config declares them (D22 F): each named once in
 * Wow's field syntax, and able to start where it says — a default its own kind reads, one value where it takes
 * one, and a default at all where it is required, since a required filter
 * never runs empty. The shape of every entry was checked before this runs.
 */
export function validateFilterFields(
  config: DashboardViewConfig,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  if (config.fields.length > MAX_DASHBOARD_FILTERS)
    issues.push(
      issue('dashboard.fields.too-many', ['fields'], {
        max: MAX_DASHBOARD_FILTERS,
      }),
    );
  const seen = new Set<string>();
  config.fields.forEach((field, index) => {
    const path: IssuePath = ['fields', index];
    const named = validateName(field, path, seen);
    issues.push(...named);
    if (named.length > 0) return;
    issues.push(...validateMembers(field, path));
    issues.push(...validateStart(field, path, kinds, limits));
  });
  return issues;
}

function validateName(
  field: DashboardField,
  path: IssuePath,
  seen: Set<string>,
): Issue[] {
  const at: IssuePath = [...path, 'name'];
  if (field.name.trim().length === 0)
    return [issue('dashboard.field.name-empty', at)];
  // A name outside Wow's query syntax would reach the compiler and throw.
  if (!isFieldName(field.name))
    return [issue('dashboard.field.name-invalid', at, { field: field.name })];
  if (seen.has(field.name))
    return [issue('dashboard.field.duplicate', at, { field: field.name })];
  seen.add(field.name);
  return [];
}

/**
 * The switches are written `true` or not at all, as every such member of a
 * config is; a fixed list is a list, and one with nothing on it offers
 * nothing to pick — said, not refused, since the author may be filling it.
 */
function validateMembers(field: DashboardField, path: IssuePath): Issue[] {
  const issues: Issue[] = [];
  for (const flag of ['required', 'multiple', 'oneDay'] as const)
    if (field[flag] !== undefined && field[flag] !== true)
      issues.push(
        issue('dashboard.shape.invalid', [...path, flag], {
          expected: 'true',
        }),
      );
  if (field.oneDay === true && filterTypeOf(field.kind) !== 'date')
    issues.push(
      issue('dashboard.field.one-day-not-date', [...path, 'oneDay'], {
        field: field.name,
      }),
    );
  const options: unknown = field.options;
  if (options !== undefined && !Array.isArray(options))
    issues.push(
      issue('dashboard.shape.invalid', [...path, 'options'], {
        expected: 'array',
      }),
    );
  else if (Array.isArray(options) && options.length === 0)
    issues.push(
      issue(
        'dashboard.field.options-empty',
        [...path, 'options'],
        { field: field.name },
        'warning',
      ),
    );
  return issues;
}

/** What the filter starts at, and that a required one starts somewhere. */
function validateStart(
  field: DashboardField,
  path: IssuePath,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const at: IssuePath = [...path, 'default'];
  const start = field.default;
  if (start === undefined || isBlankFilterValue(field, start, kinds))
    return field.required === true
      ? [
          issue('dashboard.field.required-no-default', at, {
            field: field.name,
          }),
        ]
      : [];
  return filterValueIssues(field, start, kinds, at, limits);
}

/**
 * The board's time grouping (D22 F): at least one unit to offer, each a date
 * unit Wow buckets by and offered once, and a default among them.
 */
export function validateTimeGrouping(config: DashboardViewConfig): Issue[] {
  const grouping: unknown = config.timeGrouping;
  if (grouping === undefined) return [];
  const path: IssuePath = ['timeGrouping'];
  if (!isPlainObject(grouping) || !Array.isArray(grouping.units))
    return [issue('dashboard.shape.invalid', path, { expected: 'object' })];
  const units: unknown[] = grouping.units;
  if (units.length === 0)
    return [issue('dashboard.grouping.units-empty', [...path, 'units'])];
  const issues: Issue[] = [];
  const seen = new Set<unknown>();
  units.forEach((unit, index) => {
    const at: IssuePath = [...path, 'units', index];
    if (!ANALYSIS_DATE_UNITS.includes(unit as AnalysisDateUnit))
      issues.push(
        issue('dashboard.grouping.unit-unknown', at, { unit: String(unit) }),
      );
    else if (seen.has(unit))
      issues.push(
        issue('dashboard.grouping.unit-duplicate', at, { unit: String(unit) }),
      );
    seen.add(unit);
  });
  if (!units.includes(grouping.default))
    issues.push(
      issue('dashboard.grouping.unit-unknown', [...path, 'default'], {
        unit: String(grouping.default),
      }),
    );
  return issues;
}
