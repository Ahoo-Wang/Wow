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

import { queryField } from './field.js';

/**
 * Interface for field projection.
 *
 * @typeParam FIELDS - The field paths of the model.
 * @typeParam INCLUDE - The paths `include` names, inferred from a literal
 *   list: a query client narrows its records to them ({@link Projected}).
 */
export interface Projection<
  FIELDS extends string = string,
  INCLUDE extends FIELDS = FIELDS,
> {
  include?: INCLUDE[];
  exclude?: FIELDS[];
}

/** The first segment of a dotted path. */
type Head<P extends string> = P extends `${infer H}.${string}` ? H : P;

/** What follows `K.` in the dotted paths of `P`. */
type Tail<P extends string, K extends string> = P extends `${K}.${infer R}`
  ? R
  : never;

/**
 * `T` with only the dotted paths `P` (and the objects that lead to them): an
 * included path keeps its descendants, an array its items' paths, and a
 * nullable or optional value stays so.
 */
export type PickPaths<T, P extends string> = T extends readonly (infer E)[]
  ? PickPaths<E, P>[]
  : T extends object
    ? {
        [K in keyof T as K extends Head<P> ? K : never]: K extends P
          ? T[K]
          : PickPaths<T[K], Tail<P, K & string>>;
      }
    : T;

/**
 * The records of type `R` a query with the projection's `include` paths
 * `INCLUDE` returns (review R4-Q, Q-14): `R` itself when the query includes no
 * literal paths (every field, or an exclusion alone), else `R` narrowed to
 * those paths ({@link PickPaths}).
 */
export type Projected<
  R,
  INCLUDE extends string,
  FIELDS extends string = string,
> = string extends INCLUDE
  ? R
  : [FIELDS] extends [INCLUDE]
    ? R
    : PickPaths<R, INCLUDE>;

/**
 * Creates a Projection object with the provided parameters.
 *
 * This function is a factory for creating Projection objects, which represent
 * field projection specifications for queries. It allows specifying which fields
 * to include or exclude in query results.
 *
 * @param options - The projection options. Defaults to none, which includes
 *   every field.
 * @param options.include - Array of field names to include in the projection. Optional.
 * @param options.exclude - Array of field names to exclude from the projection. Optional.
 * @typeParam INCLUDE - The included paths, inferred from a literal list, so a
 *   query client narrows its records to them ({@link Projected}).
 * @typeParam EXCLUDE - The excluded paths; the included ones when absent.
 * @returns A Projection object with the specified parameters
 */
export function projection<
  const INCLUDE extends string = string,
  const EXCLUDE extends string = INCLUDE,
>({
  include,
  exclude,
}: {
  include?: INCLUDE[];
  exclude?: EXCLUDE[];
} = {}): Projection<INCLUDE | EXCLUDE, INCLUDE> {
  // Kotlin holds these as `QueryField`, which refuses a path its pattern does
  // not match. A projection entry is a path like any other. Both keys stay
  // present whether or not they were given: the shape this factory returns is
  // its own contract, and validating a path is no reason to change it.
  return {
    include: include?.map(queryField),
    exclude: exclude?.map(queryField),
  };
}

/**
 * Interface for objects that support field projection.
 */
export interface ProjectionCapable<
  FIELDS extends string = string,
  INCLUDE extends FIELDS = FIELDS,
> {
  projection?: Projection<FIELDS, INCLUDE>;
}
