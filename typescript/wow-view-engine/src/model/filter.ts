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

import type { FilterOperator } from '@ahoo-wang/fetcher-wow';
import type { JsonValue } from './json.js';

/**
 * Operator name as stored in a configuration: the same values as Wow's
 * `FilterOperator`, as string literals, so a config stays plain JSON.
 */
export type FilterOperatorName = `${FilterOperator}`;

/**
 * The scope of a view, shared by all three kinds. Depth and node count are
 * bounded by `RuntimeLimits`, because a tree arrives from a store.
 */
export type FilterTree = FilterGroup;

export interface FilterGroup {
  op: 'and' | 'or';
  children: FilterNode[];
}

export interface FilterLeaf {
  /** Field name as declared by the definition, or a dashboard global field. */
  field: string;
  operator: FilterOperatorName;
  value: FilterValue;
}

export type FilterNode = FilterGroup | FilterLeaf;

/**
 * The semantic value of a leaf. Its shape belongs to the field's `FieldKind`
 * (see the `filter` layer), never to a compiled query: "last 7 days" is stored
 * as a relative intent and evaluated at execution time.
 */
export type FilterValue = JsonValue;

/** `simple` mode only admits a single AND group whose children are all leaves. */
export type FilterMode = 'simple' | 'advanced';
