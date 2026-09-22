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
  isSingleStringField,
  type AnalysisViewConfig,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { unknownOrOutside, type AnalysisScope } from './capability.js';
import { displayNameIssues } from './validateAliases.js';

export function validateGroups(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
): Issue[] {
  return config.groups.flatMap((group, index) => {
    const path: IssuePath = ['groups', index];
    const capability = scope.aggregations.get(group.field);
    if (!capability)
      return [
        // With `elements`, a dimension buckets the entries of the innermost
        // element and Wow reaches no further out; a root field named there is
        // not a typo, so it does not read as one.
        issue(unknownOrOutside(scope, group.field), [...path, 'field'], {
          field: group.field,
        }),
      ];
    if (!capability.groups.includes(group.type as never))
      return [
        issue('analysis.group.unsupported', [...path, 'type'], {
          field: group.field,
          type: group.type,
        }),
      ];

    const issues: Issue[] = displayNameIssues(group, path);
    if (group.type === 'HISTOGRAM') {
      if (!Number.isFinite(group.interval) || group.interval <= 0)
        issues.push(
          issue('analysis.group.interval-not-positive', [...path, 'interval']),
        );
    }
    if (group.type === 'DATE_HISTOGRAM') {
      if (!capability.dateUnits?.includes(group.unit as never))
        issues.push(
          issue('analysis.group.unit-unsupported', [...path, 'unit'], {
            unit: group.unit,
          }),
        );
      // A DATE_HISTOGRAM fills in every bucket its range implies rather than
      // only the ones that have rows, and a second dimension would multiply
      // that filling out across each of its own keys. Wow refuses it.
      if (group.dense === true && config.groups.length !== 1)
        issues.push(
          issue('analysis.group.dense-not-alone', [...path, 'dense']),
        );
      // Only the blank check, which is Wow's own. This zone is passed through
      // to the server and never resolved here, so the browser's zone table has
      // no standing over it: a name the backend knows, or a fixed offset, must
      // not be refused because this client's ICU is trimmed or out of date. A
      // filter value is the opposite case — it is resolved here against dayjs,
      // so an unknown zone there is an error.
      if (group.timeZone !== undefined) {
        if (typeof group.timeZone !== 'string')
          issues.push(
            issue('analysis.config.malformed', [...path, 'timeZone']),
          );
        else if (group.timeZone.trim() === '')
          issues.push(
            issue('analysis.group.blank-time-zone', [...path, 'timeZone']),
          );
      }
    }
    if (group.type === 'TERMS') {
      if (group.missingKey !== undefined) {
        if (typeof group.missingKey !== 'string')
          issues.push(
            issue('analysis.config.malformed', [...path, 'missingKey']),
          );
        else if (group.missingKey.trim() === '')
          issues.push(
            issue('analysis.group.blank-missing-key', [...path, 'missingKey']),
          );
        // Wow allows a sentinel bucket on single-valued string fields only —
        // nullable ones being the case it exists for — and refuses
        // multi-valued, numeric and boolean fields at schema validation. The
        // kind answers what shape its values have, and the field's own
        // candidates can still veto it: a closed set of numeric codes is a
        // numeric field whatever its kind is called.
        else if (!missingKeyAllowed(scope, group.field, kinds))
          issues.push(
            issue(
              'analysis.group.missing-key-unsupported',
              [...path, 'missingKey'],
              { field: group.field },
            ),
          );
      }
    }
    return issues;
  });
}

/**
 * Whether this dimension's field can carry a sentinel bucket key.
 *
 * A field the definition no longer declares says nothing either way: the
 * aggregation capability admitted the name, and inventing a second finding
 * over a missing declaration would bury the first one.
 */
function missingKeyAllowed(
  scope: AnalysisScope,
  name: string,
  kinds: FieldKindRegistry,
): boolean {
  const field = scope.fields.get(name);
  if (!field) return true;
  return isSingleStringField(field, kinds.get(field.kind));
}
