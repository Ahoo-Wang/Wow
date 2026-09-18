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
  filter,
  type FilterExpression,
  type SearchMode,
} from '@ahoo-wang/fetcher-wow';
import { DEFAULT_SEARCH_MODE } from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';

/**
 * Full-text search: the box at the top of a list page.
 *
 * Wow's `SEARCH` names no field, so this kind's `name` is a handle for the
 * editor and the label rather than a path, the way the metadata kinds' names
 * are. Which fields the query looks in, and whether it reads as words or as a
 * phrase, belong to the definition — matching behaviour is a property of the
 * field, and the value is only what someone typed.
 *
 * It offers no presence operators, because those carry a field name and this
 * one has none to give them.
 */
export const searchFieldKind: FieldKind = {
  id: 'search',
  operators: ['SEARCH'],
  defaultOperator: 'SEARCH',
  scalar: false,

  emptyValue() {
    return '';
  },

  /**
   * Whitespace is nothing typed. Wow refuses a blank query by throwing, and
   * spaces would otherwise pass the general emptiness rule and reach it.
   */
  isBlank({ value }) {
    // Only a string can be blank. Something that is not text at all is wrong
    // rather than unfinished, and forgiving it here would let it through to
    // `filter.search`, which answers a non-string by throwing.
    return typeof value === 'string' && value.trim().length === 0;
  },

  validate({ value, path }) {
    // A blank query never arrives here: it is unfinished, not wrong.
    return typeof value === 'string'
      ? []
      : [issue('filter.value.expected-text', path)];
  },

  compile({ leaf, field }): FilterExpression {
    return filter.search(readValue<string>(leaf.value).trim(), {
      ...(field.searchFields ? { fields: field.searchFields } : {}),
      mode: (field.searchMode ?? DEFAULT_SEARCH_MODE) as SearchMode,
    });
  },

  editor() {
    return { input: 'text' };
  },

  describe({ leaf, field }) {
    return `${field.label} ${readValue<string>(leaf.value)}`;
  },
};
