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

import { queryField } from '../field.js';
import { FilterOperator, SearchMode } from './operator.js';
import type {
  SearchFilter,
  SearchFilterOptions,
  ElementSearchFilter,
  QueryField,
} from './types.js';

/**
 * The overloads of {@link searchFilters.search | `filter.search`}: naming at least one field, as an
 * array literal, types the result as an {@link ElementSearchFilter}, which
 * `ELEMENT_MATCH` takes; otherwise it is a {@link SearchFilter}, root-only
 * when its `fields` are empty.
 */
export interface SearchBuilder {
  <FIELDS extends string>(
    query: string,
    options: SearchFilterOptions<FIELDS> & {
      fields: readonly [QueryField<FIELDS>, ...QueryField<FIELDS>[]];
    },
  ): ElementSearchFilter<FIELDS>;
  <FIELDS extends string>(
    query: string,
    options?: SearchFilterOptions<FIELDS>,
  ): SearchFilter<FIELDS>;
}

function search<FIELDS extends string>(
  query: string,
  options?: SearchFilterOptions<FIELDS>,
): SearchFilter<FIELDS> {
  if (typeof query !== 'string' || !query.trim()) {
    throw new TypeError('SEARCH query cannot be blank.');
  }
  if (
    options !== undefined &&
    (options === null || typeof options !== 'object' || Array.isArray(options))
  ) {
    throw new TypeError('SEARCH options must be a non-null object.');
  }
  const { fields = [], mode = SearchMode.TERMS } = options ?? {};
  if (!Object.values(SearchMode).includes(mode)) {
    throw new TypeError(`SEARCH mode is invalid: [${String(mode)}].`);
  }
  return {
    op: FilterOperator.SEARCH,
    query,
    mode,
    fields: fields.map(queryField),
  };
}

/** The full-text search filter. */
export const searchFilters = {
  /**
   * Matches records by full-text search. Tokenization and matching depend on
   * the backend's analyzer. Inside `elementMatch` it must name its fields,
   * relative to the element; with fields given as a non-empty array literal
   * it returns an {@link ElementSearchFilter}, which `elementMatch` takes.
   *
   * @param query - Search text.
   * @param options - Optional `fields` (defaults to `[]`, the backend's default
   *   search fields) and `mode` (defaults to `SearchMode.TERMS`).
   * @returns `{ op: 'search', query, mode, fields }`.
   * @throws TypeError If `query` is not a string or is blank, `options` is
   *   given but is `null`, an array or not an object, `mode` is not a
   *   {@link SearchMode} member, or any of `fields` is not a valid query field
   *   path.
   * @example
   * ```typescript
   * filter.search('event sourcing', {
   *   fields: ['state.description'],
   *   mode: SearchMode.PHRASE,
   * });
   * ```
   */
  search: search as SearchBuilder,
};
