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

import type { FilterGroupOperator } from '../../model/index.js';
import type { MessageKey } from '../messages.js';

/**
 * What a group's operator says about the conditions under it.
 *
 * One mapping, because the select in a group's header, the menu that nests a
 * new group and the summary above the result all name the same three things:
 * a group that read "Any of" in one place and "Any condition" in another
 * would read as two different settings.
 */
export const GROUP_OPERATOR_LABEL: Record<FilterGroupOperator, MessageKey> = {
  and: 'label.filter.all-conditions',
  or: 'label.filter.any-condition',
  nor: 'label.filter.no-condition',
};

/** The three, in the order a group's select offers them. */
export const GROUP_OPERATORS: readonly FilterGroupOperator[] = [
  'and',
  'or',
  'nor',
];
