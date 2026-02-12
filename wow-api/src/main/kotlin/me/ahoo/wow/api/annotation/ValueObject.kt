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
 * Marks a class as a value object in domain-driven design (DDD).
 *
 * Value objects represent descriptive aspects of the domain that have no conceptual identity.
 * They are defined entirely by their values and should be immutable. Two value objects
 * with the same values are considered equal and interchangeable.
 *
 * Key characteristics of value objects:
 * - No identity - equality based on values, not identity
 * - Immutable - state cannot be changed after creation
 * - Self-contained - all data needed for behavior is encapsulated
 * - Replaceable - can be replaced with another instance having same values
 * - Side-effect free - operations don't modify external state
 *
 * Example usage:
 * ```kotlin
 * @ValueObject
 * data class Money(
 *     val amount: BigDecimal,
 *     val currency: Currency
 * ) {
 *
 *     operator fun plus(other: Money): Money {
 *         require(currency == other.currency) { "Currency mismatch" }
 *         return Money(amount + other.amount, currency)
 *     }
 *
 *     companion object {
 *         val ZERO = Money(BigDecimal.ZERO, Currency.USD)
 *     }
 * }
 *
 * @ValueObject
 * data class Address(
 *     val street: String,
 *     val city: String,
 *     val postalCode: String,
 *     val country: String
 * )
 * ```
 * @see EntityObject for objects with identity
 * @see AggregateRoot for aggregate root entities
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class ValueObject
