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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type {
  FieldDefinition,
  Issue,
  RecordCapability,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/** Wow's paging modes by the name a definition gives them. */
const PAGING = { paged: 'PAGED', cursor: 'CURSOR' } as const;

/** The constraint that refuses a page of every record (Q3). */
const COUNT_REQUIRES_FILTER = 'COUNT_REQUIRES_FILTER';

/** The constraint that names the field a cursor's sort is ended on. */
const CURSOR_UNIQUE_SORT = 'CURSOR_UNIQUE_SORT';

/**
 * The record capability as the descriptor admits it (capabilities.md 4.3).
 *
 * What the deployment contradicts is an error — the paging the definition
 * reads by, the field a cursor is ended on, a row key that cannot be
 * sorted: every page would be refused, or ordered otherwise than the engine
 * believes. What it only bounds is written in: the most fields a sort may
 * name, the row key every query ends on taking one of them.
 */
export function narrowRecord(
  capability: RecordCapability,
  fields: readonly FieldDefinition[],
  descriptor: QueryModelDescriptor,
  findings: Issue[],
): RecordCapability {
  // Wow's enum, whose values are the names above.
  const paging: readonly string[] = descriptor.record.paging;
  if (!paging.includes(PAGING[capability.paging]))
    findings.push(
      issue('capability.record.paging', ['record', 'paging'], {
        paging: capability.paging,
      }),
    );

  if (capability.paging === 'cursor') {
    const appended = descriptor.constraints.find(
      constraint => constraint.type === CURSOR_UNIQUE_SORT,
    )?.appended;
    if (appended !== undefined && appended !== capability.rowKey)
      findings.push(
        issue('capability.record.cursor-appended', ['record', 'rowKey'], {
          field: capability.rowKey,
          appended,
        }),
      );
  }

  // Declared sortable, the fields narrowing turned it off: the path does not
  // sort. Admission has already refused a row key declared otherwise.
  const rowKey = fields.find(field => field.name === capability.rowKey);
  if (rowKey?.sortable === false)
    findings.push(
      issue('capability.record.row-key-unsortable', ['record', 'rowKey'], {
        field: capability.rowKey,
      }),
    );

  const countsNarrowed =
    capability.paging === 'paged' &&
    descriptor.constraints.some(
      constraint => constraint.type === COUNT_REQUIRES_FILTER,
    );
  const bound = descriptor.limits.maxSortFields - 1;
  const declared = capability.maxSortFields;
  const bounded =
    declared !== undefined && declared <= bound
      ? capability
      : { ...capability, maxSortFields: Math.max(0, bound) };
  return countsNarrowed ? { ...bounded, requiresFilter: true } : bounded;
}
