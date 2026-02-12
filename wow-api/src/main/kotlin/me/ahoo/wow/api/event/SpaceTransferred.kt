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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.modeling.SpaceId

/**
 * Represents a domain event indicating that an aggregate has been transferred to a different namespace/space.
 *
 * This interface is typically implemented by domain events that signal a change in the aggregate's
 * namespace, such as moving data between different environments, data partitions, or organizational
 * boundaries within a multi-tenant system. The transfer allows for granular data isolation and
 * organization beyond tenant and owner boundaries.
 *
 * Namespace transfers are useful for:
 * - Environment migration (e.g., moving from "staging" to "production")
 * - Data archival and retrieval (e.g., moving old data to "archive" space)
 * - Organizational restructuring (e.g., moving between department spaces)
 * - Multi-tenant data isolation (e.g., separating data by workspace or project)
 *
 * @property toSpaceId The target namespace/space identifier to which the aggregate is being transferred.
 *                     This should be a non-empty string representing a valid space identifier.
 *                     An empty string indicates the default space for the tenant.
 *
 * @see SpaceIdCapable for the interface that defines space identification
 * @see OwnerTransferred for ownership transfer events
 *
 * @since 1.0.0
 */
interface SpaceTransferred {
    /**
     * The target space identifier after the transfer.
     *
     * This value represents the namespace where the aggregate will reside following the transfer.
     * Implementations should ensure this is a valid, non-null space identifier.
     *
     * @return The target [SpaceId] for the transferred aggregate.
     */
    val toSpaceId: SpaceId
}
