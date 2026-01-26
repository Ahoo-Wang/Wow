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

package me.ahoo.wow.api.modeling

import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.DEFAULT_SPACE_ID


typealias SpaceId = String

/**
 * Interface for entities that support namespace-based data layering within a tenant context.
 *
 * The namespace provides an additional level of data isolation and organization beneath the [tenantId],
 * enabling hierarchical data partitioning in multi-tenant applications. This allows for more granular
 * control over data separation and organization within a single tenant.
 *
 * Implementations of this interface should provide a meaningful namespace string that represents
 * a logical grouping or layer of data. Common use cases include:
 * - Environment isolation (e.g., "dev", "staging", "prod")
 * - Data type partitioning (e.g., "metadata", "analytics", "cache")
 * - Business domain separation (e.g., "primary", "archive", "backup")
 *
 * @property spaceId The namespace string used for data layering under a tenant. This value should
 * be non-null and represent a valid identifier for the data layer. An empty string indicates the
 * default namespace for the tenant.
 */
interface SpaceIdCapable {
    val spaceId: SpaceId

    companion object {
        /**
         * Represents the default/root namespace within a tenant context.
         * Entities without explicit namespace assignment belong to this default space.
         */
        const val DEFAULT_SPACE_ID = ""

        /**
         * Safely resolves a nullable space identifier to the default namespace if null or empty.
         *
         * This extension function provides a convenient way to handle optional namespace values,
         * ensuring a consistent non-null return value for data layer operations.
         *
         * @receiver The nullable [SpaceId] to resolve, may be null or empty
         * @return The provided spaceId if non-null and non-empty, otherwise [DEFAULT_SPACE_ID]
         */
        fun String?.orDefaultSpaceId(): String = this ?: DEFAULT_SPACE_ID
    }
}
