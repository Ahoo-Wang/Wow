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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.annotation.KeepMaskStrategy
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.SensitivityLevel

/**
 * How query results mask a sensitive value, as the Manifest's `x-wow-mask` states it (`@Sensitive.mask`): the
 * built-in mask that keeps [Keep.prefix] leading and [Keep.suffix] trailing code points, or a custom [Strategy] named
 * by its binary class name.
 */
sealed interface QueryMask {
    data class Keep(val prefix: Int = 0, val suffix: Int = 0) : QueryMask {
        init {
            require(prefix >= 0 && suffix >= 0) { "Mask keepPrefix and keepSuffix must not be negative." }
        }
    }

    /** A custom strategy, by the binary name of its class; a [MaskStrategyResolver] supplies its instance. */
    data class Strategy(val name: String) : QueryMask {
        init {
            require(name.isNotBlank()) { "Mask strategy name must not be blank." }
        }
    }

    companion object {
        /** The default mask: every code point masked. */
        @JvmField
        val FULL: QueryMask = Keep()
    }
}

/**
 * Supplies the instance of each custom mask strategy a Manifest names. The runtime does not load classes by name
 * (decision D37), so a host registers the strategies its domain declares; a strategy no resolver supplies fails the
 * schema build instead of leaving the value unmasked.
 */
fun interface MaskStrategyResolver {
    /** The strategy registered under [name], or `null` when none is. */
    fun resolve(name: String): MaskStrategy?

    companion object {
        /** Resolves no custom strategy: only the built-in mask is available. */
        @JvmField
        val NONE: MaskStrategyResolver = MaskStrategyResolver { null }

        /** Resolves the [strategies] by name. */
        @JvmStatic
        fun of(strategies: Map<String, MaskStrategy>): MaskStrategyResolver {
            val registered = strategies.toMap()
            return MaskStrategyResolver { registered[it] }
        }
    }
}

/**
 * The sensitivity of one value: its [level] and how responses [mask] it, with the [compiled] strategy. Two rules are
 * equal when their level and mask are; the compiled instance is not part of the identity.
 */
class MaskRule private constructor(
    val level: SensitivityLevel,
    val mask: QueryMask,
    val compiled: MaskStrategy,
) {
    /** A rule masked by the built-in mask, or by a custom strategy [resolver] supplies. */
    @JvmOverloads
    constructor(
        level: SensitivityLevel,
        mask: QueryMask = QueryMask.FULL,
        resolver: MaskStrategyResolver = MaskStrategyResolver.NONE,
    ) : this(level, mask, compile(mask, resolver))

    override fun equals(other: Any?): Boolean = other is MaskRule && level == other.level && mask == other.mask

    override fun hashCode(): Int = 31 * level.hashCode() + mask.hashCode()

    override fun toString(): String = "MaskRule(level=$level, mask=$mask)"

    private companion object {
        fun compile(mask: QueryMask, resolver: MaskStrategyResolver): MaskStrategy = when (mask) {
            is QueryMask.Keep -> KeepMaskStrategy(mask.prefix, mask.suffix)
            is QueryMask.Strategy -> resolver.resolve(mask.name)
                ?: throw QuerySchemaConflictException("Mask strategy [${mask.name}] is not registered.")
        }
    }
}

/**
 * Whether a filter or sort may compare a protected value. [displayComparable] lets queries compare
 * [SensitivityLevel.DISPLAY] values (the 9.x default); [SensitivityLevel.CONFIDENTIAL] values never compare.
 */
@InternalWowApi
data class QuerySensitivityPolicy(val displayComparable: Boolean = true) {
    /** Whether a filter or sort may compare a value protected at [level]; unprotected values always compare. */
    fun comparable(level: SensitivityLevel?): Boolean = when (level) {
        null -> true
        SensitivityLevel.DISPLAY -> displayComparable
        SensitivityLevel.CONFIDENTIAL -> false
    }

    companion object {
        @JvmField
        val DEFAULT = QuerySensitivityPolicy()
    }
}
