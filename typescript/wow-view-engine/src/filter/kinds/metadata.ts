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

import { filter, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FilterOperatorName,
  MetadataFieldKindId,
} from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import {
  isNonEmptyString,
  isReferenceFilterValue,
  type ReferenceFilterValue,
} from '../values.js';

/**
 * The kinds backed by Wow's metadata filters.
 *
 * Every other kind compiles to a condition on a document field. These compile
 * to a filter that names no field at all — `{ op: 'OWNER_ID', value }` — so a
 * definition's `name` here is a handle for the editor, the label and issue
 * paths, and never reaches the query. `@ownerId` is the conventional spelling
 * and Wow's field syntax admits the `@` prefix, but nothing depends on it.
 *
 * They offer no presence operators. `IS_NULL` and friends do carry a field
 * name, so mixing them in would mean one leaf whose operator decides whether
 * `name` is a real path or a label — subtle in exactly the place a filter can
 * least afford it.
 */

/**
 * A value as a summary line should read it. A leaf's value is `JsonValue`, so
 * an object that is not a candidate would otherwise stringify to
 * `[object Object]` in the one place a reader looks to check what is applied.
 */
function shown(value: unknown): string {
  if (isReferenceFilterValue(value)) {
    const first = value.items[0];
    return first === undefined ? '' : (first.label ?? String(first.id));
  }
  if (Array.isArray(value)) return value.map(shown).join(', ');
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  return '';
}

/** The id a metadata filter carries, however the editor collected it. */
function idOf(value: unknown): string {
  if (typeof value === 'string') return value;
  return String(readValue<ReferenceFilterValue>(value).items[0]?.id ?? '');
}

/**
 * Whether a metadata value is still waiting to be filled in.
 *
 * A kind's `isBlank` replaces the registry's default rule rather than adding
 * to it, and these kinds start from three shapes — `''` for a typed id, `[]`
 * for a list of them, `{ items: [] }` for a picker — so all three must be
 * recognised here, or a freshly added row would be reported as an error the
 * moment it appeared. Whitespace is nothing typed, as it is for a search.
 */
function isBlankMetadataValue(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  if (typeof value === 'string') return value.trim().length === 0;
  if (Array.isArray(value)) return value.length === 0;
  return isReferenceFilterValue(value) && value.items.length === 0;
}

/**
 * Accepts what either editor produces: text typed straight in, or one picked
 * candidate carrying the label to show it by. A pasted tenant id and a chosen
 * owner are the same condition to the server, and only the editor differs.
 *
 * A blank value never arrives here: it is unfinished, not wrong.
 */
function validateSingle(value: unknown, path: (string | number)[]) {
  if (isNonEmptyString(value)) return [];
  if (isReferenceFilterValue(value))
    return value.items.length > 1
      ? [issue('filter.value.expects-one', path)]
      : [];
  return [issue('filter.value.expected-id', path)];
}

/**
 * One metadata field that takes one id: tenant, owner or workspace. Wow has
 * no plural form for these, so neither does the editor.
 */
function createSingleMetadataKind(
  id: MetadataFieldKindId,
  operator: FilterOperatorName,
  compile: (value: string) => FilterExpression,
): FieldKind {
  return {
    id,
    operators: [operator],
    defaultOperator: operator,

    emptyValue(_operator, field) {
      return field.remote ? { items: [] } : '';
    },

    isBlank({ value }) {
      return isBlankMetadataValue(value);
    },

    validate({ value, path }) {
      return validateSingle(value, path);
    },

    compile({ leaf }) {
      return compile(idOf(leaf.value));
    },

    editor(_operator, field) {
      // A raw id is unreadable, so a definition that declares candidates gets
      // a picker and the label travels with the value; otherwise it is text,
      // because an operator pasting a tenant id has nothing to pick from.
      return field.remote
        ? { input: 'remote', remote: field.remote }
        : { input: 'text' };
    },

    describe({ leaf, field }) {
      return `${field.label} ${shown(leaf.value)}`;
    },
  };
}

/**
 * A document id, which Wow lets a query name one at a time or several at
 * once. Both spellings mean "this document", so the pair is one kind and the
 * user picks between them the way they pick `EQ` and `IN` elsewhere.
 */
function createIdMetadataKind(
  id: MetadataFieldKindId,
  single: FilterOperatorName,
  multiple: FilterOperatorName,
  compileSingle: (value: string) => FilterExpression,
  compileMultiple: (values: string[]) => FilterExpression,
): FieldKind {
  return {
    id,
    operators: [single, multiple],
    defaultOperator: single,

    emptyValue(operator) {
      return operator === multiple ? [] : '';
    },

    isBlank({ value }) {
      return isBlankMetadataValue(value);
    },

    validate({ value, operator, path }) {
      if (operator !== multiple) return validateSingle(value, path);
      // An empty list never arrives here: it is unfinished, not wrong.
      return Array.isArray(value) && value.every(isNonEmptyString)
        ? []
        : [issue('filter.value.expected-id-list', path)];
    },

    compile({ leaf }) {
      return leaf.operator === multiple
        ? compileMultiple(readValue<string[]>(leaf.value))
        : compileSingle(idOf(leaf.value));
    },

    editor(operator) {
      return { input: 'text', multiple: operator === multiple };
    },

    describe({ leaf, field }) {
      return `${field.label} ${shown(leaf.value)}`;
    },
  };
}

/** The record's own id: `ID` for one, `IDS` for several. */
export const documentIdFieldKind: FieldKind = createIdMetadataKind(
  'documentId',
  'ID',
  'IDS',
  value => filter.id(value),
  values => filter.ids(values),
);

/** The aggregate the record belongs to, which is not always its own id. */
export const aggregateIdFieldKind: FieldKind = createIdMetadataKind(
  'aggregateId',
  'AGGREGATE_ID',
  'AGGREGATE_IDS',
  value => filter.aggregateId(value),
  values => filter.aggregateIds(values),
);

/** The tenant a record belongs to; a platform view narrows by it. */
export const tenantIdFieldKind: FieldKind = createSingleMetadataKind(
  'tenantId',
  'TENANT_ID',
  value => filter.tenantId(value),
);

/** Who owns the record, which is how "mine" is expressed. */
export const ownerIdFieldKind: FieldKind = createSingleMetadataKind(
  'ownerId',
  'OWNER_ID',
  value => filter.ownerId(value),
);

/** The workspace a record sits in. */
export const spaceIdFieldKind: FieldKind = createSingleMetadataKind(
  'spaceId',
  'SPACE_ID',
  value => filter.spaceId(value),
);

export const METADATA_FIELD_KINDS: readonly FieldKind[] = [
  documentIdFieldKind,
  aggregateIdFieldKind,
  tenantIdFieldKind,
  ownerIdFieldKind,
  spaceIdFieldKind,
];
