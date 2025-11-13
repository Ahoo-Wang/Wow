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

package me.ahoo.wow.infra.prepare

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import reactor.core.publisher.Mono

/**
 * Key preparation interface for ensuring uniqueness constraints in EventSourcing architectures.
 *
 * Unlike traditional databases that use UNIQUE KEY constraints, EventSourcing requires
 * application-level uniqueness guarantees. PrepareKey provides a mechanism to "prepare"
 * or reserve keys before they're actually used, ensuring atomicity and preventing race conditions.
 *
 * Key features:
 * - Atomic key preparation and rollback
 * - TTL (Time-To-Live) support for temporary reservations
 * - Reprepare operations for key changes
 * - Transaction-like semantics with automatic rollback on failure
 *
 * Common use cases:
 * - Username/email uniqueness during user registration
 * - Product SKU uniqueness
 * - Resource identifier allocation
 * - Preventing duplicate operations
 *
 * @param V The type of value associated with prepared keys
 *
 * Example usage for user registration:
 * ```kotlin
 * @AggregateRoot
 * class User(private val state: UserState) {
 *     @OnCommand
 *     private fun onRegister(
 *         register: Register,
 *         passwordEncoder: PasswordEncoder,
 *         usernamePrepare: PrepareKey<UsernameIndexValue>,
 *     ): Mono<Registered> {
 *         val encodedPassword = passwordEncoder.encode(register.password)
 *         return usernamePrepare.usingPrepare(
 *             key = register.username,
 *             value = UsernameIndexValue(
 *                 userId = state.id,
 *                 password = encodedPassword,
 *             ),
 *         ) {
 *             require(it) {
 *                 "username[${register.username}] is already registered."
 *             }
 *             Registered(username = register.username, password = encodedPassword).toMono()
 *         }
 *     }
 * }
 * ```
 *
 * Example usage for changing username:
 * ```kotlin
 * @OnCommand
 * private fun onChangeUsername(
 *     changeUsername: ChangeUsername,
 *     usernamePrepare: PrepareKey<UsernameIndexValue>
 * ): Mono<UsernameChanged> {
 *     val usernameIndexValue = UsernameIndexValue(
 *         userId = state.id,
 *         password = state.password,
 *     )
 *     return usernamePrepare.reprepare(
 *         oldKey = state.username,
 *         oldValue = usernameIndexValue,
 *         newKey = changeUsername.newUsername,
 *         newValue = usernameIndexValue
 *     ).map {
 *         require(it) {
 *             "username[${changeUsername.newUsername}] is already registered."
 *         }
 *         UsernameChanged(username = changeUsername.newUsername)
 *     }
 * }
 * ```
 *
 * @see PreparedValue for value wrapper with TTL support
 * @see PrepareKeyProxyFactory for proxy-based implementation
 */
interface PrepareKey<V : Any> : Named {
    /**
     * Prepares a key with a value that never expires.
     *
     * This method reserves the key permanently, ensuring no other operations can use
     * the same key until it's explicitly rolled back.
     *
     * @param key The unique key to prepare
     * @param value The value to associate with the key
     * @return A Mono emitting true if preparation succeeded, false if key was already prepared
     *
     * @see prepare(String, PreparedValue) for TTL-based preparation
     */
    fun prepare(
        key: String,
        value: V
    ): Mono<Boolean> = prepare(key, value.toForever())

    /**
     * Prepares a key with a value that may have a time-to-live.
     *
     * This method reserves the key for a specified duration, after which it automatically
     * expires if not used. This is useful for temporary reservations or cleanup scenarios.
     *
     * @param key The unique key to prepare
     * @param value The prepared value with optional TTL
     * @return A Mono emitting true if preparation succeeded, false if key was already prepared
     *
     * @throws IllegalArgumentException if key is null or empty
     * @see PreparedValue for TTL configuration
     */
    fun prepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean>

    /**
     * Retrieves a prepared value by key, filtering out expired entries.
     *
     * This method returns only non-expired prepared values. If the value has expired,
     * an empty Mono is returned.
     *
     * @param key The key to look up
     * @return A Mono emitting the prepared value, or empty if not found or expired
     *
     * @see getValue for retrieving with expiration information
     */
    fun get(key: String): Mono<V> =
        getValue(key)
            .filter { !it.isExpired }
            .map { it.value }

    /**
     * Retrieves the full prepared value information including expiration status.
     *
     * This method returns the complete PreparedValue object, allowing access to
     * both the value and its expiration information.
     *
     * @param key The key to look up
     * @return A Mono emitting the PreparedValue, or empty if not found
     *
     * @see PreparedValue for expiration checking methods
     */
    fun getValue(key: String): Mono<PreparedValue<V>>

    /**
     * Rolls back any prepared value for the given key.
     *
     * This method removes the key preparation regardless of its current value.
     * Use this when you want to unconditionally release a key reservation.
     *
     * @param key The key to rollback
     * @return A Mono emitting true if rollback succeeded, false if key was not prepared
     */
    fun rollback(key: String): Mono<Boolean>

    /**
     * Rolls back a prepared key only if it matches the specified value.
     *
     * This conditional rollback ensures atomicity by only releasing the key if
     * it contains the expected value. This prevents race conditions where
     * another operation has modified the prepared value.
     *
     * @param key The key to rollback
     * @param value The expected value that must match for rollback to succeed
     * @return A Mono emitting true if rollback succeeded with matching value, false otherwise
     */
    fun rollback(
        key: String,
        value: V
    ): Mono<Boolean>

