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

internal enum class HotPathCheckpointLevel {
    OFF,
    LIGHT,
    HEAVY,
    ;

    companion object {
        fun parse(value: String): HotPathCheckpointLevel =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unsupported hot path checkpoint level[$value]. " +
                        "Supported values are: ${entries.joinToString { it.name }}."
                )

        fun parseLegacyBoolean(value: String): HotPathCheckpointLevel =
            if (value.isEnabled()) LIGHT else OFF

        private fun String.isEnabled(): Boolean =
            when (trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                else -> false
            }
    }
}

internal object HotPathCheckpoint {
    const val CHECKPOINT_LEVEL_PROPERTY = "wow.reactor.hotpath-checkpoint-level"
    const val CHECKPOINT_LEVEL_ENV = "WOW_REACTOR_HOTPATH_CHECKPOINT_LEVEL"
    const val DETAILED_CHECKPOINT_PROPERTY = "wow.reactor.detailed-hotpath-checkpoints"
    const val DETAILED_CHECKPOINT_ENV = "WOW_REACTOR_DETAILED_HOTPATH_CHECKPOINTS"

    val checkpointLevel: HotPathCheckpointLevel = checkpointLevel()

    fun checkpointLevel(
        properties: Map<String, String?> = mapOf(
            CHECKPOINT_LEVEL_PROPERTY to System.getProperty(CHECKPOINT_LEVEL_PROPERTY),
            DETAILED_CHECKPOINT_PROPERTY to System.getProperty(DETAILED_CHECKPOINT_PROPERTY),
        ),
        environment: Map<String, String?> = System.getenv(),
    ): HotPathCheckpointLevel =
        properties[CHECKPOINT_LEVEL_PROPERTY].toCheckpointLevel()
            ?: environment[CHECKPOINT_LEVEL_ENV].toCheckpointLevel()
            ?: properties[DETAILED_CHECKPOINT_PROPERTY].toLegacyCheckpointLevel()
            ?: environment[DETAILED_CHECKPOINT_ENV].toLegacyCheckpointLevel()
            ?: HotPathCheckpointLevel.OFF

    private fun String?.toCheckpointLevel(): HotPathCheckpointLevel? =
        trimOrNull()?.let { HotPathCheckpointLevel.parse(it) }

    private fun String?.toLegacyCheckpointLevel(): HotPathCheckpointLevel? =
        trimOrNull()?.let { HotPathCheckpointLevel.parseLegacyBoolean(it) }

    private fun String?.trimOrNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }
}

internal inline fun <T : Any> Mono<T>.checkpointIfEnabled(
    level: HotPathCheckpointLevel = HotPathCheckpoint.checkpointLevel,
    description: () -> String,
): Mono<T> =
    when (level) {
        HotPathCheckpointLevel.OFF -> this
        HotPathCheckpointLevel.LIGHT -> checkpoint(description())
        HotPathCheckpointLevel.HEAVY -> checkpoint(description(), true)
    }
