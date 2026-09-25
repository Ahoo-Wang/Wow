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

import { MAX_CURSOR_SORT_FIELDS } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  isFieldlessKind,
  summaryFunctionsOf,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type IssuePath,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import {
  isBlankLeafValue,
  isFilterLeaf,
  isPlainObject,
  issue,
  validateDataConfigBase,
  walkFilter,
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
  if (!isPlainObject(config)) return [issue('config.invalid', [])];

  const issues = validateDataConfigBase(
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
  issues.push(...validateColumns(config, byName));
  issues.push(...validateCard(config, byName));
  issues.push(...validateSummaries(config, byName));
  // A source that counts only what a condition narrows (its descriptor's
  // `COUNT_REQUIRES_FILTER`, Q3) refuses a page of every record. Not a
  // mistake to fix but a question not yet asked, and said so (「先添加一个
  // 条件」); nothing is sent until there is one.
  if (
    capability.requiresFilter === true &&
    !hasCondition(config.filter, byName, kinds)
  )
    issues.push(issue('record.filter.required', []));

  return issues;
}

/**
 * Whether a tree narrows anything: a condition with its value filled in,
 * other than 「含已删除」, which matches every record as no condition does.
 */
function hasCondition(
  tree: FilterTree,
  fields: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): boolean {
  for (const { node } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    if (node.operator === 'DELETION' && node.value === 'ALL') continue;
    const field = fields.get(node.field);
    const kind = field && kinds.get(field.kind);
    if (!field || !kind) return true;
    if (!isBlankLeafValue(node.value, node.operator, field, kind, kinds))
      return true;
  }
  return false;
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
 * is however many fields there are to sort on. The query ends on the row
 * key (`compileRecord`), which takes one of Wow's slots, so a cursor view
 * offers one fewer. A definition may lower either (`maxSortFields` on its
 * record capability), which is where a source's descriptor puts its own
 * bound. It is exported because a control that offers a field
 * has to stop where `validateSort` starts refusing — otherwise the pick is
 * admitted by the UI, refused by the kernel, and the view sits in an error
 * nobody asked for.
 */
export function maxSortFields(definition: DataViewDefinition): number {
  const own =
    definition.record?.paging === 'cursor'
      ? MAX_CURSOR_SORT_FIELDS - 1
      : definition.fields.length;
  const declared = definition.record?.maxSortFields;
  return declared === undefined ? own : Math.min(own, declared);
}

function validateSort(
  config: RecordViewConfig,
  definition: DataViewDefinition,
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const issues: Issue[] = [];
  const max = maxSortFields(definition);
  // A cursor query carries its sort in the cursor, which Wow bounds, and a
  // source may bound a paged one too (`RecordCapability.maxSortFields`).
  const bounded =
    definition.record?.paging === 'cursor' ||
    definition.record?.maxSortFields !== undefined;
  if (bounded && config.sort.length > max)
    issues.push(issue('record.sort.too-many', ['sort'], { max }));

  // The source orders by at most one array of each group (#3515): a sort
  // naming a second is refused there, so here first, at the second.
  for (const group of definition.record?.parallelArrays ?? []) {
    const named = config.sort.flatMap((entry, index) =>
      group.includes(entry.field) ? [index] : [],
    );
    if (named.length > 1)
      issues.push(
        issue('record.sort.parallel-arrays', ['sort', named[1], 'field'], {
          fields: named.map(index => config.sort[index].field).join(', '),
        }),
      );
  }

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
  fields: ReadonlyMap<string, FieldDefinition>,
): Issue[] {
  const seen = new Set<string>();
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
    // stored column may say it is pinned `'left'`, `'top'` or `3`. A
    // pinning is one yes-or-no now (D19), so every one of those is simply
    // not a pinning: read through `columnPinned` the column scrolls, and it
    // is said here so the toggle that writes the member properly is known
    // to be the repair — the treatment `width` and `hidden` get, for the
    // same reason. Left unsaid it reached the settings popover as a key
    // into a wording table and took the workbench down. A stored `false` is
    // the one value still admitted: the type writes the member or leaves it
    // out (B8), and `false` says what leaving it out says.
    if (column.pinned !== undefined && typeof column.pinned !== 'boolean')
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

    // A width is a number of pixels, so the only widths that mean anything
    // are the positive finite ones. Said here because the header's resize
    // handle writes this member and a store can hold anything: `width: 0`
    // draws a column nobody can see or grab hold of again, and `NaN` — what
    // a hand-written `"120px"` becomes on the way in — lands as an inline
    // style the browser drops, so the column silently keeps its old size
    // while the config claims otherwise.
    if (
      column.width !== undefined &&
      (typeof column.width !== 'number' ||
        !Number.isFinite(column.width) ||
        column.width <= 0)
    )
      issues.push(
        issue(
          'record.column.width-invalid',
          ['table', 'columns', index, 'width'],
          { field: column.field, width: String(column.width) },
        ),
      );

    // `hidden` is written by one checkbox and has one value: the member is
    // there, and `true`, or it is not there at all. A stored `'yes'` or
    // `false` reads as shown (`columnHidden`) rather than as a guess, and
    // is said here so the checkbox that writes the member properly is
    // known to be the repair — the same treatment `pinned` and `width` get,
    // for the same reason: the config is untrusted data.
    if (column.hidden !== undefined && column.hidden !== true)
      issues.push(
        issue(
          'record.column.hidden-invalid',
          ['table', 'columns', index, 'hidden'],
          { field: column.field, hidden: String(column.hidden) },
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
    const cell = `${summary.field}\u0000${summary.fn}`;
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
    // The definition says which aggregations the source actually supports,
    // and the field's own values say which of those mean anything: a column
    // of moments offers its earliest and its latest, never their sum
    // (`summaryFunctionsOf`).
    return summaryFunctionsOf(field).includes(summary.fn)
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
