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

/**
 * Type alias for workspace identifier strings.
 *
 * This alias provides semantic meaning to String values used as workspace identifiers,
 * improving code readability and type safety in the domain model.
 *
 * @see WorkspaceIdCapable
 */
typealias WorkspaceId = String

/**
 * Marker interface for entities that belong to a specific workspace.
 *
 * This interface establishes a common contract for objects that operate within
 * a multi-tenant or segmented workspace context. Implementing classes must provide
 * a workspace identifier that determines the ownership or scope of the entity.
 *
 * Common use cases include:
 * - Multi-tenant applications where data isolation is required per workspace
 * - Organizational structures where resources belong to different departments
 * - Project-based systems where entities are scoped to specific projects
 *
 * ## Usage Example
 * ```kotlin
 * data class Project(
 *     override val workspaceId: WorkspaceId,
 *     val projectName: String,
 *     val owner: String
 * ) : WorkspaceIdCapable
 *
 * // Accessing workspace identifier
 * val project = Project(workspaceId = "ws-123", name = "MyProject", owner = "user1")
 * println(project.workspaceId) // Output: ws-123
 * ```
 *
 * ## Thread Safety
 * Implementations should ensure proper synchronization when the same instance
 * is accessed from multiple threads.
 *
 * @see WorkspaceId
 */
interface WorkspaceIdCapable {
    /**
     * The unique identifier of the workspace to which this entity belongs.
     *
     * This identifier is used for:
     * - Data isolation between different workspaces
     * - Query filtering and scoping
     * - Access control and authorization checks
     * - Resource ownership tracking
     *
     * The value should not be null or empty for valid instances.
     *
     * @return the workspace identifier as a non-null String
     * @throws IllegalStateException if the workspaceId is accessed when the entity is in an invalid state
     */
    val workspaceId: WorkspaceId

    companion object {
        const val DEFAULT_WORKSPACE_ID: WorkspaceId = TenantId.DEFAULT_TENANT_ID
    }
}
