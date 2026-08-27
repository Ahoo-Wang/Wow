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
 * Marks a command as a void command that doesn't require aggregate processing.
 *
 * Void commands are sent to the command bus but don't need to be processed by aggregate roots.
 * They are typically used for operations that don't require state changes or return values,
 * such as logging, notifications, or external system integrations.
 *
 * Void command characteristics:
 * - Sent to command bus without requiring aggregate root processing
 * - No return value or state update expected
 * - Can be used for side effects and cross-cutting concerns
 * - Must be mounted to aggregate roots via [AggregateRoot.commands]
 *
 * Common use cases:
 * - Recording user query operations for analytics
 * - Sending notifications without changing domain state
 * - Logging audit events
 * - Triggering external system synchronizations
 *
 * Example usage:
 * ```kotlin
 * @VoidCommand
 * data class LogUserQuery(
 *     val userId: String,
 *     val query: String,
 *     val timestamp: Instant = Instant.now()
 * )
 *
 * @AggregateRoot(commands = [LogUserQuery::class])
 * class UserQueryLogger {
 *     // No command handlers needed - just receives the command for logging
 * }
 *
 * // Usage
 * commandBus.send(LogUserQuery(userId, searchQuery))
 * // Command is processed (logged) but no aggregate state is changed
 * ```
 *
 * @see AggregateRoot for mounting void commands to aggregates
 * @see AggregateRoot.commands for command mounting configuration
 **/
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class VoidCommand
