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
 * Orders names the same way on every machine.
 *
 * `localeCompare` without a locale follows the default locale of the process,
 * which ICU takes from `LANG`: under `tr_TR` a name starting with `I` sorts
 * after one starting with `i`, so the order of a barrel's exports or of a
 * client's methods would depend on where the generator ran. The collator is
 * fixed to `en-US`, the order CI has always produced, so no output changes.
 */
const NAME_COLLATOR = new Intl.Collator('en-US');

/** Compares two names in the fixed `en-US` order; a comparator for `sort`. */
export function compareNames(left: string, right: string): number {
  return NAME_COLLATOR.compare(left, right);
}
