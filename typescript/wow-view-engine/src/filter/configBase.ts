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
import { isFilterGroup, isSimpleTree } from './tree.js';
import { validateFilter } from './validate.js';
import { isPlainObject } from './values.js';

/**
 * The part every view kind shares. It lives beside the filter because two of
 * its three fields describe the filter, and because `filter` is the one layer
 * record, analysis and dashboard all depend on.
 *
 * A config arrives from a store, so each of the three is first asked whether
 * it is there at all and of the right shape; a missing or unreadable one is
 * an Issue at its path, never a `TypeError` from the check that reads it.
 */
export function validateViewConfigBase(
  fields: readonly FieldDefinition[],
  config: ViewConfigBase,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): Issue[] {
  if (!isPlainObject(config)) return [issue('config.invalid', [])];

  // The root must be a group before anything walks it; `validateFilter`
  // would report a malformed root too, but at `[]`, which is the config.
  const hasTree = isFilterGroup(config.filter);
  const issues = hasTree
    ? validateFilter(fields, config.filter, kinds, { limits })
    : [issue('config.filter.invalid', ['filter'])];

  if (config.filterMode !== 'simple' && config.filterMode !== 'advanced') {
    issues.push(issue('config.filterMode.unknown', ['filterMode']));
  } else if (
    hasTree &&
    config.filterMode === 'simple' &&
    !isSimpleTree(config.filter)
  ) {
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
  // Absent, `null` or not an object: there is no setting to read.
  if (!isPlainObject(config.refresh))
    return [issue('config.refresh.missing', ['refresh'])];
  const interval = config.refresh.interval;
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
