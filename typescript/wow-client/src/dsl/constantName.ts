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
 * The constant a wire value names, as Wow's messages name it: `not-in` is
 * `NOT_IN`, `date-histogram` is `DATE_HISTOGRAM`. A wire value is the
 * constant's name in lower-kebab, so this is its exact inverse; the DSL's
 * messages quote Wow's, which name the constant.
 *
 * @internal
 */
export function constantName(wire: string): string {
  return wire.toUpperCase().replace(/-/g, '_');
}
