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
  columnPin,
  DEFAULT_RUNTIME_LIMITS,
  isFieldlessKind,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import {
  isPlainObject,
  issue,
  validateViewConfigBase,
  type FieldKindRegistry,
} from '../filter/index.js';
import { pinnedEnd, type ColumnPlacement } from './project.js';

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
  if (!isPlainObject(config)) return [issue('config.invalid', [])];

  const issues = validateViewConfigBase(
    definition.fields,
    config,
    kinds,
    limits,
  );
  // The rules below read `sort`, `table`, `card` and `summaries` as the
  // shapes the type promises; a config from a store may keep none of them.
  const skeleton = validateShape(config);
  if (skeleton.length > 0) return [...issues, ...skeleton];
  const byName = new Map(definition.fields.map(field => [field.name, field]));

  if (!capability.layouts.includes(config.layout))
    issues.push(
      issue('record.layout.unsupported', ['layout'], {
        layout: String(config.layout),
      }),
    );

  issues.push(...validatePageSize(config, limits));
  issues.push(...validateSort(config, definition, byName));
  issues.push(...validateColumns(config, capability.rowKey, byName));
  issues.push(...validateCard(config, byName));
  issues.push(...validateSummaries(config, byName));

  return issues;
}

/**
 * Whether the config has the skeleton the rules read through. Each part is
 * reported at its own path; the entries are asked only for a `field`, since
 * what a field must be is the rules' question, not this one's.
 */
