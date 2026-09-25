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
  EPOCH_TIME_UNITS,
  FIELD_CELL_IDS,
  FIELD_TONES,
  isFieldlessKind,
  isFieldName,
  SEARCH_MODES,
  STRING_COMPARISONS,
  TEMPORAL_FIELD_KIND_IDS,
  TEMPORAL_TYPES,
  type FieldDefinition,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';

/**
 * Admits a definition's fields, and each array's element fields in turn:
 * names, kinds, the declarations each kind reads, and what a search looks
 * in. `validateDefinition` calls it on `definition.fields`.
 */
export function validateFields(
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  path: IssuePath,
  inElement = false,
): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();
  // Every document field at this level, not the ones seen so far: a search
  // may name a field declared after it, and the order of a list is nobody's
  // contract.
  const documentFields = new Set(
    fields
      .filter(field => !isFieldlessKind(field.kind))
      .map(field => field.name),
  );

  fields.forEach((field, index) => {
    const at: IssuePath = [...path, index];
    if (!isFieldName(field.name))
      issues.push(
        issue('definition.field.name-invalid', [...at, 'name'], {
          field: field.name,
        }),
      );
    else if (seen.has(field.name))
      issues.push(
        issue('definition.field.duplicate', [...at, 'name'], {
          field: field.name,
        }),
      );
    seen.add(field.name);

    // Without a registered kind a filter on this field can be neither
    // admitted nor compiled, so the field is unusable rather than degraded.
    if (!kinds.get(field.kind))
      issues.push(
        issue('definition.field.kind-unregistered', [...at, 'kind'], {
          field: field.name,
          kind: field.kind,
        }),
      );

    // Wow refuses an unknown comparison by throwing, and it would throw while
    // compiling a query rather than while reading the definition.
    if (
      field.stringComparison !== undefined &&
      !STRING_COMPARISONS.includes(field.stringComparison)
    )
      issues.push(
        issue(
          'definition.field.string-comparison-invalid',
          [...at, 'stringComparison'],
          { field: field.name, value: String(field.stringComparison) },
        ),
      );

    // `filter.search` refuses an unknown mode by throwing, and a field it
    // cannot read by throwing too — both while compiling a query rather than
    // while reading the definition.
    if (
      field.searchMode !== undefined &&
      !SEARCH_MODES.includes(field.searchMode)
    )
      issues.push(
        issue('definition.field.search-mode-invalid', [...at, 'searchMode'], {
          field: field.name,
          value: String(field.searchMode),
        }),
      );

    issues.push(...validateTemporal(field, [...at, 'temporal']));

    // The renderers are a closed set `RecordTable` switches over, so a key
    // nothing switches on would not fail — it would quietly render the
    // default, and a column declared as a link would stay a string of
    // characters nobody can click.
    if (field.cell !== undefined && !FIELD_CELL_IDS.includes(field.cell))
      issues.push(
        issue('definition.field.cell-invalid', [...at, 'cell'], {
          field: field.name,
          value: String(field.cell),
        }),
      );

    // `FieldDefinition.editor` is gone (D17-11). It was declared and never
    // read: `FilterValueEditor` picks its input from the kind, the operator
    // and the value shape, so a field that named an editor got exactly the
    // control it would have got anyway — a member that looks like a
    // contract and keeps none. A definition that still writes it is a
    // warning rather than a refusal: nothing about that release stops
    // working, and refusing would take an application down over a line it
    // only has to delete.
    if ('editor' in field)
      issues.push(
        issue(
          'definition.field.editor-removed',
          [...at, 'editor'],
          { field: field.name },
          'warning',
        ),
      );

    // A tone names a theme token; one nothing maps to would leave the badge
    // neutral, which is the one thing declaring a tone was meant to change.
    (field.options ?? []).forEach((option, position) => {
      if (option.tone !== undefined && !FIELD_TONES.includes(option.tone))
        issues.push(
          issue(
            'definition.field.tone-invalid',
            [...at, 'options', position, 'tone'],
            { field: field.name, value: String(option.tone) },
          ),
        );
    });

    // A handle is not a path, so searching one would ask the backend for a
    // document field that does not exist. Naming it is as wrong as naming
    // nothing, and for the same reason.
    const missing = (field.searchFields ?? []).filter(
      name => !documentFields.has(name),
    );
    if (missing.length > 0)
      issues.push(
        issue(
          'definition.field.search-fields-unknown',
          [...at, 'searchFields'],
          { field: field.name, missing: missing.join(', ') },
        ),
      );

    // Inside an element a search has to say where it looks: Wow takes a
    // `SEARCH` in `ELEMENT_MATCH` only when it names the element's fields
    // (N4) — "wherever the backend indexes" is a question about the whole
    // record, which one entry cannot answer.
    if (
      inElement &&
      field.kind === 'search' &&
      (field.searchFields ?? []).length === 0
    )
      issues.push(
        issue(
          'definition.field.element-search-fields-required',
          [...at, 'searchFields'],
          { field: field.name },
        ),
      );

    // An element's names are its own scope: they may repeat a root field's,
    // because every reference to one is written `field.element`. Checking
    // them here is what makes a nested declaration self-contained — there is
    // no path to dangle and no second place to keep in step.
    if (field.elements !== undefined)
      issues.push(
        ...validateFields(field.elements, kinds, [...at, 'elements'], true),
      );

    issues.push(...validateElementTitle(field, kinds, [...at, 'elementTitle']));
  });

  return issues;
}

