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
 * Joins a relative path onto a base with exactly one `/` between them, as
 * `@ahoo-wang/fetcher`'s `combineURLs` does, so the generator process need
 * not load fetcher for it: `('a/', '/b.ts')` → `a/b.ts`. An empty relative
 * path gives the base, and a URL with a scheme (`https://…`, `//host/…`)
 * replaces it.
 *
 * @param base - The path to join onto
 * @param relative - The path to join
 */
export function combinePaths(base: string, relative: string): string {
  if (/^([a-z][a-z\d+\-.]*:)?\/\//i.test(relative)) return relative;
  return relative
    ? `${base.replace(/\/+$/, '')}/${relative.replace(/^\/+/, '')}`
    : base;
}
