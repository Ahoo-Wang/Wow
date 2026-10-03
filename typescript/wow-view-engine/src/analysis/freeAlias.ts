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
 * A name no group or metric is using: `base`'s stem plus the first free
 * number. Numbering by the row count collided as soon as a row was removed,
 * so the first free number it is, however rows were added and removed.
 * Aliases are single-segment in Wow, so a field path becomes one token, and
 * a copy of `amount_2` is `amount_<next>` rather than `amount_2_1`.
 */
export function freeAlias(base: string, taken: readonly string[]): string {
  const stem = base.split('.').join('_').replace(/_\d+$/, '');
  const used = new Set(taken);
  for (let index = 1; ; index += 1) {
    const alias = `${stem}_${index}`;
    if (!used.has(alias)) return alias;
  }
}
