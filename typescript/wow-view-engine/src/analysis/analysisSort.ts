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

export function effectiveSortAliases(
  dimensions: readonly { readonly alias: string }[],
  sort: readonly { readonly alias: string }[],
) {
  return new Set([...dimensions, ...sort].map(item => item.alias));
}

/** Adding an explicit dimension sort consumes no new effective sort slot. */
export function canAddAnalysisSort(
  dimensions: readonly { readonly alias: string }[],
  sort: readonly { readonly alias: string }[],
  alias: string,
  maxSort: number,
): boolean {
  const effective = effectiveSortAliases(dimensions, sort);
  return effective.has(alias) || effective.size < maxSort;
}
