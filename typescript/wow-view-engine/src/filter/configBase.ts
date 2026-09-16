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
  DEFAULT_RUNTIME_LIMITS,
  type FieldDefinition,
  type Issue,
  type RuntimeLimits,
  type ViewConfigBase,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from './fieldKind.js';
import { isSimpleTree } from './tree.js';
import { validateFilter } from './validate.js';

/**
 * The part every view kind shares. It lives beside the filter because two of
 * its three fields describe the filter, and because `filter` is the one layer
 * record, analysis and dashboard all depend on.
 */
export function validateViewConfigBase(
  fields: readonly FieldDefinition[],
  config: ViewConfigBase,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): Issue[] {
  const issues = validateFilter(fields, config.filter, kinds, { limits });

  if (config.filterMode !== 'simple' && config.filterMode !== 'advanced') {
    issues.push(issue('config.filterMode.unknown', ['filterMode']));
  } else if (config.filterMode === 'simple' && !isSimpleTree(config.filter)) {
    // The tree still runs; only the editor cannot show it, so the view opens
    // in advanced mode instead of losing the condition.
    issues.push(
      issue(
        'config.filterMode.not-simple',
        ['filterMode'],
        undefined,
        'warning',
      ),
    );
  }

  issues.push(...validateRefresh(config, limits));
  return issues;
}

function validateRefresh(
  config: ViewConfigBase,
  limits: RuntimeLimits,
): Issue[] {
  const interval = config.refresh?.interval;
  if (config.refresh === undefined)
    return [issue('config.refresh.missing', ['refresh'])];
  if (interval === null) return [];

  const path = ['refresh', 'interval'];
  if (!Number.isInteger(interval))
    return [issue('config.refresh.not-an-integer', path)];
  // An interval outside these bounds either polls tightly or overflows the
  // millisecond timer, so both ends are rejected rather than clamped.
  if (interval < limits.minRefreshInterval)
    return [
      issue('config.refresh.too-short', path, {
        min: limits.minRefreshInterval,
      }),
    ];
  if (interval > limits.maxRefreshInterval)
    return [
      issue('config.refresh.too-long', path, {
        max: limits.maxRefreshInterval,
      }),
    ];
  return [];
}
