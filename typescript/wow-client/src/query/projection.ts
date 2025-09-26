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
export interface Projection<FIELDS extends string = string> {
  include?: FIELDS[];
  exclude?: FIELDS[];
}

/**
 * Default projection configuration.
 * Empty projection object includes all fields.
 */
export const DEFAULT_PROJECTION: Projection = {};

export function defaultProjection<
  FIELDS extends string = string,
>(): Projection<FIELDS> {
  return DEFAULT_PROJECTION as Projection<FIELDS>;
}

/**
 * Creates a Projection object with the provided parameters.
 *
 * This function is a factory for creating Projection objects, which represent
 * field projection specifications for queries. It allows specifying which fields
 * to include or exclude in query results.
 *
 * @param options - The projection options. Defaults to DEFAULT_PROJECTION.
 * @param options.include - Array of field names to include in the projection. Optional.
 * @param options.exclude - Array of field names to exclude from the projection. Optional.
 * @returns A Projection object with the specified parameters
 */
export function projection<FIELDS extends string = string>(
  { include, exclude }: Projection<FIELDS> = defaultProjection(),
): Projection<FIELDS> {
  return {
    include,
    exclude,
  };
}

/**
 * Interface for objects that support field projection.
 */
export interface ProjectionCapable<FIELDS extends string = string> {
  projection?: Projection<FIELDS>;
}