function validateShape(config: RecordViewConfig): Issue[] {
  const issues: Issue[] = [];
  const namesField = (entry: unknown): entry is Record<string, unknown> =>
    isPlainObject(entry) && typeof entry.field === 'string';
  const listOf = (
    list: unknown,
    path: IssuePath,
    code: string,
    entryIs: (entry: unknown) => boolean,
  ) => {
    if (!Array.isArray(list)) {
      issues.push(issue(code, path));
      return;
    }
    list.forEach((entry, index) => {
      if (!entryIs(entry)) issues.push(issue(code, [...path, index]));
    });
  };

  listOf(config.sort, ['sort'], 'record.sort.invalid', namesField);
  if (!isPlainObject(config.table))
    issues.push(issue('record.table.invalid', ['table']));
  else
    listOf(
      config.table.columns,
      ['table', 'columns'],
      'record.table.invalid',
      namesField,
    );
  if (!isPlainObject(config.card)) {
    issues.push(issue('record.card.invalid', ['card']));
  } else {
    if (typeof config.card.title !== 'string')
      issues.push(issue('record.card.invalid', ['card', 'title']));
    listOf(
      config.card.fields,
      ['card', 'fields'],
      'record.card.invalid',
      entry => typeof entry === 'string',
    );
  }
  if (config.summaries !== undefined)
    listOf(
      config.summaries,
      ['summaries'],
      'record.summaries.invalid',
      entry => namesField(entry) && typeof entry.fn === 'string',
    );
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

/**
 * How many fields this definition may be sorted on at once.
 *
 * A cursor is a position in one total order and Wow bounds how many fields
 * that order is built from; a paged query has no such bound, so the answer
 * is however many fields there are to sort on. It is exported because a
 * control that offers a field has to stop where `validateSort` starts
 * refusing — otherwise the pick is admitted by the UI, refused by the
 * kernel, and the view sits in an error nobody asked for.
 */
export function maxSortFields(definition: DataViewDefinition): number {
  return definition.record?.paging === 'cursor'
    ? MAX_CURSOR_SORT_FIELDS
    : definition.fields.length;
}

function validateSort(
  config: RecordViewConfig,
  definition: DataViewDefinition,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const issues: Issue[] = [];
  const max = maxSortFields(definition);
  // A cursor query carries its sort in the cursor, which Wow bounds.
  if (definition.record?.paging === 'cursor' && config.sort.length > max)
    issues.push(issue('record.sort.too-many', ['sort'], { max }));

  const seen = new Set<string>();
  config.sort.forEach((sort, index) => {
    const path: IssuePath = ['sort', index, 'field'];
    // A cursor is a position in one total order, so Wow's gateway refuses a
    // repeated sort field outright; on a paged source the repeat is dead
    // weight that still reads as a second ordering in the editor.
    if (seen.has(sort.field))
      issues.push(issue('record.sort.duplicate', path, { field: sort.field }));
    seen.add(sort.field);

    // The shape check asks a sort entry for a `field` and nothing else, so
    // a stored config may name a direction of `up`, or none at all. Left
    // unsaid it reaches the editor as a key into a wording table and takes
    // the workbench down with it; said here it is a finding like any other,
    // and the draft stays in the error state until it is fixed.
    if (sort.direction !== 'ASC' && sort.direction !== 'DESC')
      issues.push(
        issue('record.sort.direction-invalid', ['sort', index, 'direction'], {
          field: sort.field,
          direction: String(sort.direction),
        }),
      );

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
  rowKey: string,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const seen = new Set<string>();
  // The other column whose pinning the config has no say in: the one the
  // table draws last. Found the way the projection finds it, over the
  // columns that can actually be drawn, so the two never disagree about
  // which column that is.
  const end = pinnedEnd(
    config.table.columns.flatMap(column => {
      const field = fields.get(column.field);
      return field && !isFieldlessKind(field.kind)
        ? [
            {
              field: column.field,
              pinned:
                column.field === rowKey ? 'left' : columnPin(column.pinned),
            } satisfies ColumnPlacement,
          ]
        : [];
    }),
    rowKey,
  );
  return config.table.columns.flatMap((column, index) => {
    const at: IssuePath = ['table', 'columns', index, 'field'];
    const issues: Issue[] = [];
    // The field is the column's identity, all the way to the React key of the
    // rendered header, so a repeat is two columns claiming one identity.
    if (seen.has(column.field))
      issues.push(
        issue('record.column.duplicate', at, { field: column.field }),
      );
    seen.add(column.field);

    // The shape check asks a column for a `field` and nothing else, so a
    // stored column may be pinned `'top'`, or to `''`. Said here it is a
    // finding the user can fix; left unsaid it reached the settings popover
    // as a key into a wording table and took the workbench down.
    //
    // Except on the two ends, whose pinning the config has no opinion about:
    // `projectRecord` holds the row key on the left and the last column on
    // the right whatever is stored, and the settings show both fixed and
    // disabled. Reporting a value nothing on screen decides would block the
    // query and the save over something no control can change — the trap in
    // `ui/record.md`, sprung by the check meant to avoid one. What the
    // config cannot say cannot be wrong.
    if (
      column.field !== rowKey &&
      column.field !== end &&
      column.pinned !== undefined &&
      columnPin(column.pinned) === null
    )
      issues.push(
        issue(
          'record.column.pin-invalid',
          ['table', 'columns', index, 'pinned'],
          {
            field: column.field,
            pinned: String(column.pinned),
          },
        ),
      );

    const field = fields.get(column.field);
    if (!field)
      return [
        ...issues,
        issue('record.field.unknown', at, { field: column.field }),
      ];
    return isFieldlessKind(field.kind)
      ? [
          ...issues,
          issue('record.field.not-a-column', at, { field: column.field }),
        ]
      : issues;
  });
}

function validateCard(
  config: RecordViewConfig,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const issues: Issue[] = [];
  // A card reads the same rows a table does, so the same two rules hold: the
  // field has to exist, and a field-less kind's name is a handle for the
  // editor rather than a path into a row, so it would be blank on every card.
  const check = (name: string | undefined, path: IssuePath) => {
    if (name === undefined) return;
    const field = fields.get(name);
    if (!field) {
      issues.push(issue('record.field.unknown', path, { field: name }));
      return;
    }
    if (isFieldlessKind(field.kind))
      issues.push(issue('record.field.not-a-column', path, { field: name }));
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
  const seen = new Set<string>();
  return (config.summaries ?? []).flatMap((summary, index) => {
    const path: IssuePath = ['summaries', index];
    const issues: Issue[] = [];
    // One cell per field and function: the pair is what `summaryAlias` names,
    // and two cells sharing an alias would read back the same one number.
    const cell = `${summary.field} ${summary.fn}`;
    if (seen.has(cell))
      issues.push(
        issue('record.summary.duplicate', path, {
          field: summary.field,
          fn: summary.fn,
        }),
      );
    seen.add(cell);

    const field = fields.get(summary.field);
    if (!field)
      return [
        ...issues,
        issue('record.field.unknown', [...path, 'field'], {
          field: summary.field,
        }),
      ];
    // The definition says which aggregations the source actually supports.
    return field.summary?.includes(summary.fn)
      ? issues
      : [
          ...issues,
          issue('record.summary.unsupported', [...path, 'fn'], {
            field: field.name,
            fn: summary.fn,
          }),
        ];
  });
}
