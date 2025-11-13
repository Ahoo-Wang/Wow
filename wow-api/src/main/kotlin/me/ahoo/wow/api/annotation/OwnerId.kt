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
 * Marks a field or property as containing the owner identifier for multi-tenant scenarios.
 *
 * The owner ID represents the tenant or user context that owns the data. This annotation
 * is used by the framework to:
 * - Enforce ownership-based access control
 * - Route commands to correct tenant contexts
 * - Isolate data between different owners
 * - Generate tenant-specific API endpoints
 *
 * The annotated field/property should contain a unique identifier for the owner/tenant,
 * such as a user ID, organization ID, or tenant key.
 *
 * @see TenantId for tenant-specific identification
 * @see AggregateRoute.Owner for ownership routing policies
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
 * class UserProfile(
 *     @AggregateId
 *     val profileId: String,
 *
 *     @OwnerId
 *     val userId: String  // The user who owns this profile
 * )
 *
 * // Command targeting the user's profile
 * data class UpdateProfileCommand(
 *     @AggregateId
 *     val profileId: String,
 *
 *     @OwnerId
 *     val userId: String,  // Must match the authenticated user
 *
 *     val newName: String
 * )
 * ```
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Inherited
@MustBeDocumented
annotation class OwnerId
