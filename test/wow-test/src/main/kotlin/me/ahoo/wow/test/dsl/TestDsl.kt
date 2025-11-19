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

package me.ahoo.wow.test.dsl

/**
 * DSL marker annotation for Wow testing framework.
 *
 * This annotation marks DSL (Domain Specific Language) interfaces and classes used in
 * the Wow testing framework to provide type-safe, hierarchical test specifications.
 * By applying this marker, the Kotlin compiler restricts implicit receiver access,
 * preventing accidental method calls across different DSL levels.
 *
 * ## Purpose
 *
 * The `@TestDsl` marker ensures that:
 * - Test DSLs maintain clear hierarchical boundaries
 * - Child DSL contexts cannot accidentally call parent DSL methods
 * - IDE provides accurate code completion and error highlighting
 * - Test code remains readable and maintainable
 *
 * ## Usage
 *
 * Apply this annotation to all DSL interfaces in the testing framework:
 *
 * ```kotlin
 * @TestDsl
 * interface AggregateDsl<S : Any> {
 *     fun on(block: GivenDsl<S>.() -> Unit)
 * }
 *
 * @TestDsl
 * interface GivenDsl<S : Any> {
 *     fun whenCommand(command: Any, block: ExpectDsl<S>.() -> Unit)
 * }
 *
 * @TestDsl
 * interface ExpectDsl<S : Any> {
 *     fun expectEvent(type: KClass<*>)
 * }
 * ```
 *
 * ## Benefits
 *
 * - **Type Safety**: Prevents calling methods from incorrect DSL contexts
 * - **Better IDE Support**: Improved code completion and error detection
 * - **Cleaner APIs**: Clear separation between different testing phases
 * - **Maintainability**: Easier to understand and modify test DSLs
 *
 * @see DslMarker for the underlying Kotlin compiler support
 * @see me.ahoo.wow.test.aggregate.dsl.AggregateDsl for aggregate testing DSL
 * @see me.ahoo.wow.test.saga.dsl.StatelessSagaDsl for saga testing DSL
 */
@DslMarker
annotation class TestDsl
