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

package me.ahoo.wow.api.command

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary

/**
 * Marker interface for commands that delete an aggregate instance.
 *
 * Commands implementing this interface signal that the target aggregate should be
 * permanently removed from the system. The deletion process typically involves:
 * - Marking the aggregate as deleted (soft delete)
 * - Publishing deletion events for downstream processing
 * - Cleaning up related resources and references
 * - Ensuring the operation is idempotent
 *
 * @see AggregateDeleted event published when deletion completes
 * @see RecoverAggregate for undoing deletions
 *
 * Example usage:
 * ```kotlin
 * // Custom delete command with additional data
 * data class DeleteUserCommand(
 *     val reason: String,
 *     val deletedBy: String
 * ) : DeleteAggregate
 *
 * // Or use the default implementation
 * val deleteCommand = DefaultDeleteAggregate
 * ```
 */
interface DeleteAggregate

/**
 * Default implementation of the delete aggregate command.
 *
 * This singleton provides a standard way to delete aggregates without additional payload.
 * It's automatically routed as a DELETE request to the aggregate's resource path.
 *
 * @see CommandRoute for the automatic routing configuration
 * @see Summary for API documentation generation
 */
@Summary("Delete aggregate")
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DefaultDeleteAggregate : DeleteAggregate