/**
 * `elementTitle` is what a cell reads each element of the array by, so a
 * name the elements do not declare would not fail — every element would
 * read as untitled, and the column would say nothing where the definition
 * meant it to say which step each event was. A title has to be a value an
 * element holds: a handle names no member of the element, and a further
 * array of objects is a list, not a name.
 */
function validateElementTitle(
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  at: IssuePath,
): Issue[] {
  const title = field.elementTitle;
  if (title === undefined) return [];
  const found = (field.elements ?? []).find(element => element.name === title);
  if (!found)
    return [
      issue('definition.field.element-title-unknown', at, {
        field: field.name,
        title,
      }),
    ];
  if (
    isFieldlessKind(found.kind, kinds.get(found.kind)) ||
    found.elements !== undefined
  )
    return [
      issue('definition.field.element-title-not-a-value', at, {
        field: field.name,
        title: found.name,
      }),
    ];
  return [];
}

/**
 * `temporal` decides what every bound of a date condition is sent as, so a
 * declaration the compiler cannot read would not fail — it would send the
 * default and be refused by the service on every query, or match nothing.
 * On a field whose conditions write no time it is a declaration that does
 * nothing, which is a mistake in code rather than a preference.
 */
function validateTemporal(field: FieldDefinition, at: IssuePath): Issue[] {
  const temporal: unknown = field.temporal;
  if (temporal === undefined) return [];
  if (!TEMPORAL_FIELD_KIND_IDS.includes(field.kind))
    return [
      issue('definition.field.temporal-misplaced', at, {
        field: field.name,
        kind: field.kind,
      }),
    ];
  // A definition is code, but a `temporal` may be built from a fetched schema,
  // so its shape is read rather than trusted.
  const declared: { type?: unknown; timeUnit?: unknown } | null =
    typeof temporal === 'object' ? temporal : null;
  const known =
    declared !== null &&
    TEMPORAL_TYPES.includes(declared.type as never) &&
    (declared.type !== 'epoch' ||
      declared.timeUnit === undefined ||
      EPOCH_TIME_UNITS.includes(declared.timeUnit as never));
  return known
    ? []
    : [
        issue('definition.field.temporal-invalid', at, {
          field: field.name,
          value: JSON.stringify(temporal),
        }),
      ];
}
