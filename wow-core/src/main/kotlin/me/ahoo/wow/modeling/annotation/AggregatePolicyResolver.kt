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

package me.ahoo.wow.modeling.annotation

import me.ahoo.wow.api.annotation.AggregateOwner
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.annotation.Spaced
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.configuration.WOW_METADATA_RESOURCE_NAME
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.reflect.KClass

/**
 * The one place that reads an aggregate's policy declarations: whether it is spaced, its owner policy and its static
 * tenant. Each policy is read from its aggregate-level declaration first, then from the place 9.2 read it, then
 * defaults; two declarations that disagree fail instead of one silently winning.
 *
 * `@AggregateRoute(spaced, owner)` cannot tell "not written" from "written as the default", so it counts as a
 * declaration only when it differs from the default (`spaced = true`, `owner != NEVER`). The effective value of every
 * aggregate that declares nothing new is therefore the one 9.2 parsed.
 */
internal object AggregatePolicyResolver {

    /**
     * `@Spaced` → `@AggregateRoute(spaced = true)` → not spaced.
     */
    @Suppress("DEPRECATION")
    fun KClass<*>.resolveSpaced(): Boolean = resolvePolicy(
        policy = "spaced",
        declaredAt = { it.getDeclaredAnnotation(Spaced::class.java)?.value },
        // compat(wow<9.3): the deprecated AggregateRoute.spaced, read when @Spaced is absent.
        legacyAt = { it.getDeclaredAnnotation(AggregateRoute::class.java)?.spaced?.takeIf { spaced -> spaced } },
        declared = { scanAnnotation<Spaced>()?.value },
        legacy = { scanAnnotation<AggregateRoute>()?.spaced?.takeIf { it } },
        describe = { declared, legacy -> "@Spaced($declared) and @AggregateRoute(spaced = $legacy)" },
        default = false
    )

    /**
     * `@AggregateOwner` → `@AggregateRoute(owner != NEVER)` → [OwnerPolicy.NEVER].
     */
    @Suppress("DEPRECATION")
    fun KClass<*>.resolveOwnerPolicy(): OwnerPolicy = resolvePolicy(
        policy = "owner",
        declaredAt = { it.getDeclaredAnnotation(AggregateOwner::class.java)?.value },
        // compat(wow<9.3): the deprecated AggregateRoute.owner, read when @AggregateOwner is absent.
        legacyAt = { it.getDeclaredAnnotation(AggregateRoute::class.java)?.owner.toOwnerPolicy() },
        declared = { scanAnnotation<AggregateOwner>()?.value },
        legacy = { scanAnnotation<AggregateRoute>()?.owner.toOwnerPolicy() },
        describe = { declared, legacy ->
            "@AggregateOwner(OwnerPolicy.$declared) and @AggregateRoute(owner = Owner.$legacy)"
        },
        default = OwnerPolicy.NEVER
    )

    @Suppress("DEPRECATION")
    private fun AggregateRoute.Owner?.toOwnerPolicy(): OwnerPolicy? =
        this?.takeIf { it != AggregateRoute.Owner.NEVER }?.let { OwnerPolicy.valueOf(it.name) }

    /**
     * Without the new annotation anywhere in the hierarchy, 9.2's reading: the first `@AggregateRoute` found.
     *
     * With it, the nearest class (the aggregate, then its supertypes) that declares the policy in either form decides,
     * so `@Spaced(false)` on an aggregate overrides `@AggregateRoute(spaced = true)` on its supertype. Only the two
     * forms on the same class disagreeing is a conflict.
     */
    @Suppress("LongParameterList")
    private fun <T : Any> KClass<*>.resolvePolicy(
        policy: String,
        declaredAt: (Class<*>) -> T?,
        legacyAt: (Class<*>) -> T?,
        declared: () -> T?,
        legacy: () -> T?,
        describe: (T?, T?) -> String,
        default: T
    ): T {
        val anyDeclared = declared()
        if (anyDeclared != null) {
            java.hierarchy().forEach { level ->
                val declaredHere = declaredAt(level)
                val legacyHere = legacyAt(level)
                if (declaredHere != null || legacyHere != null) {
                    checkAgree(level.name, policy, declaredHere, legacyHere, describe)
                    return declaredHere ?: legacyHere!!
                }
            }
        }
        // Not declared with the new annotation, or only through a meta-annotation.
        val legacyValue = legacy()
        checkAgree(qualifiedName, policy, anyDeclared, legacyValue, describe)
        return anyDeclared ?: legacyValue ?: default
    }

    private fun <T : Any> checkAgree(
        type: String?,
        policy: String,
        declared: T?,
        legacy: T?,
        describe: (T?, T?) -> String
    ) {
        check(declared == null || legacy == null || declared == legacy) {
            "Aggregate[$type] declares $policy twice with different values: ${describe(declared, legacy)}. " +
                "Keep the aggregate-level annotation and remove the deprecated AggregateRoute.$policy."
        }
    }

    /**
     * The class, then its superclasses and interfaces, nearest first.
     */
    private fun Class<*>.hierarchy(): Sequence<Class<*>> = sequence {
        val visited = mutableSetOf<Class<*>>()
        val queue = ArrayDeque(listOf(this@hierarchy))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!visited.add(type)) continue
            yield(type)
            type.superclass?.takeIf { it != Any::class.java }?.let { queue.addLast(it) }
            queue.addAll(type.interfaces)
        }
    }

    /**
     * `@StaticTenantId` → the `tenantId` of the aggregate in [WOW_METADATA_RESOURCE_NAME] (which the KSP processor
     * fills from `@BoundedContext.Aggregate(tenantId)` and `@StaticTenantId`) → none.
     *
     * @param metadataTenantId the aggregate's `tenantId` in the merged metadata resources.
     */
    fun KClass<*>.resolveStaticTenantId(metadataTenantId: String?): String? {
        val declared = scanAnnotation<StaticTenantId>()?.tenantId
        check(declared.isNullOrBlank() || metadataTenantId.isNullOrBlank() || declared == metadataTenantId) {
            "Aggregate[$qualifiedName] declares its static tenant twice with different values: " +
                "@StaticTenantId(\"$declared\") and tenantId \"$metadataTenantId\" in $WOW_METADATA_RESOURCE_NAME " +
                "(from @BoundedContext.Aggregate(tenantId) or a hand-written resource). Declare one, or make them equal."
        }
        return declared ?: metadataTenantId
    }
}
