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

/**
 * Configures the routes of an aggregate.
 *
 * This annotation defines:
 * - Resource naming for API endpoints
 * - Whether routes are generated at all
 *
 * Since 9.3.0 the aggregate's own policies are declared on the aggregate with [Spaced] and [AggregateOwner];
 * [spaced] and [owner] are deprecated but still read when the new annotation is absent.
 *
 * The routing configuration affects how commands are dispatched and how API endpoints
 * are generated for the aggregate.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @AggregateRoute(resourceName = "orders")
 * @AggregateOwner(OwnerPolicy.AGGREGATE_ID)
 * class OrderAggregate(
 *     @AggregateId
 *     val orderId: String,
 *
 *     @OwnerId
 *     val customerId: String
 * )
 * ```
 * This generates routes like: `POST /orders/{orderId}/create`
 *
 * @param resourceName Custom name for the resource in API routes. If empty, the aggregate
 *                    class name (lowercased) will be used. This affects URL generation.
 * @param enabled Whether routing is enabled for this aggregate. When false, no routes
 *               will be generated. Defaults to true.
 * @param spaced Deprecated since 9.3.0, use [Spaced]. Whether the aggregate's routes take a space from the request's `Wow-Space-Id` header (it adds
 *               no path segment). Only a spaced aggregate has a space written to its commands and its queries
 *               scoped by it. A command to any other aggregate carries the default space whatever its source
 *               (HTTP, a saga reacting to a spaced aggregate's event, in-process), and that aggregate neither checks
 *               nor records a command's space. Defaults to false.
 * @param owner Deprecated since 9.3.0, use [AggregateOwner]. Ownership policy determining tenant isolation.
 *             Controls whether operations require owner context and how ownership is determined.
 *
 * @see Spaced
 * @see AggregateOwner
 * @see AggregateRoot for marking aggregate root classes
 */
@Target(AnnotationTarget.CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateRoute(
    val resourceName: String = "",
    val enabled: Boolean = true,
    @Deprecated("Scheduled for removal in 10.0.0. Use @Spaced on the aggregate.")
    val spaced: Boolean = false,
    @Deprecated("Scheduled for removal in 10.0.0. Use @AggregateOwner on the aggregate.")
    @Suppress("DEPRECATION")
    val owner: Owner = Owner.NEVER
) {
    /**
     * Defines ownership policies for aggregate operations in multi-tenant scenarios.
     *
     * @param owned Whether this policy requires ownership context for operations.
     */
    @Deprecated("Scheduled for removal in 10.0.0. Use OwnerPolicy with @AggregateOwner.")
    enum class Owner(
        val owned: Boolean
    ) {
        /**
         * No ownership required. Operations can be performed without owner context.
         * Suitable for public aggregates or system-wide resources.
         */
        NEVER(false),

        /**
         * Ownership always required. All operations must specify an owner context.
         * Used when aggregates are strictly isolated by owner.
         */
        ALWAYS(true),

        /**
         * Owner ID is the same as aggregate ID. The aggregate instance itself serves
         * as the ownership boundary. Common for user-specific aggregates.
         */
        AGGREGATE_ID(true)
    }
}
