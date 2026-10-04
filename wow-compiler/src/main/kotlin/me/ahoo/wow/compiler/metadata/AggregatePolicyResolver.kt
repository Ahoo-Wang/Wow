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

package me.ahoo.wow.compiler.metadata

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.AggregateOwner
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.annotation.Spaced
import me.ahoo.wow.compiler.metadata.BoundedContextResolver.getAnnotation

/**
 * An aggregate's effective spaced flag and owner policy, as the runtime resolves them.
 */
internal data class AggregatePolicy(
    val spaced: Boolean,
    val owner: OwnerPolicy
) {
    val isDefault: Boolean
        get() = !spaced && owner == OwnerPolicy.NEVER

    companion object {
        const val SPACED = "spaced"
        const val OWNER = "owner"
    }
}

/**
 * The compile-time twin of core's `AggregatePolicyResolver`: `@Spaced` / `@AggregateOwner` first, then the
 * deprecated `@AggregateRoute(spaced, owner)` when it differs from its default, then the default. With the new
 * annotation in the hierarchy, the nearest class declaring the policy in either form decides; the two forms on one
 * class disagreeing is an [IllegalStateException], which the processor reports as a compile error.
 */
internal object AggregatePolicyResolver {

    @Suppress("DEPRECATION")
    fun KSClassDeclaration.resolveAggregatePolicy(): AggregatePolicy {
        val spaced = resolvePolicy(
            policy = "spaced",
            declaredAt = { level ->
                level.getAnnotation(Spaced::class)?.let { it.argument(Spaced::value.name) as Boolean? ?: true }
            },
            // compat(wow<9.3): the deprecated AggregateRoute.spaced and .owner, read when the new annotation is absent.
            legacyAt = { level ->
                (level.getAnnotation(AggregateRoute::class)?.argument(AggregateRoute::spaced.name) as Boolean?)
                    ?.takeIf { it }
            },
            describe = { declared, legacy -> "@Spaced($declared) and @AggregateRoute(spaced = $legacy)" }
        ) ?: false
        val owner = resolvePolicy(
            policy = "owner",
            declaredAt = { level ->
                level.getAnnotation(AggregateOwner::class)
                    ?.argument(AggregateOwner::value.name)
                    ?.enumEntryName()
                    ?.let { OwnerPolicy.valueOf(it) }
            },
            legacyAt = { level ->
                level.getAnnotation(AggregateRoute::class)
                    ?.argument(AggregateRoute::owner.name)
                    ?.enumEntryName()
                    ?.takeIf { it != OwnerPolicy.NEVER.name }
                    ?.let { OwnerPolicy.valueOf(it) }
            },
            describe = { declared, legacy ->
                "@AggregateOwner(OwnerPolicy.$declared) and @AggregateRoute(owner = Owner.$legacy)"
            }
        ) ?: OwnerPolicy.NEVER
        return AggregatePolicy(spaced = spaced, owner = owner)
    }

    /**
     * Without the new annotation anywhere, as the runtime reads 9.2's declaration: the first `@AggregateRoute` of the
     * hierarchy. With it, the nearest class that declares the policy in either form decides.
     */
    private fun <T : Any> KSClassDeclaration.resolvePolicy(
        policy: String,
        declaredAt: (KSDeclaration) -> T?,
        legacyAt: (KSDeclaration) -> T?,
        describe: (T?, T?) -> String
    ): T? {
        val levels = sequenceOf<KSDeclaration>(this) + getAllSuperTypes().map { it.declaration }
        if (levels.none { declaredAt(it) != null }) {
            return levels.firstOrNull { it.getAnnotation(AggregateRoute::class) != null }?.let(legacyAt)
        }
        levels.forEach { level ->
            val declaredHere = declaredAt(level)
            val legacyHere = legacyAt(level)
            if (declaredHere != null || legacyHere != null) {
                check(declaredHere == null || legacyHere == null || declaredHere == legacyHere) {
                    "Aggregate[${level.qualifiedName?.asString()}] declares $policy twice with different values: " +
                        "${describe(declaredHere, legacyHere)}. " +
                        "Keep the aggregate-level annotation and remove the deprecated AggregateRoute.$policy."
                }
                return declaredHere ?: legacyHere
            }
        }
        return null
    }

    /**
     * The argument's value, its default included when it was not written; `null` when KSP reports neither.
     */
    private fun KSAnnotation.argument(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value
            ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

    private fun Any.enumEntryName(): String = when (this) {
        is KSClassDeclaration -> simpleName.asString()
        is KSType -> declaration.simpleName.asString()
        else -> toString().substringAfterLast('.')
    }
}
