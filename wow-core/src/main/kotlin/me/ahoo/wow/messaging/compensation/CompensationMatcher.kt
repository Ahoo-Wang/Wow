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

package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message

const val COMPENSATION_PREFIX = "compensate."
const val COMPENSATE_PROCESSOR_SEPARATOR = ","
const val COMPENSATION_EXCLUDE_HEADER = "${COMPENSATION_PREFIX}exclude"
const val COMPENSATION_INCLUDE_HEADER = "${COMPENSATION_PREFIX}include"
const val COMPENSATION_ALL_VALUE = "*"

data class CompensationConfig(
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet()
) {
    companion object {
        val EMPTY = CompensationConfig()
    }
}

object CompensationMatcher {
    private fun String.asStringSet(): Set<String> {
        return split(COMPENSATE_PROCESSOR_SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    fun <M : Message<out M, *>> M.withCompensation(config: CompensationConfig = CompensationConfig.EMPTY): M {
        return this.withCompensation(include = config.include, exclude = config.exclude)
    }

    fun <M : Message<out M, *>> M.withCompensation(
        include: Set<String> = emptySet(),
        exclude: Set<String> = emptySet()
    ): M {
        header.withCompensation(include, exclude)
        return this
    }

    fun Header.withCompensation(
        include: Set<String> = emptySet(),
        exclude: Set<String> = emptySet()
    ): Header {
        val excludeString = exclude.joinToString(COMPENSATE_PROCESSOR_SEPARATOR) {
            it.trim()
        }
        val includeString = include.joinToString(COMPENSATE_PROCESSOR_SEPARATOR) {
            it.trim()
        }
        return with(COMPENSATION_EXCLUDE_HEADER, excludeString)
            .with(COMPENSATION_INCLUDE_HEADER, includeString)
    }

    fun Message<*, *>.match(processor: String): Boolean {
        return header.match(processor)
    }

    fun Header.match(processor: String): Boolean {
        val excludeString = this[COMPENSATION_EXCLUDE_HEADER]
        val includeString = this[COMPENSATION_INCLUDE_HEADER]
        if (excludeString == null && includeString == null) {
            return true
        }
        val exclude = excludeString?.asStringSet() ?: emptySet()
        if (processor in exclude) {
            return false
        }
        val include = includeString?.asStringSet() ?: emptySet()
        if (include.contains(COMPENSATION_ALL_VALUE)) {
            return true
        }
        return processor in include
    }
}
