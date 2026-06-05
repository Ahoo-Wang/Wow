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

package me.ahoo.wow.tck.container

import java.util.Locale
import java.util.UUID

object ContainerTestIds {
    private val PREFIX_PATTERN = Regex("[a-z][a-z0-9_]{0,29}")
    private const val PREFIX_REQUIREMENT =
        "prefix must normalize to 1-30 lowercase letters, digits, or underscores and start with a letter."

    fun nextName(prefix: String): String {
        require(prefix.isNotBlank()) {
            "prefix must not be blank."
        }
        val normalizedPrefix = prefix
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace('.', '_')
        require(PREFIX_PATTERN.matches(normalizedPrefix)) {
            PREFIX_REQUIREMENT
        }
        val suffix = UUID.randomUUID().toString().replace("-", "")
        return "${normalizedPrefix}_$suffix"
    }
}
