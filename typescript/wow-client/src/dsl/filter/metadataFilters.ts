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

import { constantName } from '../constantName.js';
import { DeletionState } from '../deletionState.js';
import { FilterOperator } from './operator.js';
import type {
  DeletionFilter,
  MatchFilter,
  MetadataValueFilter,
  MetadataValuesFilter,
} from './types.js';
import { requireNonEmpty, requiredString } from './validate.js';

function metadataValue(
  op: MetadataValueFilter['op'],
  value: string,
): MetadataValueFilter {
  return { op, value: requiredString(`${constantName(op)} value`, value) };
}

function metadataValues(
  op: MetadataValuesFilter['op'],
  values: readonly string[],
): MetadataValuesFilter {
  requireNonEmpty(`${constantName(op)} values`, values);
  values.forEach(value => requiredString(`${constantName(op)} value`, value));
  return { op, values: [...values] };
}

/** The filters on what Wow knows of every record: all or none, its ids, tenant, owner and space, and its deletion state. */
export const metadataFilters = {
  /**
   * Matches every record in the query scope.
   *
   * @returns `{ op: 'match-all' }`.
   * @example
   * ```typescript
   * filter.matchAll();
   * ```
   */
  matchAll(): MatchFilter {
    return { op: FilterOperator.MATCH_ALL };
  },
  /**
   * Matches no record.
   *
   * @returns `{ op: 'match-none' }`.
   * @example
   * ```typescript
   * filter.matchNone();
   * ```
   */
  matchNone(): MatchFilter {
    return { op: FilterOperator.MATCH_NONE };
  },
  /**
   * Matches the record whose record ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The record ID.
   * @returns `{ op: 'id', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.id('order-1');
   * ```
   */
  id(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.ID, value);
  },
  /**
   * Matches records whose record ID is one of `values`. Root-only: not
   * allowed inside `elementMatch`.
   *
   * @param values - Non-empty list of record IDs.
   * @returns `{ op: 'ids', values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a non-string.
   * @example
   * ```typescript
   * filter.ids(['order-1', 'order-2']);
   * ```
   */
  ids(values: readonly string[]): MetadataValuesFilter {
    return metadataValues(FilterOperator.IDS, values);
  },
  /**
   * Matches the record whose aggregate ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The aggregate ID.
   * @returns `{ op: 'aggregate-id', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.aggregateId('order-1');
   * ```
   */
  aggregateId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.AGGREGATE_ID, value);
  },
  /**
   * Matches records whose aggregate ID is one of `values`. Root-only: not
   * allowed inside `elementMatch`.
   *
   * @param values - Non-empty list of aggregate IDs.
   * @returns `{ op: 'aggregate-ids', values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a non-string.
   * @example
   * ```typescript
   * filter.aggregateIds(['order-1', 'order-2']);
   * ```
   */
  aggregateIds(values: readonly string[]): MetadataValuesFilter {
    return metadataValues(FilterOperator.AGGREGATE_IDS, values);
  },
  /**
   * Matches the record whose tenant ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The tenant ID.
   * @returns `{ op: 'tenant-id', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.tenantId('tenant-a');
   * ```
   */
  tenantId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.TENANT_ID, value);
  },
  /**
   * Matches the record whose owner ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The owner ID.
   * @returns `{ op: 'owner-id', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.ownerId('user-1');
   * ```
   */
  ownerId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.OWNER_ID, value);
  },
  /**
   * Matches the record whose space ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The space ID.
   * @returns `{ op: 'space-id', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.spaceId('space-1');
   * ```
   */
  spaceId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.SPACE_ID, value);
  },
  /**
   * Matches snapshots by deletion state. An explicit `DELETION` at the root or
   * in a root `AND` replaces the gateway's default `ACTIVE` scope. Root-only:
   * not allowed inside `elementMatch`.
   *
   * @param state - `ACTIVE`, `DELETED` or `ALL`.
   * @returns `{ op: 'deletion', state }`.
   * @throws TypeError If `state` is not a {@link DeletionState} member.
   * @example
   * ```typescript
   * filter.deletion(DeletionState.ALL);
   * ```
   */
  deletion(state: DeletionState): DeletionFilter {
    if (
      state !== DeletionState.ACTIVE &&
      state !== DeletionState.DELETED &&
      state !== DeletionState.ALL
    ) {
      throw new TypeError(`Deletion state is invalid: [${String(state)}].`);
    }
    return { op: FilterOperator.DELETION, state };
  },
};
