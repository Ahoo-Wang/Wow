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

/**
 * Interface for pagination information.
 *
 * Page number, starting from 1
 * Page size
 */
export interface Pagination {
  index: number;
  size: number;
}

/**
 * Default pagination configuration.
 * Page index starts at 1, page size is 10.
 */
export const DEFAULT_PAGINATION: Pagination = {
  index: 1,
  size: 10,
};

/**
 * Creates a Pagination object with the provided parameters.
 *
 * This function is a factory for creating Pagination objects, which represent
 * pagination information including page index and page size. It provides default
 * values for optional properties while allowing customization of index and size.
 *
 * @param options - The pagination options. Optional.
 * @param options.index - The page index, starting from 1. Defaults to DEFAULT_PAGINATION.index.
 * @param options.size - The page size. Defaults to DEFAULT_PAGINATION.size.
 * @returns A Pagination object with the specified parameters
 */
export function pagination({
                             index = DEFAULT_PAGINATION.index,
                             size = DEFAULT_PAGINATION.size,
                           }: Partial<Pagination> = DEFAULT_PAGINATION): Pagination {
  return {
    index,
    size,
  };
}
