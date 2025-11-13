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
 * Configures routing and ownership behavior for aggregate operations.
 *
 * This annotation defines how aggregate instances are accessed and managed in terms of:
 * - Resource naming for API endpoints
 * - Ownership policies for multi-tenant scenarios
 * - Route generation for REST APIs
 *
 * The routing configuration affects how commands are dispatched and how API endpoints
 * are generated for the aggregate.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @AggregateRoute(
 *     resourceName = "orders",
 *     owner = AggregateRoute.Owner.AGGREGATE_ID
 * )
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
 * @param owner Ownership policy determining tenant isolation. Controls whether operations
 *             require owner context and how ownership is determined.
 *
 * @see Owner for available ownership policies
 * @see AggregateRoot for marking aggregate root classes
 */
@Target(AnnotationTarget.CLASS)
@Inherited
@MustBeDocumented
annotation class AggregateRoute(
    val resourceName: String = "",
    val enabled: Boolean = true,
    val owner: Owner = Owner.NEVER
) {
    /**
     * Defines ownership policies for aggregate operations in multi-tenant scenarios.
     *
     * @param owned Whether this policy requires ownership context for operations.
     */
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
