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
 * Allows creation of a new aggregate instance if one doesn't already exist.
 *
 * This annotation enables commands to create aggregate instances on-demand when they
 * don't exist. Without this annotation, commands will fail if the target aggregate
 * cannot be found.
 *
 * Use this annotation when:
 * - Commands should be able to initialize new aggregates
 * - The system supports lazy aggregate creation
 * - Commands represent creation operations that may be retried
 *
 * Example usage:
 * ```kotlin
 * @AggregateRoot
 * @AllowCreate
 * class UserAggregate(
 *     @AggregateId
 *     val userId: String
 * ) {
 *
 *     @OnCommand
 *     fun createIfNotExists(command: CreateUserCommand): UserCreated {
 *         // This command can create a new user if one doesn't exist
 *         return UserCreated(userId, command.email)
 *     }
 * }
 * ```
 *
 * @see CreateAggregate for marking commands that always create new aggregates
 * @see AggregateRoot for aggregate root classes
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class AllowCreate
