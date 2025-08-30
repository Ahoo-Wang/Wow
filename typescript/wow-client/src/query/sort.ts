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

export enum SortDirection {
  ASC = 'ASC',
  DESC = 'DESC',
}

/**
 * Interface for sort criteria.
 */
export interface Sort {
  field: string;
  direction: SortDirection;
}

/**
 * Creates a sort object with ascending direction for the specified field.
 * @param field - The field to sort by
 * @returns A Sort object with the specified field and ascending direction
 */
export function asc(field: string): Sort {
  return {
    field,
    direction: SortDirection.ASC,
  };
}

/**
 * Creates a sort object with descending direction for the specified field.
 * @param field - The field to sort by
 * @returns A Sort object with the specified field and descending direction
 */
export function desc(field: string): Sort {
  return {
    field,
    direction: SortDirection.DESC,
  };
}

/**
 * Interface for objects that support sorting.
 */
export interface SortCapable {
  sort?: Sort[];
}
