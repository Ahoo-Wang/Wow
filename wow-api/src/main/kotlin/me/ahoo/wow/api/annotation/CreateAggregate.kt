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
 * Marks a command as one that creates new aggregate instances.
 *
 * Commands annotated with @CreateAggregate are designated as initialization commands
 * that create new aggregates. These commands are the first commands applied to an
 * aggregate and establish its initial state.
 *
 * Create commands typically:
 * - Have no version requirements (target version is 0)
 * - Cannot be applied to existing aggregates
 * - Initialize the aggregate's state from scratch
 * - Publish creation events
 *
 * Example usage:
 * ```kotlin
 * @CreateAggregate
 * data class CreateUserCommand(
 *     @AggregateId
 *     val userId: String,
 *     val email: String,
 *     val name: String
 * )
 *
 * @AggregateRoot
 * class UserAggregate {
 *
 *     @OnCommand
 *     fun create(command: CreateUserCommand): UserCreated {
 *         // This is the first command for new users
 *         return UserCreated(command.userId, command.email, command.name)
 *     }
 * }
 * ```
 * @see AllowCreate for commands that can create aggregates conditionally
 * @see AggregateRoot for aggregate root classes
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CreateAggregate
