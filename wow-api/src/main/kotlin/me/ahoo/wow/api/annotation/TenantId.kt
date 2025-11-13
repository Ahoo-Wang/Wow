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

import me.ahoo.wow.api.modeling.TenantId
import java.lang.annotation.Inherited

/**
 * Marks a field or property as containing the tenant identifier for multi-tenant applications.
 *
 * The tenant ID identifies which tenant (organization, customer, etc.) the data belongs to.
 * This annotation enables automatic tenant context propagation and data isolation in
 * multi-tenant systems.
 *
 * The framework uses this annotation to:
 * - Automatically set tenant context from requests
 * - Isolate data access by tenant boundaries
 * - Generate tenant-specific routing and storage
 * - Enforce tenant-based security policies
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * class UserAggregate(
 *     @AggregateId
 *     val userId: String,
 *
 *     @TenantId
 *     val tenantId: String  // Which organization this user belongs to
 * )
 *
 * // Command that operates within a tenant context
 * data class CreateUserCommand(
 *     @AggregateId
 *     val userId: String,
 *
 *     @TenantId
 *     val tenantId: String,  // Automatically populated from request context
 *
 *     val email: String,
 *     val name: String
 * )
 * ```
 * @see StaticTenantId for aggregates with fixed tenant IDs
 * @see TenantId.DEFAULT_TENANT_ID for the default tenant identifier
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Inherited
@MustBeDocumented
annotation class TenantId

/**
 * Specifies a static tenant ID for classes that belong to a fixed tenant.
 *
 * This annotation is used for aggregates or components that are permanently associated
 * with a specific tenant, eliminating the need for dynamic tenant resolution.
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @StaticTenantId("system-tenant")  // Always belongs to system tenant
 * class SystemConfigurationAggregate {
 *
 *     @OnCommand
 *     fun updateGlobalSettings(command: UpdateSettingsCommand) {
 *         // System-wide configuration changes
 *     }
 * }
 * ```
 * @param tenantId The fixed tenant identifier. Defaults to [TenantId.DEFAULT_TENANT_ID].
 *
 * @see TenantId for dynamic tenant identification
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class StaticTenantId(
    val tenantId: String = TenantId.DEFAULT_TENANT_ID
)