    /**
     * Reprepares a key with a new value that never expires.
     *
     * This method updates an existing key preparation with a new permanent value.
     * The old value is used for optimistic concurrency control.
     *
     * @param key The key to reprepare
     * @param oldValue The expected current value for concurrency control
     * @param newValue The new value to prepare permanently
     * @return A Mono emitting true if reprepare succeeded, false otherwise
     *
     * @see reprepare(String, V, PreparedValue) for TTL-based reprepare
     */
    fun reprepare(
        key: String,
        oldValue: V,
        newValue: V
    ): Mono<Boolean> = reprepare(key, oldValue, newValue.toForever())

    /**
     * Reprepares a key with a new value that may have TTL.
     *
     * This method updates an existing key preparation with a new value and optional TTL.
     * The old value ensures atomicity and prevents concurrent modifications.
     *
     * @param key The key to reprepare
     * @param oldValue The expected current value for concurrency control
     * @param newValue The new prepared value with optional TTL
     * @return A Mono emitting true if reprepare succeeded, false otherwise
     */
    fun reprepare(
        key: String,
        oldValue: V,
        newValue: PreparedValue<V>
    ): Mono<Boolean>

    /**
     * Reprepares a key with a new permanent value.
     *
     * This method updates the value for an existing key preparation, keeping it permanent.
     *
     * @param key The key to reprepare
     * @param value The new permanent value
     * @return A Mono emitting true if reprepare succeeded, false otherwise
     */
    fun reprepare(
        key: String,
        value: V
    ): Mono<Boolean> = reprepare(key, value.toForever())

    /**
     * Reprepares a key with a new value that may have TTL.
     *
     * This method updates the value for an existing key preparation with optional TTL.
     *
     * @param key The key to reprepare
     * @param value The new prepared value with optional TTL
     * @return A Mono emitting true if reprepare succeeded, false otherwise
     */
    fun reprepare(
        key: String,
        value: PreparedValue<V>
    ): Mono<Boolean>

    /**
     * Reprepares by changing both key and value to new permanent values.
     *
     * This method atomically releases the old key and prepares the new key.
     * It's commonly used for scenarios like username changes where the identifier itself changes.
     *
     * @param oldKey The current key to release
     * @param oldValue The expected value of the old key for concurrency control
     * @param newKey The new key to prepare
     * @param newValue The permanent value for the new key
     * @return A Mono emitting true if the key change succeeded, false otherwise
     *
     * @throws IllegalArgumentException if oldKey equals newKey
     */
    fun reprepare(
        oldKey: String,
        oldValue: V,
        newKey: String,
        newValue: V
    ): Mono<Boolean> = reprepare(oldKey, oldValue, newKey, newValue.toForever())

    /**
     * Reprepares by changing both key and value with optional TTL.
     *
     * This method provides the most flexible reprepare operation, allowing changes to
     * both the key and value with TTL support. It ensures atomicity by first preparing
     * the new key, then rolling back the old key only if successful.
     *
     * @param oldKey The current key to release
     * @param oldValue The expected value of the old key for concurrency control
     * @param newKey The new key to prepare (must be different from oldKey)
     * @param newValue The prepared value for the new key with optional TTL
     * @return A Mono emitting true if the key change succeeded, false otherwise
     *
     * @throws IllegalArgumentException if oldKey equals newKey
     * @throws IllegalStateException if rollback of old key fails after new key is prepared
     */
    fun reprepare(
        oldKey: String,
        oldValue: V,
        newKey: String,
        newValue: PreparedValue<V>
    ): Mono<Boolean> {
        require(oldKey != newKey) {
            "oldKey must not be equals to newKey. oldKey:[$oldKey]"
        }
        return usingPrepare(newKey, newValue) { prepared ->
            if (!prepared) {
                return@usingPrepare Mono.just(false)
            }
            rollback(oldKey, oldValue).doOnNext {
                if (!it) {
                    throw IllegalStateException(
                        "Reprepare - Rollback failed. newKey:[$newKey] oldKey:[$oldKey],oldValue:[$oldValue]",
                    )
                }
            }
        }
    }

    /**
     * Executes an operation within a prepare context with permanent value.
     *
     * This method provides transaction-like semantics: it prepares a key, executes the
     * provided operation, and automatically rolls back the preparation if the operation fails.
     *
     * @param R The return type of the operation
     * @param key The key to prepare for the operation
     * @param value The permanent value to prepare
     * @param then A function that receives the preparation result and returns a Mono with the operation
     * @return A Mono with the operation result, with automatic rollback on failure
     *
     * @see usingPrepare(String, PreparedValue, (Boolean) -> Mono) for TTL support
     */
    fun <R> usingPrepare(
        key: String,
        value: V,
        then: (Boolean) -> Mono<R>
    ): Mono<R> = usingPrepare(key, value.toForever(), then)

    /**
     * Executes an operation within a prepare context with optional TTL.
     *
     * This method provides transaction-like semantics for key preparation. If the key preparation
     * succeeds and the subsequent operation fails, the preparation is automatically rolled back.
     * This ensures consistency and prevents resource leaks.
     *
     * @param R The return type of the operation
     * @param key The key to prepare for the operation
     * @param value The prepared value with optional TTL
     * @param then A function that receives the preparation result (true/false) and returns
     *             a Mono with the operation to execute
     * @return A Mono with the operation result, with automatic rollback on failure
     *
     * @throws Exception if the operation fails after successful preparation (rollback attempted)
     */
    fun <R> usingPrepare(
        key: String,
        value: PreparedValue<V>,
        then: (Boolean) -> Mono<R>
    ): Mono<R> {
        return prepare(key, value)
            .flatMap { prepared ->
                then(prepared).onErrorResume {
                    val errorMono = Mono.error<R>(it)
                    if (!prepared) {
                        return@onErrorResume errorMono
                    }
                    rollback(key, value.value)
                        .then(errorMono)
                }
            }
    }
}
