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
 * Wow's `QueryField` pattern, shared by every place that names a path.
 *
 * Internal on purpose: no entry re-exports this module, so the
 * validator stays an implementation detail rather than becoming public API
 * that has to be documented and kept. A filter, a sort and a projection all
 * hold paths, and Kotlin wraps all three in `QueryField`, so they agree here
 * rather than each inventing its own idea of a path.
 */
const QUERY_FIELD_PATTERN =
  /^@?[A-Za-z_][A-Za-z0-9_-]*(\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*$/;

/** Admits a field path, the way `QueryField`'s `init` does. */
export function queryField<FIELDS extends string>(field: FIELDS): FIELDS {
  if (typeof field !== 'string' || !QUERY_FIELD_PATTERN.test(field)) {
    throw new TypeError(`Query field is invalid: [${String(field)}].`);
  }
  return field;
}
