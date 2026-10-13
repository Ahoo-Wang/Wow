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

package me.ahoo.wow.api.query

/**
 * The result of a [CountQuery].
 *
 * @property count The number of matches; when [exact] is `false`, the limit the count stopped at.
 * @property exact Whether [count] is every match; `false` reads as "at least [count]" (shown as `1000+`).
 */
data class CountResult(
    val count: Long,
    val exact: Boolean,
) {
    init {
        require(count >= 0) {
            "count must not be negative."
        }
    }
}
