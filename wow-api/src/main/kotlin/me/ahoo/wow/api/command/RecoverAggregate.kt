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
 * Marker interface for commands that recover a previously deleted aggregate.
 *
 * Commands implementing this interface signal that a soft-deleted aggregate should be
 * restored to active status. The recovery process typically involves:
 * - Unmarking the aggregate as deleted
 * - Publishing recovery events for downstream processing
 * - Restoring related resources and references
 * - Ensuring the operation is idempotent for already recovered aggregates
 *
 * @see AggregateRecovered event published when recovery completes
 * @see DeleteAggregate for the deletion operation
 * @see AggregateDeleted for the deletion event
 *
 * Example usage:
 * ```kotlin
 * // Custom recover command with additional data
 * data class RecoverUserCommand(
 *     val reason: String,
 *     val recoveredBy: String
 * ) : RecoverAggregate
 *
 * // Or use the default implementation
 * val recoverCommand = DefaultRecoverAggregate
 * ```
 */
interface RecoverAggregate

/**
 * Default implementation of the recover aggregate command.
 *
 * This singleton provides a standard way to recover deleted aggregates without additional payload.
 * It's automatically routed as a PUT request to the aggregate's "recover" action path.
 *
 * @see CommandRoute for the automatic routing configuration
 * @see Summary for API documentation generation
 */
@Summary("Recover deleted aggregate")
@CommandRoute(action = "recover", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DefaultRecoverAggregate : RecoverAggregate
