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
    fun KClass<*>.resolveSpaced(): Boolean {
        val declared = scanAnnotation<Spaced>()?.value
        // compat(wow<9.3): the deprecated AggregateRoute.spaced, read when @Spaced is absent.
        val legacy = scanAnnotation<AggregateRoute>()?.spaced?.takeIf { it }
        check(declared == null || legacy == null || declared == legacy) {
            "Aggregate[$qualifiedName] declares spaced twice with different values: @Spaced($declared) and " +
                "@AggregateRoute(spaced = $legacy). Keep @Spaced and remove the deprecated AggregateRoute.spaced."
        }
        return declared ?: legacy ?: false
    }

    /**
     * `@AggregateOwner` → `@AggregateRoute(owner != NEVER)` → [OwnerPolicy.NEVER].
     */
    @Suppress("DEPRECATION")
    fun KClass<*>.resolveOwnerPolicy(): OwnerPolicy {
        val declared = scanAnnotation<AggregateOwner>()?.value
        // compat(wow<9.3): the deprecated AggregateRoute.owner, read when @AggregateOwner is absent.
        val legacy = scanAnnotation<AggregateRoute>()?.owner
            ?.takeIf { it != AggregateRoute.Owner.NEVER }
            ?.let { OwnerPolicy.valueOf(it.name) }
        check(declared == null || legacy == null || declared == legacy) {
            "Aggregate[$qualifiedName] declares its owner twice with different policies: " +
                "@AggregateOwner(OwnerPolicy.$declared) and @AggregateRoute(owner = Owner.$legacy). " +
                "Keep @AggregateOwner and remove the deprecated AggregateRoute.owner."
        }
        return declared ?: legacy ?: OwnerPolicy.NEVER
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
