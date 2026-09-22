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
  isFieldlessKind,
  type DataViewDefinition,
  type FieldDefinition,
} from '../model/index.js';

/** One titled part of a record's detail: the fields a group gathers. */
export interface DetailSection {
  /** The field group's id, or `null` for the fields no group gathers. */
  id: string | null;
  /** The group's label; `null` where the fields gathered no group. */
  label: string | null;
  fields: FieldDefinition[];
}

/**
 * How a record's detail is laid out: every field the definition declares,
 * under the groups it declares, in their order — the order the field
 * pickers list them in, so the detail reads like the author arranged the
 * record — and the fields no group gathers after them, in declaration
 * order. A field is listed once, under the first group that names it.
 *
 * Only fields that are a **value on the record** are listed: a fieldless
 * kind — full-text search, the deletion switch, the metadata handles — is a
 * way to filter, and has no value to show.
 */
export function detailSections(
  definition: Pick<DataViewDefinition, 'fields' | 'fieldGroups'>,
): DetailSection[] {
  const valued = definition.fields.filter(
    field => !isFieldlessKind(field.kind),
  );
  const byName = new Map(valued.map(field => [field.name, field]));
  const placed = new Set<string>();
  const sections: DetailSection[] = [];
  for (const group of definition.fieldGroups ?? []) {
    const fields = group.fields.flatMap(name => {
      const field = byName.get(name);
      if (!field || placed.has(name)) return [];
      placed.add(name);
      return [field];
    });
    if (fields.length > 0)
      sections.push({ id: group.id, label: group.label, fields });
  }
  const rest = valued.filter(field => !placed.has(field.name));
  if (rest.length > 0) sections.push({ id: null, label: null, fields: rest });
  return sections;
}
