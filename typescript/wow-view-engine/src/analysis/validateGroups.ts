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

import type { AnalysisViewConfig, Issue, IssuePath } from '../model/index.js';
import { issue } from '../filter/index.js';
import type { AnalysisScope } from './capability.js';

export function validateGroups(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
): Issue[] {
  return config.groups.flatMap((group, index) => {
    const path: IssuePath = ['groups', index];
    const capability = scope.aggregations.get(group.field);
    if (!capability)
      return [
        issue('analysis.field.unknown', [...path, 'field'], {
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

    const issues: Issue[] = [];
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
      }
    }
    return issues;
  });
}
