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

import org.springframework.stereotype.Component

/**
 * Marks an interface as a PrepareKey for optimistic concurrency control and resource reservation.
 *
 * PrepareKey interfaces define operations for preparing, committing, and rolling back
 * changes to shared resources in a transactional manner. This annotation enables the framework
 * to automatically generate proxy implementations that handle the complete prepare-commit-rollback
 * lifecycle with built-in error handling and transaction management.
 *
 * The annotated interface must extend `PrepareKey<T>` where `T` is the value type being prepared.
 * The framework creates dynamic proxy instances that delegate to underlying PrepareKey implementations
 * while providing additional capabilities such as:
 * - Automatic transaction management
 * - Error handling and rollback on failures
 * - Resource cleanup on exceptions
 * - Lifecycle management for prepared values
 *
 * ## Prepare-Commit-Rollback Lifecycle
 *
 * 1. **Prepare**: Reserve or validate the resource before making changes
 * 2. **Execute**: Perform the business logic within the prepared context
 * 3. **Commit**: Confirm the changes if execution succeeds
 * 4. **Rollback**: Release/cancel the preparation if execution fails
 *
 * ## Thread Safety
 *
 * PrepareKey implementations should be thread-safe as they may be used concurrently
 * across multiple requests. The framework handles synchronization through the proxy layer.
 *
 * ## Error Handling
 *
 * If an exception occurs during the `usingPrepare` block, the framework automatically:
 * - Calls `rollback()` to release prepared resources
 * - Propagates the original exception to the caller
 * - Ensures cleanup happens even if rollback fails
 *
 * @param name Optional custom name for the prepare key. If empty, the framework will
 *             derive the name from the interface's simple class name. The name is used
 *             to identify and configure the underlying PrepareKey implementation.
 *             Type: String
 *             Default: "" (empty string)
 *
 * @throws IllegalArgumentException if the annotated class does not extend PrepareKey<T>
 * @throws IllegalStateException if proxy creation fails due to configuration issues
 *
 * Example usage:
 * ```kotlin
 * @PreparableKey("user-username")
 * interface UserUsernamePrepareKey : PrepareKey<String> {
 *     // Interface can declare additional prepare-related methods
 *     fun validateUsername(username: String): Boolean
 * }
 *
 * // Usage in service with automatic lifecycle management
 * @Service
 * class UserService(
 *     private val usernameKey: UserUsernamePrepareKey
 * ) {
 *
 *     fun changeUsername(userId: String, newUsername: String) {
 *         // Framework automatically handles prepare/commit/rollback
 *         usernameKey.usingPrepare(newUsername) {
 *             // This block executes within prepared context
 *             require(usernameKey.validateUsername(newUsername)) {
 *                 "Username already taken"
 *             }
 *
 *             // Perform the actual username change
 *             userRepository.updateUsername(userId, newUsername)
 *
 *             // If successful, framework calls commit()
 *             // If exception thrown, framework calls rollback()
 *         }
 *     }
 *
 *     fun registerUser(email: String, username: String): UserId {
 *         return usernameKey.usingPrepare(username) {
 *             val userId = userRepository.createUser(email)
 *             userRepository.setUsername(userId, username)
 *             userId  // Return value from prepared block
 *         }
 *     }
 * }
 *
 * // Manual lifecycle management (less common)
 * fun manualUsernameChange(usernameKey: UserUsernamePrepareKey, newUsername: String) {
 *     val preparedValue = usernameKey.prepare(newUsername)
 *     try {
 *         // Perform operations with prepared value
 *         usernameKey.commit(preparedValue)
 *     } catch (e: Exception) {
 *         usernameKey.rollback(preparedValue)
 *         throw e
 *     }
 * }
 * ```
 *
 * @see PrepareKey for the base interface defining prepare operations
 * @see me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyFactory for proxy creation details
 * @see me.ahoo.wow.infra.prepare.PreparedValue for the prepared value container
 * @since 1.0.0
 */
@Target(AnnotationTarget.CLASS)
@Component
annotation class PreparableKey(
    val name: String = ""
)
