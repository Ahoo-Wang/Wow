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

package me.ahoo.wow.reactor

import reactor.core.publisher.Mono

internal object HotPathCheckpoint {
    const val DETAILED_CHECKPOINT_PROPERTY = "wow.reactor.detailed-hotpath-checkpoints"
    const val DETAILED_CHECKPOINT_ENV = "WOW_REACTOR_DETAILED_HOTPATH_CHECKPOINTS"

    val detailedCheckpointEnabled: Boolean = detailedCheckpointEnabled()

    fun detailedCheckpointEnabled(
        properties: Map<String, String?> = mapOf(
            DETAILED_CHECKPOINT_PROPERTY to System.getProperty(DETAILED_CHECKPOINT_PROPERTY),
        ),
        environment: Map<String, String?> = System.getenv(),
    ): Boolean {
        val propertyValue = properties[DETAILED_CHECKPOINT_PROPERTY]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (propertyValue != null) {
            return propertyValue.isEnabled()
        }
        return environment[DETAILED_CHECKPOINT_ENV]
            ?.trim()
            ?.isEnabled()
            ?: false
    }

    private fun String.isEnabled(): Boolean =
        when (lowercase()) {
            "true", "1", "yes", "on" -> true
            else -> false
        }
}

internal inline fun <T : Any> Mono<T>.checkpointIfEnabled(
    detailedEnabled: Boolean = HotPathCheckpoint.detailedCheckpointEnabled,
    description: () -> String,
): Mono<T> =
    if (detailedEnabled) {
        checkpoint(description())
    } else {
        this
    }
