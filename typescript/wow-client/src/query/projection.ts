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
 * Interface for field projection.
 */
export interface Projection {
  include?: string[];
  exclude?: string[];
}

/**
 * Default projection configuration.
 * Empty projection object includes all fields.
 */
export const DEFAULT_PROJECTION: Projection = {};

/**
 * Creates a projection object with optional include and exclude field arrays.
 * @param include - Array of field names to include in the projection. If provided, only these fields will be included.
 * @param exclude - Array of field names to exclude from the projection. If provided, these fields will be excluded.
 * @returns A Projection object with the specified include and exclude arrays
 */
export function projection(include?: string[], exclude?: string[]): Projection {
  return {
    include,
    exclude,
  };
}

/**
 * Interface for objects that support field projection.
 */
export interface ProjectionCapable {
  projection?: Projection;
}
