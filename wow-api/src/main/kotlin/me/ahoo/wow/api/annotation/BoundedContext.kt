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

package me.ahoo.wow.api.annotation

import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * Defines a bounded context in domain-driven design (DDD).
 *
 * A bounded context represents a coherent area of the business domain with clear boundaries,
 * containing its own ubiquitous language, domain model, and business rules. It defines
 * the scope where particular terms and rules apply.
 *
 * This annotation helps organize the codebase by grouping related aggregates and establishing
 * clear boundaries between different business domains.
 *
 * Example usage:
 * ```kotlin
 * @BoundedContext(
 *     name = "ecommerce",
 *     alias = "shop",
 *     description = "Handles online retail operations",
 *     aggregates = [
 *         BoundedContext.Aggregate(
 *             name = "order",
 *             tenantId = "tenant-1"
 *         ),
 *         BoundedContext.Aggregate(
 *             name = "product",
 *             tenantId = "tenant-1"
 *         )
 *     ]
 * )
 * ```
 * @param name The unique name of the bounded context. Used for identification and configuration.
 * @param alias An optional alias for the bounded context, useful for shorter references.
 * @param description A human-readable description of the bounded context's purpose and scope.
 * @param scopes Array of scope identifiers that define the context's boundaries.
 * @param packageScopes Array of package classes that define which packages belong to this context.
 * @param aggregates Array of aggregate definitions within this bounded context.
 *
 * @see Aggregate for defining aggregates within a bounded context
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class BoundedContext(
    /**
     * The unique name of the bounded context.
     */
    val name: String,
    /**
     * Optional alias for shorter references to this bounded context.
     */
    val alias: String = "",
    /**
     * Human-readable description of the bounded context's purpose.
     */
    val description: String = "",
    /**
     * Scope identifiers defining the context's boundaries.
     */
    val scopes: Array<String> = [],
    /**
     * Package classes that belong to this bounded context.
     */
    val packageScopes: Array<KClass<*>> = [],
    /**
     * Aggregate definitions within this bounded context.
     */
    val aggregates: Array<Aggregate> = []
) {
    /**
     * Defines an aggregate within a bounded context.
     *
     * Aggregates are clusters of domain objects that can be treated as a single unit
     * for data changes. Each aggregate has a root entity and may contain other entities
     * and value objects.
     */
    @Target(AnnotationTarget.CLASS)
    @Inherited
    @MustBeDocumented
    annotation class Aggregate(
        /**
         * The name of the aggregate. Must be unique within the bounded context.
         */
        val name: String,
        /**
         * Static tenant ID for multi-tenant scenarios. If specified, all instances
         * of this aggregate will belong to this tenant.
         */
        val tenantId: String = "",
        /**
         * Name of a custom ID generator for this aggregate. If empty, the default
         * ID generation strategy will be used.
         */
        val id: String = "",
        /**
         * Scope identifiers specific to this aggregate.
         */
        val scopes: Array<String> = [],
        /**
         * Package classes that contain the aggregate's implementation.
         */
        val packageScopes: Array<KClass<*>> = []
    )
}
