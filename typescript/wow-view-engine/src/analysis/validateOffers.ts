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
  ANALYSIS_DATE_PARTS,
  TEMPORAL_FIELD_KIND_IDS,
  type AggregationFieldCapability,
  type AnalysisCapability,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/**
 * What each field of an analysis capability offers, held to the fields it
 * names: a root entry must name a declared field — an aggregation over a
 * name the definition never declared resolves to nothing — and a field
 * offering `DATE_PART` must hold time, which a calendar part is read off
 * (Wow's `AGGREGATE_TEMPORAL`), with only the parts Wow has. An element's
 * unknown fields are the chain's finding (`validateElementChain`); its date
 * parts are checked here too.
 */
export function offerIssues(
  definition: DataViewDefinition,
  capability: AnalysisCapability,
): Issue[] {
  const issues = capability.fields.flatMap((entry, index) => {
    const at: IssuePath = ['analysis', 'fields', index];
    const field = definition.fields.find(held => held.name === entry.field);
    return field
      ? fieldIssues(entry, field, at)
      : [
          issue('definition.analysis.field-unknown', at, {
            field: entry.field,
          }),
        ];
  });
  // Each level of the expansion chain declares its own fields; a level that
  // names no declared array is the chain's finding, not this one's.
  let held: readonly FieldDefinition[] = definition.fields;
  (capability.elements ?? []).forEach((element, level) => {
    held = held.find(field => field.name === element.path)?.elements ?? [];
    element.aggregations.forEach((entry, index) =>
      issues.push(
        ...fieldIssues(
          entry,
          held.find(field => field.name === entry.field),
          ['analysis', 'elements', level, 'aggregations', index],
        ),
      ),
    );
  });
  return issues;
}

function fieldIssues(
  entry: AggregationFieldCapability,
  field: FieldDefinition | undefined,
  at: IssuePath,
): Issue[] {
  const issues: Issue[] = [];
  if (
    entry.groups.includes('DATE_PART' as never) &&
    field &&
    !(TEMPORAL_FIELD_KIND_IDS as readonly string[]).includes(field.kind)
  )
    issues.push(
      issue('definition.analysis.date-part-not-temporal', [...at, 'groups'], {
        field: entry.field,
      }),
    );
  (entry.dateParts ?? []).forEach((part, index) => {
    if (!(ANALYSIS_DATE_PARTS as readonly string[]).includes(part))
      issues.push(
        issue(
          'definition.analysis.date-part-unknown',
          [...at, 'dateParts', index],
          { field: entry.field, value: String(part) },
        ),
      );
  });
  return issues;
}
