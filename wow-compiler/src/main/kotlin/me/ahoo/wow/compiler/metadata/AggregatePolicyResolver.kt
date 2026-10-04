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
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.AggregateOwner
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.annotation.Spaced
import me.ahoo.wow.compiler.metadata.BoundedContextResolver.getAnnotation
import kotlin.reflect.KClass

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
 * deprecated `@AggregateRoute(spaced, owner)` when it differs from its default, then the default. Both declared
 * with different values is an [IllegalStateException], which the processor reports as a compile error.
 */
internal object AggregatePolicyResolver {

    @Suppress("DEPRECATION")
    fun KSClassDeclaration.resolveAggregatePolicy(): AggregatePolicy {
        val aggregate = qualifiedName?.asString()
        val route = findAnnotation(AggregateRoute::class)

        val declaredSpaced = findAnnotation(Spaced::class)?.let { it.argument(Spaced::value.name) as Boolean? ?: true }
        // compat(wow<9.3): the deprecated AggregateRoute.spaced and .owner, read when the new annotation is absent.
        val legacySpaced = (route?.argument(AggregateRoute::spaced.name) as Boolean?)?.takeIf { it }
        check(declaredSpaced == null || legacySpaced == null || declaredSpaced == legacySpaced) {
            "Aggregate[$aggregate] declares spaced twice with different values: @Spaced($declaredSpaced) and " +
                "@AggregateRoute(spaced = $legacySpaced). Keep @Spaced and remove the deprecated AggregateRoute.spaced."
        }

        val declaredOwner = findAnnotation(AggregateOwner::class)
            ?.argument(AggregateOwner::value.name)
            ?.enumEntryName()
            ?.let { OwnerPolicy.valueOf(it) }
        val legacyOwner = route?.argument(AggregateRoute::owner.name)
            ?.enumEntryName()
            ?.takeIf { it != OwnerPolicy.NEVER.name }
            ?.let { OwnerPolicy.valueOf(it) }
        check(declaredOwner == null || legacyOwner == null || declaredOwner == legacyOwner) {
            "Aggregate[$aggregate] declares its owner twice with different policies: " +
                "@AggregateOwner(OwnerPolicy.$declaredOwner) and @AggregateRoute(owner = Owner.$legacyOwner). " +
                "Keep @AggregateOwner and remove the deprecated AggregateRoute.owner."
        }

        return AggregatePolicy(
            spaced = declaredSpaced ?: legacySpaced ?: false,
            owner = declaredOwner ?: legacyOwner ?: OwnerPolicy.NEVER
        )
    }

    /**
     * The annotation on this class, else on its nearest supertype that has it (all three annotations are
     * `@Inherited`).
     */
    private fun KSClassDeclaration.findAnnotation(annotationClass: KClass<*>): KSAnnotation? =
        getAnnotation(annotationClass)
            ?: getAllSuperTypes().firstNotNullOfOrNull { it.declaration.getAnnotation(annotationClass) }

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
