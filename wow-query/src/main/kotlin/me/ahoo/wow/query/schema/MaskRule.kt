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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.annotation.KeepMaskStrategy
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import java.lang.reflect.InvocationTargetException

/**
 * The compiled form of one [Sensitive] declaration: its [level] and the [compiled] strategy that masks result values.
 * Two rules are equal when they declare the same level and mask, so the same declaration reached through different
 * members is one rule.
 */
class MaskRule(val level: SensitivityLevel, val mask: Mask = Mask()) {
    val compiled: MaskStrategy = compile(mask)

    override fun equals(other: Any?): Boolean = other is MaskRule && level == other.level && mask == other.mask

    override fun hashCode(): Int = 31 * level.hashCode() + mask.hashCode()

    override fun toString(): String = "MaskRule(level=$level, mask=$mask)"

    companion object {
        fun of(sensitive: Sensitive): MaskRule = MaskRule(sensitive.level, sensitive.mask)

        private fun compile(mask: Mask): MaskStrategy {
            if (mask.strategy == MaskStrategy::class) {
                return conflictOnFailure("Invalid built-in mask [$mask].") {
                    KeepMaskStrategy(mask.keepPrefix, mask.keepSuffix)
                }
            }
            if (mask.keepPrefix != 0 || mask.keepSuffix != 0) {
                throw QuerySchemaConflictException(
                    "Mask strategy [${mask.strategy.qualifiedName}] cannot be combined with keepPrefix or keepSuffix."
                )
            }
            return conflictOnFailure("Unable to instantiate MaskStrategy [${mask.strategy.qualifiedName}].") {
                mask.strategy.objectInstance ?: mask.strategy.java.getConstructor().newInstance()
            }
        }

        // A failing strategy keeps its original error as the cause of a schema conflict; errors propagate unchanged.
        @Suppress("TooGenericExceptionCaught")
        private inline fun <T> conflictOnFailure(message: String, operation: () -> T): T = try {
            operation()
        } catch (error: Throwable) {
            when (val failure = (error as? InvocationTargetException)?.targetException ?: error) {
                is QuerySchemaException, is Error -> throw failure
                is Exception -> throw QuerySchemaConflictException(message, failure)
                else -> throw failure
            }
        }
    }
}
