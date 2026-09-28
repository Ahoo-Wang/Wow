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

/**
 * A field as `defineView` builds it (host-integration.md 3): its kind, its
 * values and whether it sorts are the descriptor's; its label, its order,
 * its cell and what it narrows are the host's. The operators are left to
 * the kind unless the host narrows them — the engine cuts them to the
 * descriptor in force whenever a view runs (N5), so a list copied here
 * would only go stale.
 */

import type {
  FieldDescriptor,
  QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import type {
  FieldDefinition,
  FieldKindId,
  FieldOption,
  Issue,
  PagingMode,
} from '../../model/index.js';
import { issue } from '../../filter/index.js';
import {
  describedField,
  type DescribedField,
} from '../../capabilities/match.js';
import type { FieldSpec } from './spec.js';

/** What building the fields reads, and where it says what it found. */
export interface FieldBuild {
  descriptor: QueryModelDescriptor;
  paging: PagingMode;
  findings: Issue[];
}

/** One built field and what it is over, for the analyses to read. */
export interface BuiltField {
  field: FieldDefinition;
  described: DescribedField | null;
  spec: FieldSpec;
  /** Whether the value is sensitive: masked, so never grouped or measured. */
  sensitive: boolean;
  /** An array's entries, built as its fields are. */
  children?: BuiltField[];
}

const found = (
  code: string,
  params: Record<string, string>,
  severity: Issue['severity'] = 'error',
): Issue => issue(code, ['fields'], params, severity);

/** The fields listed, built in the order listed; one the descriptor lacks is left out. */
export function buildFields(
  listed: Readonly<Record<string, FieldSpec | string>>,
  scope: string | undefined,
  build: FieldBuild,
): BuiltField[] {
  return Object.entries(listed).flatMap(([key, given]) => {
    const spec: FieldSpec =
      typeof given === 'string' ? { label: given } : given;
    const built = buildField(key, spec, scope, build);
    return built ? [built] : [];
  });
}

function buildField(
  key: string,
  spec: FieldSpec,
  scope: string | undefined,
  build: FieldBuild,
): BuiltField | null {
  const { descriptor, findings } = build;
  const path = scope === undefined ? key : `${scope}.${key}`;
  if (spec.search) return searchField(key, spec, scope, build);

  const described = describedField(descriptor, path, scope);
  if (!described) {
    findings.push(found('definition.field.undescribed', { field: path }));
    return null;
  }
  const raw = fieldDescriptor(descriptor, described.canonical ?? path, scope);
  const kind = spec.kind ?? kindOf(raw, described, spec);
  if (kind === null) {
    findings.push(found('definition.field.kind-unknown', { field: path }));
    return null;
  }
  const field: FieldDefinition = {
    name: key,
    label: labelOf(spec, raw, path, findings),
    kind,
  };
  const comparable = described.comparable !== false;

  // An entry's field is read inside its record, not what records are
  // ordered by: offered as a sort only where the host asks.
  const sorts =
    comparable &&
    (build.paging === 'cursor' ? described.sort.cursor : described.sort.paged);
  const sortsByDefault = sorts && scope === undefined;
  if (spec.sortable === true && !sorts)
    findings.push(
      found('definition.field.sort-wider', { field: path }, 'warning'),
    );
  if (sorts && (spec.sortable ?? sortsByDefault)) field.sortable = true;

  if (spec.operators) {
    const offered = operatorsOffered(descriptor, described, path);
    for (const operator of spec.operators)
      if (!offered.has(operator))
        findings.push(
          found(
            'definition.field.operator-wider',
            { field: path, operator },
            'warning',
          ),
        );
    field.operators = spec.operators;
  } else if (!comparable) field.operators = [];

  if (spec.summary) {
    const functions: readonly string[] = described.aggregate?.functions ?? [];
    for (const fn of spec.summary)
      if (fn !== 'COUNT' && !functions.includes(fn))
        findings.push(
          found(
            'definition.field.summary-wider',
            { field: path, fn },
            'warning',
          ),
        );
    field.summary = spec.summary;
  }

  const options = optionsOf(spec, described, path, findings);
  if (options) field.options = options;

  if (described.deprecated) {
    if (!spec.deprecated)
      findings.push(
        found(
          'definition.field.deprecated',
          { field: path, message: described.deprecated.message ?? '' },
          'warning',
        ),
      );
    field.deprecated = spec.deprecated ?? described.deprecated;
  }

  let children: BuiltField[] | undefined;
  if (spec.elements) {
    if (!descriptor.elements.some(element => element.path === path))
      findings.push(found('definition.field.not-elements', { field: path }));
    children = buildFields(spec.elements, path, build);
    field.elements = children.map(entry => entry.field);
  }
  if (spec.elementTitle !== undefined) field.elementTitle = spec.elementTitle;
  if (spec.cell !== undefined) field.cell = spec.cell;
  Object.assign(field, spec.more);
  return {
    field,
    described,
    spec,
    sensitive: raw?.sensitivity !== undefined,
    ...(children ? { children } : {}),
  };
}

/** A search box: its fields must be paths the descriptor has. */
function searchField(
  key: string,
  spec: FieldSpec,
  scope: string | undefined,
  build: FieldBuild,
): BuiltField {
  const search = spec.search ?? { fields: [] };
  // An entry's search names its fields within the entry (N4).
  for (const name of search.fields) {
    const path = scope === undefined ? name : `${scope}.${name}`;
    if (!describedField(build.descriptor, path, scope))
      build.findings.push(
        found('definition.field.undescribed', { field: path }),
      );
  }
  const field: FieldDefinition = {
    name: key,
    label: spec.label ?? key,
    kind: 'search',
    searchFields: search.fields,
  };
  if (search.mode) field.searchMode = search.mode;
  return { field, described: null, spec, sensitive: false };
}

/** The descriptor's own entry for a path, for what `describedField` leaves out. */
function fieldDescriptor(
  descriptor: QueryModelDescriptor,
  path: string,
  scope: string | undefined,
): FieldDescriptor | undefined {
  return (
    descriptor.fields.find(
      entry => entry.path === path && entry.scope === scope,
    ) ??
    descriptor.variants?.values
      .flatMap(variant => variant.fields)
      .find(entry => entry.path === path)
  );
}

/** The kind the descriptor says a path is; `null` where it says none. */
function kindOf(
  raw: FieldDescriptor | undefined,
  described: DescribedField,
  spec: FieldSpec,
): FieldKindId | null {
  if (spec.elements) return 'elementMatch';
  if (described.enum || spec.options) return 'enum';
  const temporal = described.semantic?.type;
  if (temporal === 'TEMPORAL_DATE') return 'date';
  if (temporal === 'TEMPORAL_EPOCH' || temporal === 'TEMPORAL_FORMATTED')
    return 'datetime';
  const types: readonly string[] = raw?.types ?? [];
  if (types.length !== 1) return null;
  switch (types[0]) {
    case 'BOOLEAN':
      return 'boolean';
    case 'INTEGER':
    case 'DECIMAL':
      return 'number';
    case 'STRING':
      return 'string';
    default:
      return null;
  }
}

/**
 * The host's word, else the domain's description, else the path — the two
 * fallbacks said, since a reader would see a program's word.
 */
function labelOf(
  spec: FieldSpec,
  raw: FieldDescriptor | undefined,
  path: string,
  findings: Issue[],
): string {
  if (spec.label !== undefined) return spec.label;
  findings.push(found('definition.field.unlabelled', { field: path }, 'note'));
  return raw?.description ?? path;
}

/**
 * The comparisons a path admits: its own operators, and a match over its
 * entries where it is an element the model filters by (capabilities.md,
 * `ELEMENT_MATCH` comes from `elements[]`).
 */
function operatorsOffered(
  descriptor: QueryModelDescriptor,
  described: DescribedField,
  path: string,
): ReadonlySet<string> {
  const offered = new Set(described.operators);
  if (descriptor.elements.some(entry => entry.path === path && entry.filter))
    offered.add('ELEMENT_MATCH');
  return offered;
}

/**
 * A category's values: the host's listed first, in its order and words,
 * then the rest of the descriptor's under their descriptions; one the host
 * hides left out. Where the descriptor lists none the host's list is the
 * whole of it.
 */
function optionsOf(
  spec: FieldSpec,
  described: DescribedField,
  path: string,
  findings: Issue[],
): FieldOption[] | undefined {
  const listed = spec.options ?? {};
  const values = described.enum;
  if (!values)
    return spec.options
      ? Object.entries(listed).flatMap(([value, given]) =>
          given === false ? [] : [option(value, given)],
        )
      : undefined;
  const known = new Map(values.map(entry => [String(entry.value), entry]));
  const options: FieldOption[] = [];
  for (const [value, given] of Object.entries(listed)) {
    const entry = known.get(value);
    if (!entry) {
      findings.push(
        found('definition.option.undescribed', { field: path, value }),
      );
      continue;
    }
    if (given !== false) options.push(option(entry.value, given));
  }
  for (const entry of values)
    if (!(String(entry.value) in listed))
      options.push({
        value: entry.value as FieldOption['value'],
        label: entry.description ?? String(entry.value),
      });
  return options;
}

function option(
  value: unknown,
  given: string | Omit<FieldOption, 'value'>,
): FieldOption {
  return typeof given === 'string'
    ? { value: value as FieldOption['value'], label: given }
    : { value: value as FieldOption['value'], ...given };
}
