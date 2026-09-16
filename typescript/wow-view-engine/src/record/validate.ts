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

import { MAX_CURSOR_SORT_FIELDS } from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import {
  issue,
  validateViewConfigBase,
  type FieldKindRegistry,
} from '../filter/index.js';

export interface ValidateRecordOptions {
  limits?: RuntimeLimits;
}

/**
 * Admits a record config against its definition.
 *
 * Everything the definition declares is enforced here rather than left to the
 * backend: a column that no longer exists, a sort on a field that is not
 * sortable, or a summary the field never offered is a fixable issue, not a
 * failed query.
 */
export function validateRecord(
  definition: DataViewDefinition,
  config: RecordViewConfig,
  kinds: FieldKindRegistry,
  options: ValidateRecordOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const capability = definition.record;
  if (!capability)
    return [
      issue('record.capability.missing', [], { definition: definition.id }),
    ];

  const issues = validateViewConfigBase(
    definition.fields,
    config,
    kinds,
    limits,
  );
  const byName = new Map(definition.fields.map(field => [field.name, field]));

  if (!capability.layouts.includes(config.layout))
    issues.push(
      issue('record.layout.unsupported', ['layout'], { layout: config.layout }),
    );

  issues.push(...validatePageSize(config, limits));
  issues.push(...validateSort(config, capability.paging, byName));
  issues.push(...validateColumns(config, byName));
  issues.push(...validateCard(config, byName));
  issues.push(...validateSummaries(config, byName));

  return issues;
}

function validatePageSize(
  config: RecordViewConfig,
  limits: RuntimeLimits,
): Issue[] {
  const path: IssuePath = ['pageSize'];
  if (!Number.isInteger(config.pageSize) || config.pageSize < 1)
    return [issue('record.pageSize.not-positive', path)];
  if (config.pageSize > limits.maxPageSize)
    return [
      issue('record.pageSize.too-large', path, { max: limits.maxPageSize }),
    ];
  return [];
}

function validateSort(
  config: RecordViewConfig,
  paging: 'paged' | 'cursor',
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const issues: Issue[] = [];
  // A cursor query carries its sort in the cursor, which Wow bounds.
  if (paging === 'cursor' && config.sort.length > MAX_CURSOR_SORT_FIELDS)
    issues.push(
      issue('record.sort.too-many', ['sort'], { max: MAX_CURSOR_SORT_FIELDS }),
    );

  config.sort.forEach((sort, index) => {
    const path: IssuePath = ['sort', index, 'field'];
    const field = fields.get(sort.field);
    if (!field) {
      issues.push(issue('record.field.unknown', path, { field: sort.field }));
      return;
    }
    if (!field.sortable)
      issues.push(
        issue('record.sort.not-sortable', path, { field: field.name }),
      );
  });
  return issues;
}

function validateColumns(
  config: RecordViewConfig,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  return config.table.columns.flatMap((column, index) =>
    fields.has(column.field)
      ? []
      : [
          issue('record.field.unknown', ['table', 'columns', index, 'field'], {
            field: column.field,
          }),
        ],
  );
}

function validateCard(
  config: RecordViewConfig,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const issues: Issue[] = [];
  const check = (name: string | undefined, path: IssuePath) => {
    if (name !== undefined && !fields.has(name))
      issues.push(issue('record.field.unknown', path, { field: name }));
  };

  check(config.card.title, ['card', 'title']);
  check(config.card.image, ['card', 'image']);
  config.card.fields.forEach((name, index) =>
    check(name, ['card', 'fields', index]),
  );
  return issues;
}

function validateSummaries(
  config: RecordViewConfig,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  return (config.summaries ?? []).flatMap((summary, index) => {
    const path: IssuePath = ['summaries', index];
    const field = fields.get(summary.field);
    if (!field)
      return [
        issue('record.field.unknown', [...path, 'field'], {
          field: summary.field,
        }),
      ];
    // The definition says which aggregations the source actually supports.
    return field.summary?.includes(summary.fn)
      ? []
      : [
          issue('record.summary.unsupported', [...path, 'fn'], {
            field: field.name,
            fn: summary.fn,
          }),
        ];
  });
}
